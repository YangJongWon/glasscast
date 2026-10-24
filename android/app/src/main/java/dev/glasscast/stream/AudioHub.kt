package dev.glasscast.stream

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared between the glasses microphone (producer) and `/audio` listeners (consumers).
 *
 * Chunks are 16 kHz mono 16-bit little-endian PCM. About one second is buffered; a listener
 * further behind loses the oldest chunks instead of drifting out of sync with the video.
 */
object AudioHub {
  const val SAMPLE_RATE = 16_000

  private val _available = MutableStateFlow(false)
  /** True while the microphone is running and `/audio` accepts listeners. */
  val available: StateFlow<Boolean> = _available.asStateFlow()

  private val _chunks =
      MutableSharedFlow<ByteArray>(extraBufferCapacity = 50, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val chunks: SharedFlow<ByteArray> = _chunks.asSharedFlow()

  fun publish(pcm: ByteArray) {
    _chunks.tryEmit(pcm)
  }

  fun setAvailable(available: Boolean) {
    _available.value = available
  }
}
