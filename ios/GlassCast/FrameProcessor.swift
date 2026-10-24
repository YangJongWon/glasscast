import CoreImage
import UIKit
import Vision
import os

/// Turns each raw live-view frame into what the preview, desktop viewers and recordings
/// show: digital zoom, auto framing, stabilization and auto enhance, all on device.
///
/// Every step is a crop of the frame, worked out in normalized coordinates (0...1, origin
/// bottom-left as in Core Image), followed by optional color correction:
///
/// 1. **Framing**: manual zoom crops the center; auto framing instead eases the crop
///    toward faces, people or text found by Vision.
/// 2. **Stabilization**: the crop shrinks a little and slides against the frame-to-frame
///    shake measured by Vision image registration, so the content moves smoothly.
/// 3. **Enhance**: Core Image's auto-adjustment filters, re-measured about once a second.
///
/// Thread-safe: settings change on the main thread; `process` runs on the SDK frame thread.
final class FrameProcessor: @unchecked Sendable {
  struct Settings: Equatable {
    var zoom: CGFloat = 1
    var enhance = false
    var stabilize = false
    var autoFrame = false

    var needsCoreImage: Bool { enhance || stabilize || autoFrame }
  }

  static let zoomRange: ClosedRange<CGFloat> = 1...4
  /// Share of the frame kept when stabilizing; the rest is room for the crop to move.
  private static let stabilizedScale: CGFloat = 0.84
  /// Analysis runs on a small copy of the frame: plenty for motion and detection.
  private static let analysisWidth: CGFloat = 192
  private static let framingInterval = 6  // frames between detections
  private static let enhanceInterval = 24  // frames between auto-adjust measurements

  private let settingsLock = OSAllocatedUnfairLock(initialState: Settings())
  var settings: Settings {
    get { settingsLock.withLock { $0 } }
    set { settingsLock.withLock { $0 = newValue } }
  }

  private let lock = NSLock()
  private let context = CIContext(options: [.cacheIntermediates: false])
  private var frameIndex = 0
  private var lastSettings = Settings()

  // Stabilization: where the content has drifted (normalized), and a smoothed copy of it.
  private var previousSmall: CGImage?
  private var drift = CGPoint.zero
  private var smoothDrift = CGPoint.zero

  // Framing: the crop being shown, and the one it is easing toward.
  private var framing = CGRect(x: 0, y: 0, width: 1, height: 1)
  private var framingTarget = CGRect(x: 0, y: 0, width: 1, height: 1)
  private var framesWithoutSubject = 0

  private var enhanceFilters: [CIFilter] = []

