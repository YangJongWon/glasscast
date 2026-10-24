package dev.glasscast.stream

import android.Manifest.permission.RECORD_AUDIO
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GlassesMicException(message: String) : Exception(message)

/**
 * Captures the glasses' microphone over Bluetooth SCO (HFP) as 16 kHz mono 16-bit PCM.
 *
 * HFP is the only route to the glasses' microphones. It carries voice tuned to the wearer,
 * and it switches the glasses' speakers out of A2DP while active. The phone's own microphone
 * is never used as a fallback.
 */
class GlassesMic(context: Context) {
  companion object {
    /** 20 ms of audio per chunk. */
    private const val CHUNK_BYTES = AudioHub.SAMPLE_RATE / 50 * 2
  }

  private val audioManager = context.getSystemService(AudioManager::class.java)
  private var record: AudioRecord? = null
  private var job: Job? = null

  val isRunning: Boolean get() = record != null

  /** Routes input to the glasses and starts capturing. [onChunk] runs on an IO thread. */
  @RequiresPermission(RECORD_AUDIO)
  fun start(scope: CoroutineScope, onChunk: (ByteArray) -> Unit) {
    if (record != null) return
    val glasses =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
            ?: throw GlassesMicException(
                "Couldn't find the glasses microphone. Make sure the glasses are connected over Bluetooth.")
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    if (!audioManager.setCommunicationDevice(glasses)) {
      releaseRoute()
      throw GlassesMicException("Couldn't switch audio input to the glasses.")
    }

    val minBuffer =
        AudioRecord.getMinBufferSize(
            AudioHub.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val newRecord =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioHub.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
    if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
      newRecord.release()
      releaseRoute()
      throw GlassesMicException("Couldn't open the glasses microphone.")
    }
    newRecord.startRecording()
    record = newRecord
    job =
        scope.launch(Dispatchers.IO) {
          val buffer = ByteArray(CHUNK_BYTES)
          try {
            while (isActive) {
              val read = newRecord.read(buffer, 0, buffer.size)
              if (read < 0) break
              if (read > 0) onChunk(buffer.copyOf(read))
            }
          } finally {
            newRecord.release()
          }
        }
  }

  fun stop() {
    val current = record ?: return
    record = null
    job?.cancel()
    job = null
    // Unblocks read(); the capture loop releases the recorder on its way out.
    runCatching { current.stop() }
    releaseRoute()
  }

  private fun releaseRoute() {
    audioManager.clearCommunicationDevice()
    audioManager.mode = AudioManager.MODE_NORMAL
  }
}
