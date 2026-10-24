package dev.glasscast.stream

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor

/**
 * Records live-view frames (as shown, zoom included) and, optionally, 16 kHz mono PCM sound
 * into an H.264/AAC MP4.
 *
 * Frames are drawn onto the encoder's input surface, which scales them to the file's size and
 * timestamps them on arrival; audio is stamped with the same clock (System.nanoTime), so they
 * line up. A background thread moves encoded data into the muxer.
 */
class VideoRecorder(output: FileDescriptor, width: Int, height: Int, private val withAudio: Boolean) {
  companion object {
    private const val TAG = "GlassCastRecorder"
    private const val TIMEOUT_US = 10_000L
  }

  // H.264 needs even dimensions.
  private val width = width and 1.inv()
  private val height = height and 1.inv()

  private val video =
      MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
        configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, this@VideoRecorder.width, this@VideoRecorder.height)
                .apply {
                  setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                  setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                  setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                  setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
      }
  private val surface: Surface = video.createInputSurface()
  private val audio: MediaCodec? =
      if (!withAudio) null
      else
          MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioHub.SAMPLE_RATE, 1).apply {
                  setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                  setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
          }
  private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val frameRect = Rect(0, 0, this.width, this.height)

  private var videoTrack = -1
  private var audioTrack = -1
  private var muxerStarted = false
  private var sawKeyFrame = false
  @Volatile private var stopping = false
  private var videoDone = false
  private var audioDone = !withAudio
  private val drainer = Thread(::drainLoop, "GlassCastRecorder")

  init {
    video.start()
    audio?.start()
    drainer.start()
  }

  /** Draws one frame; the surface scales it to the file's size. Call from one thread. */
  fun drawFrame(bitmap: Bitmap) {
    if (stopping) return
    runCatching {
      val canvas = surface.lockHardwareCanvas()
      try {
        canvas.drawBitmap(bitmap, null, frameRect, paint)
      } finally {
        surface.unlockCanvasAndPost(canvas)
      }
    }
  }

  /** 16 kHz mono little-endian Int16 PCM, as served at `/audio`. Dropped if the encoder is busy. */
  fun appendAudio(pcm: ByteArray) {
    val codec = audio ?: return
    if (stopping) return
    runCatching {
      val index = codec.dequeueInputBuffer(0)
      if (index < 0) return
      val buffer = codec.getInputBuffer(index) ?: return
      buffer.clear()
      val length = minOf(pcm.size, buffer.remaining())
      buffer.put(pcm, 0, length)
      // The chunk ends now; its first sample was captured `duration` earlier.
      val durationUs = length / 2 * 1_000_000L / AudioHub.SAMPLE_RATE
      codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000 - durationUs, 0)
    }
  }

  /** Finishes the file. Blocks until the encoders have flushed; call off the main thread. */
  fun stop(): Boolean {
    if (stopping) return false
    stopping = true
    runCatching { video.signalEndOfInputStream() }
    audio?.let { codec ->
      runCatching {
        val index = codec.dequeueInputBuffer(TIMEOUT_US * 10)
        if (index >= 0) {
          codec.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } else {
          audioDone = true
        }
      }
    }
    drainer.join(5_000)
    val ok = muxerStarted
    runCatching { if (muxerStarted) muxer.stop() }
    runCatching { muxer.release() }
    runCatching { video.stop() }
    runCatching { video.release() }
    runCatching { audio?.stop() }
    runCatching { audio?.release() }
    surface.release()
    return ok
  }

  private fun drainLoop() {
    val info = MediaCodec.BufferInfo()
    try {
      while (!(videoDone && audioDone)) {
        if (!videoDone) videoDone = drain(video, info, isVideo = true)
        if (!audioDone) audioDone = drain(audio!!, info, isVideo = false)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Recording stopped: ${e.message}")
    }
  }

  /** Moves whatever the codec has ready into the muxer. Returns true at end of stream. */
  private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean): Boolean {
    while (true) {
      val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
      when {
        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(codec.outputFormat, isVideo)
        index >= 0 -> {
          val buffer = codec.getOutputBuffer(index)
          val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
          if (isVideo && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && muxerStarted) sawKeyFrame = true
          val ready = muxerStarted && (!isVideo || sawKeyFrame)
          if (buffer != null && ready && !isConfig && info.size > 0) {
            muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, info)
          }
          codec.releaseOutputBuffer(index, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
      }
    }
  }

  private fun addTrack(format: MediaFormat, isVideo: Boolean) {
    if (isVideo) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
    if (videoTrack >= 0 && (!withAudio || audioTrack >= 0)) {
      muxer.start()
      muxerStarted = true
      // Frames encoded before the muxer started were dropped; start the file on a key frame.
      video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }
  }
}
