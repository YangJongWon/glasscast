package dev.glasscast.stream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/** Encodes the SDK's decoded frames (I420 / YUV420 planar) as JPEG. */
object YuvJpeg {
  private const val QUALITY = 70

  fun i420ToJpeg(i420: ByteArray, width: Int, height: Int): ByteArray? {
    if (i420.size < width * height * 3 / 2) return null
    val image = YuvImage(i420ToNv21(i420, width, height), ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream(width * height / 4)
    if (!image.compressToJpeg(Rect(0, 0, width, height), QUALITY, out)) return null
    return out.toByteArray()
  }

  /** I420 (Y plane, U plane, V plane) to NV21 (Y plane, interleaved VU), which YuvImage accepts. */
  private fun i420ToNv21(input: ByteArray, width: Int, height: Int): ByteArray {
    val ySize = width * height
    val quarter = ySize / 4
    val output = ByteArray(ySize + quarter * 2)
    System.arraycopy(input, 0, output, 0, ySize)
    for (i in 0 until quarter) {
      output[ySize + i * 2] = input[ySize + quarter + i] // V
      output[ySize + i * 2 + 1] = input[ySize + i] // U
    }
    return output
  }
}
