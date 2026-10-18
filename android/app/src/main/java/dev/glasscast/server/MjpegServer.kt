package dev.glasscast.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.glasscast.stream.FrameHub
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
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
 * - `GET /status`       JSON `{state, fps, width, height, clients}`
 */
class MjpegServer(
    private val context: Context,
    private val viewerHtml: ByteArray,
    private val scope: CoroutineScope,
) {
  companion object {
    const val PORT = 8080
    const val MAX_STREAM_CLIENTS = 4
    private const val BONJOUR_TYPE = "_glasscast._tcp"
    private const val BOUNDARY = "glasscast"
    private const val MAX_REQUEST_BYTES = 16 * 1024
    private const val TAG = "GlassCastServer"
    private val CRLF = "\r\n".toByteArray()
  }

  private val _viewerCount = MutableStateFlow(0)
  /** Number of open `/stream` connections. */
  val viewerCount: StateFlow<Int> = _viewerCount.asStateFlow()

  private val streamClients = AtomicInteger(0)
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
          "/stream" -> stream(s, input, out)
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
    return head.substringBefore("\r\n")
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
