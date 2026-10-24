package dev.glasscast.stream

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.media.FaceDetector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns each raw I420 frame into what the preview, desktop viewers and recordings show: digital
 * zoom, auto framing, stabilization and auto enhance, with no extra libraries.
 *
 * Every step but enhance is a crop, worked out in normalized coordinates (0..1, origin top-left)
 * and returned as a pixel [Rect] for [YuvJpeg]:
 * 1. **Framing**: manual zoom crops the center; auto framing eases the crop toward faces found
 *    by the platform FaceDetector.
 * 2. **Stabilization**: block matching on a small copy of the luma plane measures the shake;
 *    a slightly tighter crop slides against it, letting slow pans through.
 * 3. **Enhance**: auto levels and gamma on the luma plane, measured about once a second and
 *    eased so the picture doesn't flicker. Modifies the frame in place.
 *
 * Call [process] from one thread (the frame thread); [settings] may change from any thread.
 */
class FrameProcessor {
  data class Settings(
      val zoom: Float = 1f,
      val enhance: Boolean = false,
      val stabilize: Boolean = false,
      val autoFrame: Boolean = false,
  )

  companion object {
    /** Share of the frame kept when stabilizing; the rest is room for the crop to move. */
    private const val STABILIZED_SCALE = 0.84f
    /** Width of the luma copy used for motion and face detection. */
    private const val ANALYSIS_WIDTH = 96
    private const val SEARCH = 6 // pixels each way, in the analysis copy
    private const val FRAMING_INTERVAL = 6
    private const val ENHANCE_INTERVAL = 24
    private const val FACE_WIDTH = 160 // FaceDetector needs an even width
  }

  @Volatile var settings = Settings()

  private var last = Settings()
  private var frameIndex = 0

  // Stabilization
  private var previous: ByteArray? = null
  private var driftX = 0f
  private var driftY = 0f
  private var smoothX = 0f
  private var smoothY = 0f

  // Framing: the crop being shown, and the one it is easing toward.
  private var framing = RectF(0f, 0f, 1f, 1f)
  private var framingTarget = RectF(0f, 0f, 1f, 1f)
  private var framesWithoutFace = 0

  // Enhance: current and target levels, as black point, white point and gamma.
  private var black = 0f
  private var white = 255f
  private var gamma = 1f
  private var targetBlack = 0f
  private var targetWhite = 255f
  private var targetGamma = 1f
  private val lut = ByteArray(256)

  /** Returns the crop to encode; with enhance on, also corrects [i420]'s luma in place. */
  fun process(i420: ByteArray, width: Int, height: Int): Rect {
    val s = settings
    if (s != last) reset(keepFraming = s.autoFrame && last.autoFrame)
    last = s
    frameIndex++

    if (!s.enhance && !s.stabilize && !s.autoFrame) return YuvJpeg.cropRect(width, height, s.zoom)

    val needsAnalysis = s.stabilize || (s.autoFrame && frameIndex % FRAMING_INTERVAL == 1)
    val (small, sw, sh) = if (needsAnalysis) downsample(i420, width, height) else Triple(ByteArray(0), 0, 0)

    var crop: RectF
    if (s.autoFrame) {
      if (frameIndex % FRAMING_INTERVAL == 1) updateFraming(i420, width, height)
      framing = ease(framing, framingTarget, 0.08f)
      crop = RectF(framing)
    } else {
      val side = 1f / s.zoom
      crop = RectF((1 - side) / 2, (1 - side) / 2, (1 + side) / 2, (1 + side) / 2)
    }

    if (s.stabilize) {
      val (dx, dy) = stabilizationOffset(small, sw, sh)
      crop = scale(crop, STABILIZED_SCALE)
      crop.offset(dx, dy)
    }
    crop = clampToUnit(crop)

    if (s.enhance) enhance(i420, width, height)

    return Rect(
        (crop.left * width).toInt() and 1.inv(),
        (crop.top * height).toInt() and 1.inv(),
        (crop.right * width).toInt() and 1.inv(),
        (crop.bottom * height).toInt() and 1.inv(),
    )
  }

