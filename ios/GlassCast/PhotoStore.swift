import AVFoundation
import Foundation
import Observation
import UIKit

/// Photos taken with the glasses camera (JPEG) and live-view recordings (MP4), kept in the
/// app's Documents folder (visible in the Files app) and listed newest first.
@Observable
@MainActor
final class PhotoStore {
  struct Photo: Identifiable, Hashable {
    let url: URL
    var id: URL { url }
    var isVideo: Bool { url.pathExtension.lowercased() == "mp4" }
  }

  private(set) var photos: [Photo] = []
  /// Thumbnail of the newest photo, for the button that opens the gallery.
  private(set) var latestThumbnail: UIImage?

  @ObservationIgnored private let directory: URL
  @ObservationIgnored private let nameFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyyMMdd-HHmmss-SSS"
    return formatter
  }()

  init() {
    let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    directory = documents.appendingPathComponent("Photos", isDirectory: true)
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    reload()
  }

  /// Saves a JPEG from the glasses and returns its file.
  @discardableResult
  func add(_ jpeg: Data, takenAt date: Date = Date()) throws -> Photo {
    let url = directory.appendingPathComponent("glasscast-\(nameFormatter.string(from: date)).jpg")
    try jpeg.write(to: url, options: .atomic)
    let photo = Photo(url: url)
    photos.insert(photo, at: 0)
    latestThumbnail = Self.thumbnail(for: jpeg)
    return photo
  }

  /// A file URL for a new recording; pass it to `addVideo(at:)` once it is finished.
  func newVideoURL(startedAt date: Date = Date()) -> URL {
    directory.appendingPathComponent("glasscast-\(nameFormatter.string(from: date)).mp4")
  }

  func addVideo(at url: URL) {
    let photo = Photo(url: url)
    photos.insert(photo, at: 0)
    latestThumbnail = Self.thumbnail(at: url)
  }

  func delete(_ photo: Photo) {
    try? FileManager.default.removeItem(at: photo.url)
    photos.removeAll { $0 == photo }
    latestThumbnail = photos.first.flatMap { Self.thumbnail(at: $0.url) }
  }

  private func reload() {
    let urls =
      (try? FileManager.default.contentsOfDirectory(
        at: directory, includingPropertiesForKeys: nil, options: .skipsHiddenFiles)) ?? []
    // Names embed the capture time, so name order is time order.
    photos = urls.filter { ["jpg", "mp4"].contains($0.pathExtension.lowercased()) }
      .sorted { $0.lastPathComponent > $1.lastPathComponent }
      .map(Photo.init)
    latestThumbnail = photos.first.flatMap { Self.thumbnail(at: $0.url) }
  }

  static func thumbnail(at url: URL, maxPixel: CGFloat = 240) -> UIImage? {
    if url.pathExtension.lowercased() == "mp4" {
      let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
      generator.appliesPreferredTrackTransform = true
      generator.maximumSize = CGSize(width: maxPixel, height: maxPixel)
      return (try? generator.copyCGImage(at: .zero, actualTime: nil)).map(UIImage.init(cgImage:))
    }
    guard let data = try? Data(contentsOf: url) else { return nil }
    return thumbnail(for: data, maxPixel: maxPixel)
  }

  static func thumbnail(for data: Data, maxPixel: CGFloat = 240) -> UIImage? {
    guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
    let scale = min(1, maxPixel / max(image.size.width, image.size.height))
    return image.preparingThumbnail(
      of: CGSize(width: image.size.width * scale, height: image.size.height * scale))
  }
}
