import Foundation
import Observation
import UIKit

/// Photos taken with the glasses camera, kept as JPEG files in the app's Documents folder
/// (visible in the Files app) and listed newest first.
@Observable
@MainActor
final class PhotoStore {
  struct Photo: Identifiable, Hashable {
    let url: URL
    var id: URL { url }
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
    photos = urls.filter { $0.pathExtension.lowercased() == "jpg" }
      .sorted { $0.lastPathComponent > $1.lastPathComponent }
      .map(Photo.init)
    latestThumbnail = photos.first.flatMap { Self.thumbnail(at: $0.url) }
  }

  static func thumbnail(at url: URL, maxPixel: CGFloat = 240) -> UIImage? {
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