  private fun reset(keepFraming: Boolean) {
    previous = null
    driftX = 0f
    driftY = 0f
    smoothX = 0f
    smoothY = 0f
    black = 0f
    white = 255f
    gamma = 1f
    if (!keepFraming) {
      framing = RectF(0f, 0f, 1f, 1f)
      framingTarget = RectF(0f, 0f, 1f, 1f)
      framesWithoutFace = 0
    }
  }

  // MARK: - Stabilization

  /** Box-filtered luma at ANALYSIS_WIDTH, for motion estimation. */
  private fun downsample(i420: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
    val step = max(1, width / ANALYSIS_WIDTH)
    val sw = width / step
    val sh = height / step
    val out = ByteArray(sw * sh)
    for (y in 0 until sh) {
      val row = y * step * width
      for (x in 0 until sw) out[y * sw + x] = i420[row + x * step]
    }
    return Triple(out, sw, sh)
  }

  /** How far to slide the crop so the content follows a smoothed path, not the shake. */
  private fun stabilizationOffset(current: ByteArray, sw: Int, sh: Int): Pair<Float, Float> {
    val prev = previous
    previous = current
    if (prev == null || prev.size != current.size) return 0f to 0f

    // Global shift (dx, dy) of the content between frames: minimize the mean absolute
    // difference of prev(x, y) and current(x + dx, y + dy) over a grid.
    var bestDx = 0
    var bestDy = 0
    var best = Long.MAX_VALUE
    for (dy in -SEARCH..SEARCH) {
      for (dx in -SEARCH..SEARCH) {
        var sum = 0L
        var y = SEARCH
        while (y < sh - SEARCH) {
          var x = SEARCH
          val prow = y * sw
          val crow = (y + dy) * sw + dx
          while (x < sw - SEARCH) {
            sum += abs((prev[prow + x].toInt() and 0xFF) - (current[crow + x].toInt() and 0xFF))
            x += 2
          }
          y += 2
        }
        // Prefer no movement on ties so flat scenes don't drift.
        if (sum < best || (sum == best && abs(dx) + abs(dy) < abs(bestDx) + abs(bestDy))) {
          best = sum
          bestDx = dx
          bestDy = dy
        }
      }
    }
    // A shift at the edge of the search is a fast turn, not shake: follow it instead.
    if (abs(bestDx) == SEARCH || abs(bestDy) == SEARCH) {
      driftX = 0f; driftY = 0f; smoothX = 0f; smoothY = 0f
      return 0f to 0f
    }
    driftX += bestDx.toFloat() / sw
    driftY += bestDy.toFloat() / sh
    // The smoothed path trails the real one, so slow pans come through and shake doesn't.
    smoothX += (driftX - smoothX) * 0.12f
    smoothY += (driftY - smoothY) * 0.12f
    val room = (1 - STABILIZED_SCALE) / 2
    val offsetX = (driftX - smoothX).coerceIn(-room, room)
    val offsetY = (driftY - smoothY).coerceIn(-room, room)
    // Never let the correction run away: pull the smoothed path back within reach.
    smoothX = driftX - offsetX
    smoothY = driftY - offsetY
    return offsetX to offsetY
  }

  // MARK: - Framing

