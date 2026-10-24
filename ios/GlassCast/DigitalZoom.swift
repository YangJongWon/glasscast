import UIKit
import os

/// Center-crop digital zoom for live-view frames. The glasses camera is very wide, so
/// cropping brings the view closer to what the wearer actually looks at. The same crop
/// goes to the phone preview and to desktop viewers.
///
/// Thread-safe: the zoom is set on the main thread and read on the SDK's frame thread.
final class DigitalZoom: Sendable {
  static let range: ClosedRange<CGFloat> = 1...4

  private let factor = OSAllocatedUnfairLock<CGFloat>(initialState: 1)

  var value: CGFloat {
    get { factor.withLock { $0 } }
    set { factor.withLock { $0 = min(max(newValue, Self.range.lowerBound), Self.range.upperBound) } }
  }

  /// Returns the center `1/zoom` of the frame, or the frame itself at 1x.
  func apply(to image: UIImage) -> UIImage {
    let zoom = value
    guard zoom > 1.01 else { return image }
    let size = image.size
    let crop = CGSize(width: size.width / zoom, height: size.height / zoom)
    let origin = CGPoint(x: (size.width - crop.width) / 2, y: (size.height - crop.height) / 2)

    // Cropping the bitmap directly is cheap; the viewer scales it back up.
    if let cgImage = image.cgImage {
      let scale = CGFloat(cgImage.width) / size.width
      let rect = CGRect(
        x: origin.x * scale, y: origin.y * scale, width: crop.width * scale, height: crop.height * scale
      ).integral
      if let cropped = cgImage.cropping(to: rect) {
        return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
      }
    }
    let format = UIGraphicsImageRendererFormat()
    format.scale = image.scale
    return UIGraphicsImageRenderer(size: crop, format: format).image { _ in
      image.draw(at: CGPoint(x: -origin.x, y: -origin.y))
    }
  }
}
