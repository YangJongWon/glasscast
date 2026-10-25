package dev.glasscast

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import dev.glasscast.photos.PhotoStore
import dev.glasscast.server.StreamService
import dev.glasscast.stream.AudioHub
import dev.glasscast.stream.FrameHub
import dev.glasscast.stream.FrameProcessor
import dev.glasscast.stream.GlassesMic
import dev.glasscast.stream.GlassesMicException
import dev.glasscast.stream.VideoRecorder
import dev.glasscast.stream.JpegFrame
import dev.glasscast.stream.YuvJpeg
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Quality(val label: String, val videoQuality: VideoQuality) {
  LOW("360p", VideoQuality.LOW),
  MEDIUM("504p", VideoQuality.MEDIUM),
  HIGH("720p", VideoQuality.HIGH),
}

data class UiState(
    val sdkReady: Boolean = false,
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val hasActiveDevice: Boolean = false,
    val sessionState: DeviceSessionState = DeviceSessionState.IDLE,
    val streamState: StreamState = StreamState.STOPPED,
    /** Streaming was requested and hasn't been stopped (session or stream may still be starting). */
    val wantsStream: Boolean = false,
    val quality: Quality = Quality.MEDIUM,
    /** Digital zoom for the preview and desktop viewers, 1..4. */
    val zoom: Float = 1f,
    /** Auto brightness and contrast. */
    val autoEnhance: Boolean = false,
    /** Smooths out head shake. */
    val stabilize: Boolean = false,
    /** Zooms toward faces; replaces manual zoom while on. */
    val autoFrame: Boolean = false,
    /** Landscape only: fill the screen instead of fitting between bars. This screen only. */
    val landscapeFill: Boolean = false,
    /** Off by default and on every launch; the mic only runs while this is on and live view is wanted. */
    val audioEnabled: Boolean = false,
    val capturingAudio: Boolean = false,
    /** A photo was requested and hasn't arrived yet. */
    val takingPhoto: Boolean = false,
    /** When the current recording started (elapsedRealtime); null while not recording. */
    val recordingSince: Long? = null,
    val fps: Int = 0,
    val showCameraPermissionPrompt: Boolean = false,
    val error: String? = null,
    /** A problem with the glasses connection, shown as a banner with what to do about it. */
    val connectionIssue: ConnectionIssue? = null,
) {
  val isRegistered: Boolean get() = registrationState == RegistrationState.REGISTERED
  val isRegistering: Boolean get() = registrationState == RegistrationState.REGISTERING
  val isStreaming: Boolean get() = streamState == StreamState.STREAMING
  val isRecording: Boolean get() = recordingSince != null

  val statusText: String
    get() =
        when (streamState) {
          StreamState.STREAMING -> "Live"
          StreamState.PAUSED -> "Paused on glasses"
          StreamState.STARTING, StreamState.STARTED -> "Starting camera…"
          StreamState.STOPPING -> "Stopping…"
          else ->
              when {
                sessionState == DeviceSessionState.STARTING -> "Connecting to glasses…"
                hasActiveDevice -> "Ready"
                else -> "Glasses not connected"
              }
        }

  /** One line under the status explaining what to do, when the state calls for it. */
  val statusHint: String?
    get() =
        when {
          streamState == StreamState.PAUSED -> "Unfold your glasses and put them on to continue."
          streamState == StreamState.STOPPED &&
              sessionState != DeviceSessionState.STARTING &&
              !hasActiveDevice -> "Turn on your glasses, unfold them and keep them near your phone."
          else -> null
        }
}

/**
 * Owns the DAT SDK lifecycle: registration, the [DeviceSession], and the camera [Stream].
 * Every frame is encoded to JPEG once and shared by the on-screen preview and the MJPEG server.
 * When the user turns sound on, the glasses' microphone goes to the server's `/audio` route.
 */
class GlassCastViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val FRAME_RATE = 24
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 4f
    private const val PREFS = "glasscast"
  }

  private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private val _state =
      MutableStateFlow(
          UiState(
              zoom = prefs.getFloat("zoom", 1f),
              autoEnhance = prefs.getBoolean("autoEnhance", false),
              stabilize = prefs.getBoolean("stabilize", false),
              autoFrame = prefs.getBoolean("autoFrame", false),
              landscapeFill = prefs.getBoolean("landscapeFill", false),
          ))
  val state: StateFlow<UiState> = _state.asStateFlow()

  /** Kept apart from [state] so ~24 updates a second don't recompose the whole screen. */
  private val _preview = MutableStateFlow<Bitmap?>(null)
  val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

  private val server = (application as GlassCastApplication).server
  val viewerCount: StateFlow<Int> = server.viewerCount

  /** Photos taken with the glasses (Pictures/GlassCast). */
  val photos = PhotoStore(application)

  private val deviceSelector by lazy { AutoDeviceSelector() }
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var sessionJobs = emptyList<Job>()
  private var streamJobs = emptyList<Job>()
  private var restartAfterStop = false
  private val frameTimes = ArrayDeque<Long>()
  private val mic = GlassesMic(application)
  private val processor = FrameProcessor()
  /** Fed from the frame and microphone threads. */
  @Volatile private var recorder: VideoRecorder? = null
  private var recording: Pair<PhotoStore.Photo, ParcelFileDescriptor>? = null

  init {
    applyProcessing()
    viewModelScope.launch { photos.reload() }
    // Desktop viewers' "Take photo" button.
    server.onCaptureRequest = { viewModelScope.launch { takePhoto() } }
  }

  /** Call once Android permissions are granted and `Wearables.initialize` has run. */
  fun onSdkReady() {
    if (_state.value.sdkReady) return
    _state.update { it.copy(sdkReady = true) }
    viewModelScope.launch {
      Wearables.registrationState.collect { value ->
        _state.update { it.copy(registrationState = value) }
      }
    }
    viewModelScope.launch {
      deviceSelector.activeDeviceFlow().collect { device ->
        _state.update { it.copy(hasActiveDevice = device != null) }
      }
    }
  }

  // MARK: - Registration

  fun connectGlasses(activity: Activity) {
    if (_state.value.isRegistering) return
    Wearables.startRegistration(activity)
  }

  fun disconnectGlasses(activity: Activity) {
    stopStreaming()
    Wearables.startUnregistration(activity)
  }

  // MARK: - Streaming

  /** Starts a device session if needed; the camera is added once the session reports STARTED. */
  fun startStreaming() {
    _state.update { it.copy(wantsStream = true, connectionIssue = null) }
    // Meta recommends setting up HFP before the camera stream starts.
    updateAudioCapture()
    session?.let {
      if (_state.value.sessionState == DeviceSessionState.STARTED) beginStreamIfPermitted()
      return
    }
    Wearables.createSession(deviceSelector)
        .onSuccess { created ->
          session = created
          // Subscribe before start() so no initial transitions are missed.
          observeSession(created)
          _state.update { it.copy(sessionState = DeviceSessionState.STARTING) }
          created.start()
        }
        .onFailure { error, _ -> report(error) }
  }

  fun retryConnection() {
    _state.update { it.copy(connectionIssue = null) }
    startStreaming()
  }

  fun dismissConnectionIssue() {
    _state.update { it.copy(connectionIssue = null) }
  }

  /** Ends the session; the SDK cascades the stop to the camera and its stream. */
  fun stopStreaming() {
    restartAfterStop = false
    _state.update { it.copy(wantsStream = false) }
    updateAudioCapture()
    session?.stop()
  }

  // MARK: - Photos

  /** Asks the glasses camera for a full photo; returns once it is saved or has failed. */
  suspend fun takePhoto() {
    val current = camera ?: return
    if (!_state.value.isStreaming || _state.value.takingPhoto) return
    _state.update { it.copy(takingPhoto = true) }
    try {
      val result =
          withTimeoutOrNull(20_000) { current.stream.capturePhoto() }
              ?: return fail("The photo didn't arrive from the glasses. Try again.")
      val photo = result.getOrNull() ?: return fail(result.errorOrNull()?.description ?: "Couldn't take a photo.")
      val jpeg =
          withContext(Dispatchers.Default) { toJpeg(photo) }
              ?: return fail("The glasses sent a photo GlassCast couldn't read.")
      photos.add(jpeg)
      FrameHub.publishPhoto(jpeg)
    } catch (e: Exception) {
      fail("Couldn't save the photo: ${e.message}")
    } finally {
      _state.update { it.copy(takingPhoto = false) }
    }
  }

  /** Keeps everything as JPEG so the gallery, sharing and `/photo.jpg` agree. */
  private fun toJpeg(photo: PhotoData): ByteArray? {
    val bitmap =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val buffer = photo.data.duplicate()
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
          }
        }
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
      out.toByteArray()
    }
  }

  // MARK: - Recording

  /** Records the live view as shown (zoom included), with sound when it is on. */
  fun startRecording() {
    val frame = _preview.value ?: return
    if (!_state.value.isStreaming || _state.value.isRecording) return
    _state.update { it.copy(recordingSince = SystemClock.elapsedRealtime()) }
    viewModelScope.launch {
      try {
        val video = photos.newVideo()
        val file = photos.openForWriting(video) ?: error("Couldn't open the video file.")
        val created =
            withContext(Dispatchers.IO) {
              VideoRecorder(file.fileDescriptor, frame.width, frame.height, withAudio = mic.isRunning)
            }
        recording = video to file
        recorder = created
        // Stop was tapped while the encoders were starting.
        if (!_state.value.isRecording) {
          _state.update { it.copy(recordingSince = SystemClock.elapsedRealtime()) }
          stopRecording()
        }
      } catch (e: Exception) {
        _state.update { it.copy(recordingSince = null) }
        fail("Couldn't start recording: ${e.message}")
      }
    }
  }

  fun stopRecording() {
    if (!_state.value.isRecording) return
    _state.update { it.copy(recordingSince = null) }
    val active = recorder ?: return
    recorder = null
    val (video, file) = recording ?: return
    recording = null
    // The app scope, so the file is still finished when this runs from onCleared().
    getApplication<GlassCastApplication>().appScope.launch {
      val recorded = withContext(Dispatchers.IO) { active.stop().also { file.close() } }
      photos.finishVideo(video, recorded)
      if (!recorded) fail("Nothing was recorded. Keep live view running a little longer.")
    }
  }

  // MARK: - Audio

  /** Turning sound on requires RECORD_AUDIO; MainActivity asks for it first. */
  fun setAudioEnabled(enabled: Boolean) {
    if (enabled == _state.value.audioEnabled) return
    _state.update { it.copy(audioEnabled = enabled) }
    updateAudioCapture()
  }

  fun onMicrophonePermissionDenied() {
    fail("Microphone access is off. Turn it on for GlassCast in Settings.")
  }

  /** Runs the microphone exactly while sound is on and live view is wanted. */
  @Suppress("MissingPermission") // audioEnabled is only set after RECORD_AUDIO is granted.
  private fun updateAudioCapture() {
    val shouldCapture = _state.value.audioEnabled && _state.value.wantsStream
    if (shouldCapture == mic.isRunning) return
    if (shouldCapture) {
      try {
        mic.start(viewModelScope) { pcm ->
          AudioHub.publish(pcm)
          recorder?.appendAudio(pcm)
        }
        AudioHub.setAvailable(true)
      } catch (e: GlassesMicException) {
        _state.update { it.copy(audioEnabled = false) }
        fail(e.message ?: "Couldn't start the glasses microphone.")
      }
    } else {
      AudioHub.setAvailable(false)
      mic.stop()
    }
    _state.update { it.copy(capturingAudio = mic.isRunning) }
    // Add or drop the service's microphone type so capture keeps working with the screen off.
    // Skipped when stopping: the service is about to go away.
    if (camera != null && _state.value.wantsStream) {
      StreamService.start(getApplication(), microphone = mic.isRunning)
    }
  }

  fun setZoom(value: Float) {
    // Zooming by hand takes over from auto framing.
    if (_state.value.autoFrame) setAutoFrame(false)
    val clamped = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    prefs.edit().putFloat("zoom", clamped).apply()
    _state.update { it.copy(zoom = clamped) }
    applyProcessing()
  }

  private fun applyProcessing() {
    val s = _state.value
    processor.settings = FrameProcessor.Settings(s.zoom, s.autoEnhance, s.stabilize, s.autoFrame)
  }

  fun setAutoEnhance(on: Boolean) {
    prefs.edit().putBoolean("autoEnhance", on).apply()
    _state.update { it.copy(autoEnhance = on) }
    applyProcessing()
  }

  fun setStabilize(on: Boolean) {
    prefs.edit().putBoolean("stabilize", on).apply()
    _state.update { it.copy(stabilize = on) }
    applyProcessing()
  }

  fun setAutoFrame(on: Boolean) {
    prefs.edit().putBoolean("autoFrame", on).apply()
    _state.update { it.copy(autoFrame = on) }
    applyProcessing()
  }

  fun setLandscapeFill(on: Boolean) {
    prefs.edit().putBoolean("landscapeFill", on).apply()
    _state.update { it.copy(landscapeFill = on) }
  }

  fun setQuality(quality: Quality) {
    if (quality == _state.value.quality) return
    _state.update { it.copy(quality = quality) }
    // Stream settings are fixed per camera, so re-add the camera with the new quality.
    camera?.let {
      restartAfterStop = true
      it.close()
    }
  }

  /** Called after the user confirms the switch to the Meta AI app for camera permission. */
  fun confirmCameraPermission(request: suspend (Permission) -> PermissionStatus) {
    _state.update { it.copy(showCameraPermissionPrompt = false) }
    viewModelScope.launch {
      if (request(Permission.CAMERA) == PermissionStatus.Granted) {
        beginStream()
      } else {
        fail("Camera permission was denied in the Meta AI app.")
      }
    }
  }

  fun cancelCameraPermission() {
    _state.update { it.copy(showCameraPermissionPrompt = false) }
    stopStreaming()
  }

  fun clearError() {
    _state.update { it.copy(error = null) }
  }

  private fun beginStreamIfPermitted() {
    if (!_state.value.wantsStream || camera != null) return
    viewModelScope.launch {
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            if (status == PermissionStatus.Granted) {
              beginStream()
            } else {
              _state.update { it.copy(showCameraPermissionPrompt = true) }
            }
          }
          .onFailure { error, _ -> report(error) }
    }
  }

  private fun beginStream() {
    val current = session ?: return
    if (camera != null || !_state.value.wantsStream) return
    StreamService.start(getApplication(), microphone = mic.isRunning)
    current
        .addCamera(
            StreamConfiguration(
                videoQuality = _state.value.quality.videoQuality,
                frameRate = FRAME_RATE,
            ))
        .onSuccess { added ->
          camera = added
          observeStream(added.stream)
          _state.update { it.copy(streamState = StreamState.STARTING) }
          added.stream.start().onFailure { error, _ ->
            report(error)
            clearStreamResources()
          }
        }
        .onFailure { error, _ ->
          StreamService.stop(getApplication())
          report(error)
        }
  }

  // MARK: - Observation

  private fun observeSession(session: DeviceSession) {
    sessionJobs =
        listOf(
            viewModelScope.launch {
              session.state.collect { state ->
                _state.update { it.copy(sessionState = state) }
                when (state) {
                  DeviceSessionState.STARTED -> beginStreamIfPermitted()
                  DeviceSessionState.STOPPED -> {
                    // Not stopped by the user (stopStreaming clears wantsStream first): the
                    // glasses went away.
                    if (_state.value.wantsStream && _state.value.connectionIssue == null) {
                      _state.update { it.copy(connectionIssue = ConnectionIssue.LOST) }
                    }
                    cleanupSession()
                  }
                  else -> Unit
                }
              }
            },
            viewModelScope.launch {
              session.errors.collect { error ->
                // A compatibility notice, not a failure: the session keeps working.
                if (error == DeviceSessionError.DWA_OUT_OF_STU_RANGE) return@collect
                report(error)
                // A session that fails before it starts may never report STOPPED. Drop it so
                // the button resets and the next tap creates a fresh session.
                if (_state.value.sessionState != DeviceSessionState.STARTED) {
                  session.stop()
                  cleanupSession()
                }
              }
            },
        )
  }

  private fun observeStream(stream: Stream) {
    streamJobs =
        listOf(
            viewModelScope.launch(frameDispatcher) { stream.videoStream.collect(::handleFrame) },
            viewModelScope.launch {
              // state replays STOPPED on subscribe (before start()), so only react to a stop
              // after the stream has been active.
              var hasBeenActive = false
              stream.state.collect { state ->
                _state.update { it.copy(streamState = state) }
                FrameHub.updateStatus { it.copy(state = statusName(state)) }
                val terminal = state == StreamState.STOPPED || state == StreamState.CLOSED
                if (!terminal) {
                  hasBeenActive = true
                } else if (hasBeenActive) {
                  onStreamStopped()
                }
              }
            },
            viewModelScope.launch {
              stream.errorStream.collect { error -> report(error) }
            },
        )
  }

  /** Runs on [frameDispatcher]: I420 → JPEG once, shared by preview and desktop viewers. */
  private fun handleFrame(frame: VideoFrame) {
    if (frame.isCompressed) return
    val buffer = frame.buffer.duplicate()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val crop = processor.process(bytes, frame.width, frame.height)
    val jpeg = YuvJpeg.i420ToJpeg(bytes, frame.width, frame.height, crop) ?: return

    FrameHub.publish(JpegFrame(jpeg, crop.width(), crop.height()))
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    _preview.value = bitmap
    if (bitmap != null) recorder?.drawFrame(bitmap)

    val now = SystemClock.elapsedRealtime()
    frameTimes.addLast(now)
    while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1_000) frameTimes.removeFirst()
    val fps = frameTimes.size
    if (fps != _state.value.fps) _state.update { it.copy(fps = fps) }
    FrameHub.updateStatus {
      it.copy(fps = fps.toDouble(), width = crop.width(), height = crop.height())
    }
  }

  private fun onStreamStopped() {
    clearStreamResources()
    if (restartAfterStop && _state.value.sessionState == DeviceSessionState.STARTED) {
      restartAfterStop = false
      beginStream()
    }
  }

  private fun clearStreamResources() {
    // Keep what was recorded so far.
    stopRecording()
    streamJobs.forEach { it.cancel() }
    streamJobs = emptyList()
    // Closing detaches the capability so a later addCamera() can succeed.
    camera?.close()
    camera = null
    if (!restartAfterStop) StreamService.stop(getApplication())
    _preview.value = null
    FrameHub.clear()
    FrameHub.updateStatus { it.copy(state = "stopped", fps = 0.0) }
    viewModelScope.launch(frameDispatcher) { frameTimes.clear() }
    _state.update { it.copy(streamState = StreamState.STOPPED, fps = 0) }
  }

  private fun cleanupSession() {
    clearStreamResources()
    sessionJobs.forEach { it.cancel() }
    sessionJobs = emptyList()
    session = null
    // Stopped by the user, or the glasses dropped the session (taken off, out of range).
    _state.update { it.copy(wantsStream = false, sessionState = DeviceSessionState.STOPPED) }
    updateAudioCapture()
  }

  private fun report(error: DatError) {
    _state.update { it.copy(connectionIssue = ConnectionIssue.from(error)) }
  }

  private fun fail(message: String) {
    _state.update { it.copy(error = message) }
  }

  /** Names used by `/status` and `web/viewer.html`. */
  private fun statusName(state: StreamState): String =
      when (state) {
        StreamState.STREAMING -> "streaming"
        StreamState.PAUSED -> "paused"
        StreamState.STARTING, StreamState.STARTED -> "starting"
        else -> "stopped"
      }

  override fun onCleared() {
    stopRecording()
    server.onCaptureRequest = null
    AudioHub.setAvailable(false)
    mic.stop()
    session?.stop()
    StreamService.stop(getApplication())
    super.onCleared()
  }
}