  /** Zooms toward faces; with none found for about two seconds, back to the whole view. */
  private fun updateFraming(i420: ByteArray, width: Int, height: Int) {
    val fw = FACE_WIDTH
    val fh = (height.toLong() * fw / width).toInt()
    val pixels = IntArray(fw * fh)
    for (y in 0 until fh) {
      val sy = y * height / fh
      for (x in 0 until fw) {
        val v = i420[sy * width + x * width / fw].toInt() and 0xFF
        pixels[y * fw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
      }
    }
    val bitmap = Bitmap.createBitmap(pixels, fw, fh, Bitmap.Config.RGB_565)
    val faces = arrayOfNulls<FaceDetector.Face>(4)
    val found = runCatching { FaceDetector(fw, fh, faces.size).findFaces(bitmap, faces) }.getOrDefault(0)
    bitmap.recycle()

    if (found > 0) {
      val box = RectF()
      val mid = PointF()
      for (face in faces.take(found).filterNotNull()) {
        face.getMidPoint(mid)
        // A face is roughly 2.5 eye-distances wide; keep shoulders and some headroom.
        val half = face.eyesDistance() * 2.6f
        box.union(
            RectF((mid.x - half) / fw, (mid.y - half) / fh, (mid.x + half) / fw, (mid.y + half * 1.4f) / fh))
      }
      // Normalized units: a square crop keeps the frame's aspect ratio.
      val side = max(box.width(), box.height()).coerceIn(1f / 3f, 1f)
      framingTarget =
          clampToUnit(
              RectF(box.centerX() - side / 2, box.centerY() - side / 2, box.centerX() + side / 2, box.centerY() + side / 2))
      framesWithoutFace = 0
    } else {
      framesWithoutFace += FRAMING_INTERVAL
      if (framesWithoutFace > 48) framingTarget = RectF(0f, 0f, 1f, 1f)
    }
  }

  // MARK: - Enhance

  private fun enhance(i420: ByteArray, width: Int, height: Int) {
    if (frameIndex % ENHANCE_INTERVAL == 1) measureLevels(i420, width, height)
    // Ease toward the measured levels so the picture doesn't jump.
    black += (targetBlack - black) * 0.1f
    white += (targetWhite - white) * 0.1f
    gamma += (targetGamma - gamma) * 0.1f
    val range = max(white - black, 32f)
    for (v in 0..255) {
      val n = ((v - black) / range).coerceIn(0f, 1f)
      lut[v] = (n.pow(gamma) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }
    for (i in 0 until width * height) i420[i] = lut[i420[i].toInt() and 0xFF]
  }

  /** Black and white points at the 1st and 99th percentiles; gamma to bring the mean to mid-grey. */
  private fun measureLevels(i420: ByteArray, width: Int, height: Int) {
    val histogram = IntArray(256)
    var count = 0
    var i = 0
    while (i < width * height) {
      histogram[i420[i].toInt() and 0xFF]++
      count++
      i += 7
    }
    var low = 0
    var seen = 0
    while (low < 255 && seen + histogram[low] < count / 100) seen += histogram[low++]
    var high = 255
    seen = 0
    while (high > 0 && seen + histogram[high] < count / 100) seen += histogram[high--]
    // Don't stretch a scene that is genuinely low-contrast (fog, a white wall) too far.
    targetBlack = min(low.toFloat(), 60f)
    targetWhite = max(high.toFloat(), 190f)
    var sum = 0.0
    for (v in 0..255) sum += histogram[v] * ((v - targetBlack) / (targetWhite - targetBlack)).coerceIn(0f, 1f)
    val mean = (sum / count).coerceIn(0.05, 0.95)
    targetGamma = (ln(0.47) / ln(mean)).toFloat().coerceIn(0.6f, 1.6f)
  }

  // MARK: - Geometry

  private fun scale(rect: RectF, factor: Float): RectF {
    val w = rect.width() * factor
    val h = rect.height() * factor
    return RectF(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2)
  }

  /** Slides the rect back inside 0..1 without changing its size. */
  private fun clampToUnit(rect: RectF): RectF {
    val w = min(rect.width(), 1f)
    val h = min(rect.height(), 1f)
    val left = rect.left.coerceIn(0f, 1f - w)
    val top = rect.top.coerceIn(0f, 1f - h)
    return RectF(left, top, left + w, top + h)
  }

  private fun ease(rect: RectF, target: RectF, amount: Float) =
      RectF(
          rect.left + (target.left - rect.left) * amount,
          rect.top + (target.top - rect.top) * amount,
          rect.right + (target.right - rect.right) * amount,
          rect.bottom + (target.bottom - rect.bottom) * amount,
      )
}
