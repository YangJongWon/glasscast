package dev.glasscast.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.glasscast.stream.AudioHub
import dev.glasscast.stream.FrameHub
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Re-broadcasts the glasses' live view over the local network as MJPEG.
 *
 * Routes (identical on iOS and in `tools/mock_server.py`):
 * - `GET /`             the bundled `viewer.html`
 * - `GET /stream`       `multipart/x-mixed-replace` JPEG stream
 * - `GET /snapshot.jpg` the newest frame
 * - `GET /audio`        glasses microphone as an endless 16 kHz mono WAV (503 while off)
 * - `GET /photo.jpg`    the newest photo taken with the glasses (404 before the first)
 * - `POST /capture`     asks the glasses to take a photo (202; 409 unless streaming)
 * - `GET /status`       JSON `{state, fps, width, height, clients, audio, photo}`
 */
class MjpegServer(
    private val context: Context,
    private val viewerHtml: ByteArray,
    private val scope: CoroutineScope,
) {
  companion object {
    const val PORT = 8080
    const val MAX_STREAM_CLIENTS = 4
    const val MAX_AUDIO_CLIENTS = 4
    private const val BONJOUR_TYPE = "_glasscast._tcp"
    private const val BOUNDARY = "glasscast"
    private const val MAX_REQUEST_BYTES = 16 * 1024
    private const val TAG = "GlassCastServer"
    private val CRLF = "\r\n".toByteArray()
  }

  private val _viewerCount = MutableStateFlow(0)
  /** Number of open `/stream` connections. */
  val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

  /** Called for `POST /capture`, on a server thread. */
  @Volatile var onCaptureRequest: (() -> Unit)? = null

  private val streamClients = AtomicInteger(0)
  private val audioClients = AtomicInteger(0)
  @Volatile private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null
  private var nsdListener: NsdManager.RegistrationListener? = null

  /** Starts listening on [PORT]. Safe to call repeatedly. */
  @Synchronized
  fun start() {
    if (acceptJob?.isActive == true) return
    acceptJob =
        scope.launch(Dispatchers.IO) {
          try {
            ServerSocket().use { socket ->
              socket.reuseAddress = true
              socket.bind(InetSocketAddress(PORT))
              serverSocket = socket
              registerBonjour()
              while (isActive) {
                val client = socket.accept()
                launch { handle(client) }
              }
            }
          } catch (e: IOException) {
            if (isActive) Log.w(TAG, "Server stopped: ${e.message}")
          } finally {
            serverSocket = null
            unregisterBonjour()
          }
        }
  }

  @Synchronized
  fun stop() {
    // accept() only returns on close, so close the socket before cancelling.
    serverSocket?.close()
    acceptJob?.cancel()
    acceptJob = null
  }

  // MARK: - Requests

  private suspend fun handle(socket: Socket) {
    socket.use { s ->
      try {
        s.tcpNoDelay = true
        s.soTimeout = 5_000
        val input = BufferedInputStream(s.getInputStream())
        val requestLine = readRequestHead(input) ?: return
        val parts = requestLine.split(" ")
        val out = s.getOutputStream()
        if (parts.size >= 2 && parts[0] == "POST" && parts[1].substringBefore('?') == "/capture") {
          capture(out)
          return
        }
        if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
          respond(out, "405 Method Not Allowed", "text/plain", "Method not allowed\n".toByteArray())
          return
        }
        when (parts[1].substringBefore('?')) {
          "/", "/index.html" -> respond(out, "200 OK", "text/html; charset=utf-8", viewerHtml)
          "/status" -> respond(out, "200 OK", "application/json", statusJson())
          "/snapshot.jpg" -> {
            val frame = FrameHub.frames.value
            if (frame != null) {
              respond(out, "200 OK", "image/jpeg", frame.jpeg)
            } else {
              respond(out, "503 Service Unavailable", "text/plain", "No frame yet\n".toByteArray())
            }
          }
          "/photo.jpg" -> {
            val photo = FrameHub.latestPhoto
            if (photo != null) {
              respond(out, "200 OK", "image/jpeg", photo)
            } else {
              respond(out, "404 Not Found", "text/plain", "No photo yet\n".toByteArray())
            }
          }
          "/stream" -> stream(s, input, out)
          "/audio" -> audio(s, input, out)
          else -> respond(out, "404 Not Found", "text/plain", "Not found\n".toByteArray())
        }
      } catch (_: IOException) {
        // Viewer went away mid-request; nothing to clean up beyond closing the socket.
      }
    }
  }

  /** Reads the request headers and returns the request line, or null if malformed. */
  private fun readRequestHead(input: InputStream): String? {
    val head = StringBuilder()
    var total = 0
    while (!head.endsWith("\r\n\r\n")) {
      val byte = input.read()
      if (byte < 0 || ++total > MAX_REQUEST_BYTES) return null
      head.append(byte.toChar())
    }
    return head.toString().substringBefore("\r\n")
  }

  /** The request body (if any) is ignored; the photo arrives later at `/photo.jpg`. */
  private fun capture(out: OutputStream) {
    if (FrameHub.status.value.state != "streaming") {
      respond(out, "409 Conflict", "text/plain", "Live view isn't running\n".toByteArray())
      return
    }
    onCaptureRequest?.invoke()
    respond(out, "202 Accepted", "text/plain", "Taking a photo\n".toByteArray())
  }

  private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
    out.write(
        ("HTTP/1.1 $status\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n")
            .toByteArray())
    out.write(body)
    out.flush()
  }

  // MARK: - MJPEG stream

  private suspend fun stream(socket: Socket, input: InputStream, out: OutputStream) {
    if (streamClients.incrementAndGet() > MAX_STREAM_CLIENTS) {
      streamClients.decrementAndGet()
      respond(out, "503 Service Unavailable", "text/plain", "Too many viewers\n".toByteArray())
      return
    }
    _viewerCount.value = streamClients.get()
    try {
      out.write(
          ("HTTP/1.1 200 OK\r\n" +
                  "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                  "Cache-Control: no-store\r\n" +
                  "Access-Control-Allow-Origin: *\r\n" +
                  "Connection: close\r\n\r\n")
              .toByteArray())
      out.flush()
      socket.soTimeout = 0

      coroutineScope {
        val writer = launch {
          // StateFlow conflates: a slow viewer gets the newest frame, never a backlog.
          FrameHub.frames.filterNotNull().collect { frame ->
            out.write(
                ("--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.jpeg.size}\r\n\r\n")
                    .toByteArray())
            out.write(frame.jpeg)
            out.write(CRLF)
            out.flush()
          }
        }
        // The viewer sends nothing after its request, so a finished read means it hung up.
        // This also catches hang-ups while no frames are flowing (stream stopped).
        launch(Dispatchers.IO) {
          runCatching { while (input.read() >= 0) Unit }
          writer.cancel()
        }
        writer.join()
        // Unblocks the hang-up reader if the writer ended first (write error).
        socket.close()
      }
    } catch (_: IOException) {
      // Viewer disconnected.
    } finally {
      _viewerCount.value = streamClients.decrementAndGet()
    }
  }

  // MARK: - Audio stream

  private suspend fun audio(socket: Socket, input: InputStream, out: OutputStream) {
    if (!AudioHub.available.value) {
      respond(out, "503 Service Unavailable", "text/plain", "Audio is off\n".toByteArray())
      return
    }
    if (audioClients.incrementAndGet() > MAX_AUDIO_CLIENTS) {
      audioClients.decrementAndGet()
      respond(out, "503 Service Unavailable", "text/plain", "Too many listeners\n".toByteArray())
      return
    }
    try {
      out.write(
          ("HTTP/1.1 200 OK\r\n" +
                  "Content-Type: audio/wav\r\n" +
                  "Cache-Control: no-store\r\n" +
                  "Access-Control-Allow-Origin: *\r\n" +
                  "Connection: close\r\n\r\n")
              .toByteArray())
      out.write(wavHeader(AudioHub.SAMPLE_RATE))
      out.flush()
      socket.soTimeout = 0

      coroutineScope {
        val writer = launch {
          AudioHub.chunks.collect { pcm ->
            out.write(pcm)
            out.flush()
          }
        }
        // Hang up when sound is turned off on the phone.
        val soundOff = launch {
          AudioHub.available.first { !it }
          writer.cancel()
        }
        launch(Dispatchers.IO) {
          runCatching { while (input.read() >= 0) Unit }
          writer.cancel()
        }
        writer.join()
        soundOff.cancel()
        socket.close()
      }
    } catch (_: IOException) {
      // Listener disconnected.
    } finally {
      audioClients.decrementAndGet()
    }
  }

  /**
   * WAV header for an endless mono 16-bit stream. The size fields are left at their maximum,
   * which browsers, VLC and OBS (ffmpeg) treat as "read until the connection ends".
   */
  private fun wavHeader(sampleRate: Int): ByteArray =
      ByteBuffer.allocate(44)
          .order(ByteOrder.LITTLE_ENDIAN)
          .put("RIFF".toByteArray())
          .putInt(-1)
          .put("WAVEfmt ".toByteArray())
          .putInt(16) // fmt chunk size
          .putShort(1) // PCM
          .putShort(1) // mono
          .putInt(sampleRate)
          .putInt(sampleRate * 2) // byte rate
          .putShort(2) // block align
          .putShort(16) // bits per sample
          .put("data".toByteArray())
          .putInt(-1)
          .array()

  private fun statusJson(): ByteArray {
    val status = FrameHub.status.value
    return JSONObject()
        .put("app", "GlassCast")
        .put("platform", "android")
        .put("state", status.state)
        .put("fps", Math.round(status.fps * 10) / 10.0)
        .put("width", status.width)
        .put("height", status.height)
        .put("clients", streamClients.get())
        .put("audio", AudioHub.available.value)
        .put("photo", FrameHub.photoSeq)
        .toString()
        .toByteArray()
  }

  // MARK: - Bonjour / NSD

  private fun registerBonjour() {
    val manager = context.getSystemService(NsdManager::class.java) ?: return
    val info =
        NsdServiceInfo().apply {
          serviceName = "GlassCast"
          serviceType = BONJOUR_TYPE
          port = PORT
        }
    val listener =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(info: NsdServiceInfo) = Unit
          override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
          override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Bonjour registration failed: $errorCode")
          }
          override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
    nsdListener = listener
    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
  }

  private fun unregisterBonjour() {
    val listener = nsdListener ?: return
    nsdListener = null
    runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(listener) }
  }
}
