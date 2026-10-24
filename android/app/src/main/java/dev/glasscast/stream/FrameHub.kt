package dev.glasscast.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One encoded frame. Deliberately not a data class: every frame must be a distinct StateFlow value. */
class JpegFrame(val jpeg: ByteArray, val width: Int, val height: Int)

/** What `/status` reports. `state` uses the names `web/viewer.html` understands. */
data class StreamStatus(
    val state: String = "stopped",
    val fps: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Shared between the glasses stream (producer) and the MJPEG server (consumers).
 *
 * Only the newest frame is kept. StateFlow conflates, so a slow viewer simply skips
 * to the latest frame instead of building up a backlog.
 */
object FrameHub {
  private val _frames = MutableStateFlow<JpegFrame?>(null)
  val frames: StateFlow<JpegFrame?> = _frames.asStateFlow()

  private val _status = MutableStateFlow(StreamStatus())
  val status: StateFlow<StreamStatus> = _status.asStateFlow()

  fun publish(frame: JpegFrame) {
    _frames.value = frame
  }

  fun clear() {
    _frames.value = null
  }

  fun updateStatus(transform: (StreamStatus) -> StreamStatus) {
    _status.update(transform)
  }

  /** Newest photo taken with the glasses, served at `/photo.jpg`. */
  @Volatile var latestPhoto: ByteArray? = null
    private set
  /** Increments with every photo so viewers can tell when a new one arrived. */
  @Volatile var photoSeq = 0
    private set

  @Synchronized
  fun publishPhoto(jpeg: ByteArray) {
    latestPhoto = jpeg
    photoSeq += 1
  }
}
