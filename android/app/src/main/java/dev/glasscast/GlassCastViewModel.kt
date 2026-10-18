package dev.glasscast

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import dev.glasscast.server.StreamService
import dev.glasscast.stream.FrameHub
import dev.glasscast.stream.JpegFrame
import dev.glasscast.stream.YuvJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val fps: Int = 0,
    val showCameraPermissionPrompt: Boolean = false,
    val error: String? = null,
) {
  val isRegistered: Boolean get() = registrationState == RegistrationState.REGISTERED
  val isRegistering: Boolean get() = registrationState == RegistrationState.REGISTERING
  val isStreaming: Boolean get() = streamState == StreamState.STREAMING

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
                else -> "Put on your glasses"
              }
        }
}

/**
 * Owns the DAT SDK lifecycle: registration, the [DeviceSession], and the camera [Stream].
 * Every frame is encoded to JPEG once and shared by the on-screen preview and the MJPEG server.
 */
class GlassCastViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val FRAME_RATE = 24
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  /** Kept apart from [state] so ~24 updates a second don't recompose the whole screen. */
  private val _preview = MutableStateFlow<Bitmap?>(null)
  val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

  val viewerCount: StateFlow<Int> = (application as GlassCastApplication).server.viewerCount

  private val deviceSelector by lazy { AutoDeviceSelector() }
  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var sessionJobs = emptyList<Job>()
  private var streamJobs = emptyList<Job>()
  private var restartAfterStop = false
  private val frameTimes = ArrayDeque<Long>()

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
    _state.update { it.copy(wantsStream = true) }
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
        .onFailure { error, _ -> fail(error.description) }
  }

  /** Ends the session; the SDK cascades the stop to the camera and its stream. */
  fun stopStreaming() {
    restartAfterStop = false
    _state.update { it.copy(wantsStream = false) }
    session?.stop()
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
          .onFailure { error, _ -> fail(error.description) }
    }
  }

  private fun beginStream() {
    val current = session ?: return
    if (camera != null || !_state.value.wantsStream) return
    StreamService.start(getApplication())
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
            fail(error.description)
            clearStreamResources()
          }
        }
        .onFailure { error, _ ->
          StreamService.stop(getApplication())
          fail(error.description)
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
                  DeviceSessionState.STOPPED -> cleanupSession()
                  else -> Unit
                }
              }
            },
            viewModelScope.launch { session.errors.collect { error -> fail(error.description) } },
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
              stream.errorStream.collect { error -> fail(error.description) }
            },
        )
  }

  /** Runs on [frameDispatcher]: I420 → JPEG once, shared by preview and desktop viewers. */
  private fun handleFrame(frame: VideoFrame) {
    if (frame.isCompressed) return
    val buffer = frame.buffer.duplicate()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val jpeg = YuvJpeg.i420ToJpeg(bytes, frame.width, frame.height) ?: return

    FrameHub.publish(JpegFrame(jpeg, frame.width, frame.height))
    _preview.value = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    val now = SystemClock.elapsedRealtime()
    frameTimes.addLast(now)
    while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1_000) frameTimes.removeFirst()
    val fps = frameTimes.size
    if (fps != _state.value.fps) _state.update { it.copy(fps = fps) }
    FrameHub.updateStatus {
      it.copy(fps = fps.toDouble(), width = frame.width, height = frame.height)
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
    session?.stop()
    StreamService.stop(getApplication())
    super.onCleared()
  }
}
