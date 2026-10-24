package dev.glasscast.stream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * Encodes the SDK's decoded frames (I420 / YUV420 planar) as JPEG. [zoom] above 1 keeps only
 * the center `1/zoom` of the frame (digital zoom), which costs nothing extra: YuvImage encodes
 * just the requested rectangle.
 */
object YuvJpeg {
  private const val QUALITY = 70

  fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, zoom: Float = 1f): ByteArray? =
      i420ToJpeg(i420, width, height, cropRect(width, height, zoom))

  /** Encodes only [crop], whose edges must be even. */
  fun i420ToJpeg(i420: ByteArray, width: Int, height: Int, crop: Rect): ByteArray? {
    if (i420.size < width * height * 3 / 2) return null
    val image = YuvImage(i420ToNv21(i420, width, height), ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream(width * height / 4)
    if (!image.compressToJpeg(crop, QUALITY, out)) return null
    return out.toByteArray()
  }

  /** Center crop for [zoom], on even coordinates because chroma is subsampled 2x2. */
  fun cropRect(width: Int, height: Int, zoom: Float): Rect {
    if (zoom <= 1.01f) return Rect(0, 0, width, height)
    val w = (width / zoom).toInt() and 1.inv()
    val h = (height / zoom).toInt() and 1.inv()
    val left = ((width - w) / 2) and 1.inv()
    val top = ((height - h) / 2) and 1.inv()
    return Rect(left, top, left + w, top + h)
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