  func process(_ image: UIImage) -> UIImage {
    let settings = self.settings
    lock.lock()
    defer { lock.unlock() }
    if settings != lastSettings { reset(keepingFraming: settings.autoFrame && lastSettings.autoFrame) }
    lastSettings = settings
    frameIndex += 1

    // Manual zoom alone is a cheap bitmap crop, unless the frame isn't bitmap-backed.
    if !settings.needsCoreImage, image.cgImage != nil || settings.zoom <= 1.01 {
      return Self.centerCrop(image, zoom: settings.zoom)
    }
    guard let input = CIImage(image: image) else { return image }
    let extent = input.extent
    let small = input.transformed(
      by: CGAffineTransform(scaleX: Self.analysisWidth / extent.width, y: Self.analysisWidth / extent.width))

    var crop: CGRect
    if settings.autoFrame {
      if frameIndex % Self.framingInterval == 1 { updateFramingTarget(small) }
      framing = Self.ease(framing, toward: framingTarget, by: 0.08)
      crop = framing
    } else {
      crop = Self.centered(scale: 1 / settings.zoom)
    }

    if settings.stabilize {
      let offset = stabilizationOffset(small)
      crop = Self.scale(crop, by: Self.stabilizedScale)
      crop = crop.offsetBy(dx: offset.x, dy: offset.y)
    }
    crop = Self.clampToUnit(crop)

    let rect = CGRect(
      x: extent.minX + crop.minX * extent.width, y: extent.minY + crop.minY * extent.height,
      width: crop.width * extent.width, height: crop.height * extent.height
    ).integral
    var output = input.cropped(to: rect)
    if settings.enhance {
      if frameIndex % Self.enhanceInterval == 1 || enhanceFilters.isEmpty {
        enhanceFilters = small.autoAdjustmentFilters(options: [.redEye: false])
      }
      output = enhanceFilters.reduce(output) { image, filter in
        filter.setValue(image, forKey: kCIInputImageKey)
        return filter.outputImage ?? image
      }.cropped(to: rect)
    }
    output = output.transformed(by: CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
    guard let cgImage = context.createCGImage(output, from: CGRect(origin: .zero, size: rect.size)) else {
      return image
    }
    return UIImage(cgImage: cgImage)
  }

  private func reset(keepingFraming: Bool) {
    previousSmall = nil
    drift = .zero
    smoothDrift = .zero
    enhanceFilters = []
    if !keepingFraming {
      framing = CGRect(x: 0, y: 0, width: 1, height: 1)
      framingTarget = framing
      framesWithoutSubject = 0
    }
  }

  // MARK: - Stabilization

  /// How far to slide the crop so the content follows a smoothed path, not the shake.
  private func stabilizationOffset(_ small: CIImage) -> CGPoint {
    guard let current = context.createCGImage(small, from: small.extent) else { return .zero }
    defer { previousSmall = current }
    guard let previous = previousSmall else { return .zero }

    let request = VNTranslationalImageRegistrationRequest(targetedCGImage: current)
    guard (try? VNImageRequestHandler(cgImage: previous).perform([request])) != nil,
      let alignment = (request.results?.first as? VNImageTranslationAlignmentObservation)?.alignmentTransform
    else { return drift - smoothDrift }

    // Vision reports the shift that maps the new frame back onto the previous one, so the
    // content itself moved the opposite way.
    let moved = CGPoint(x: -alignment.tx / small.extent.width, y: -alignment.ty / small.extent.height)
    // A jump this big is a cut or a fast turn, not shake: follow it instead.
    if abs(moved.x) > 0.2 || abs(moved.y) > 0.2 {
      drift = .zero
      smoothDrift = .zero
      return .zero
    }
    drift = drift + moved
    // The smoothed path trails the real one, so slow pans come through and shake doesn't.
    smoothDrift = smoothDrift + (drift - smoothDrift) * 0.12
    let room = (1 - Self.stabilizedScale) / 2
    var offset = drift - smoothDrift
    offset.x = min(max(offset.x, -room), room)
    offset.y = min(max(offset.y, -room), room)
    // Never let the correction run away: pull the smoothed path back within reach.
    smoothDrift = drift - offset
    return offset
  }

  // MARK: - Framing

  /// Faces first, then people, then text; with nothing found for a while, back to wide.
  private func updateFramingTarget(_ small: CIImage) {
    let faces = VNDetectFaceRectanglesRequest()
    let people = VNDetectHumanRectanglesRequest()
    people.upperBodyOnly = true
    let text = VNDetectTextRectanglesRequest()
    guard (try? VNImageRequestHandler(ciImage: small).perform([faces, people, text])) != nil else { return }

    if let boxes = faces.results?.map(\.boundingBox), !boxes.isEmpty {
      // Leave room around faces: shoulders below, some space above.
      framingTarget = Self.frame(Self.union(boxes), padding: 2.2)
      framesWithoutSubject = 0
    } else if let boxes = people.results?.map(\.boundingBox), !boxes.isEmpty {
      framingTarget = Self.frame(Self.union(boxes), padding: 1.3)
      framesWithoutSubject = 0
    } else if let boxes = text.results?.map(\.boundingBox), !boxes.isEmpty {
      framingTarget = Self.frame(Self.union(boxes), padding: 1.4)
      framesWithoutSubject = 0
    } else {
      framesWithoutSubject += Self.framingInterval
      // About two seconds without a subject: ease back out to the whole view.
      if framesWithoutSubject > 48 { framingTarget = CGRect(x: 0, y: 0, width: 1, height: 1) }
    }
  }

  /// A crop around `box` with the frame's aspect ratio, at most 3x in and inside the frame.
  private static func frame(_ box: CGRect, padding: CGFloat) -> CGRect {
    // Normalized units: a crop of w x h keeps the frame's aspect when w == h.
    var side = max(box.width, box.height) * padding
    side = min(max(side, 1 / 3), 1)
    let center = CGPoint(x: box.midX, y: box.midY)
    return clampToUnit(CGRect(x: center.x - side / 2, y: center.y - side / 2, width: side, height: side))
  }

  // MARK: - Geometry

  private static func centered(scale: CGFloat) -> CGRect {
    CGRect(x: (1 - scale) / 2, y: (1 - scale) / 2, width: scale, height: scale)
  }

  private static func scale(_ rect: CGRect, by factor: CGFloat) -> CGRect {
    let width = rect.width * factor
    let height = rect.height * factor
    return CGRect(x: rect.midX - width / 2, y: rect.midY - height / 2, width: width, height: height)
  }

  /// Slides the rect back inside 0...1 without changing its size.
  private static func clampToUnit(_ rect: CGRect) -> CGRect {
    let width = min(rect.width, 1)
    let height = min(rect.height, 1)
    let x = min(max(rect.minX, 0), 1 - width)
    let y = min(max(rect.minY, 0), 1 - height)
    return CGRect(x: x, y: y, width: width, height: height)
  }

  private static func union(_ boxes: [CGRect]) -> CGRect {
    boxes.dropFirst().reduce(boxes[0]) { $0.union($1) }
  }

  private static func ease(_ rect: CGRect, toward target: CGRect, by amount: CGFloat) -> CGRect {
    CGRect(
      x: rect.minX + (target.minX - rect.minX) * amount, y: rect.minY + (target.minY - rect.minY) * amount,
      width: rect.width + (target.width - rect.width) * amount,
      height: rect.height + (target.height - rect.height) * amount)
  }

  /// Manual zoom alone: crop the bitmap directly, which is cheap.
  private static func centerCrop(_ image: UIImage, zoom: CGFloat) -> UIImage {
    guard zoom > 1.01, let cgImage = image.cgImage else { return image }
    let width = CGFloat(cgImage.width) / zoom
    let height = CGFloat(cgImage.height) / zoom
    let rect = CGRect(
      x: (CGFloat(cgImage.width) - width) / 2, y: (CGFloat(cgImage.height) - height) / 2, width: width, height: height
    ).integral
    guard let cropped = cgImage.cropping(to: rect) else { return image }
    return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
  }
}

private func + (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x + b.x, y: a.y + b.y) }
private func - (a: CGPoint, b: CGPoint) -> CGPoint { CGPoint(x: a.x - b.x, y: a.y - b.y) }
private func * (a: CGPoint, k: CGFloat) -> CGPoint { CGPoint(x: a.x * k, y: a.y * k) }
