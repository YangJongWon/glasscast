import AVKit
import SwiftUI

/// Photos taken with the glasses, newest first. Tap one to view, share or delete it.
struct GalleryView: View {
  var store: PhotoStore
  @Environment(\.dismiss) private var dismiss

  private let columns = [GridItem(.adaptive(minimum: 100), spacing: 2)]

  var body: some View {
    NavigationStack {
      Group {
        if store.photos.isEmpty {
          ContentUnavailableView(
            "No photos yet",
            systemImage: "camera",
            description: Text(
              "Start live view, then tap the shutter to take a photo with your glasses or the red button to record."))
        } else {
          ScrollView {
            LazyVGrid(columns: columns, spacing: 2) {
              ForEach(store.photos) { photo in
                NavigationLink(value: photo) {
                  Thumbnail(url: photo.url)
                    .overlay(alignment: .bottomLeading) {
                      if photo.isVideo {
                        Image(systemName: "video.fill")
                          .font(.caption)
                          .foregroundStyle(.white)
                          .shadow(radius: 2)
                          .padding(6)
                      }
                    }
                }
              }
            }
          }
        }
      }
      .navigationTitle("Photos")
      .navigationBarTitleDisplayMode(.inline)
      .navigationDestination(for: PhotoStore.Photo.self) { photo in
        PhotoDetail(photo: photo, store: store)
      }
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { dismiss() }
        }
      }
    }
  }
}

private struct Thumbnail: View {
  let url: URL
  @State private var image: UIImage?

  var body: some View {
    Color.gray.opacity(0.2)
      .aspectRatio(1, contentMode: .fit)
      .overlay {
        if let image {
          Image(uiImage: image).resizable().scaledToFill()
        }
      }
      .clipped()
      .task(id: url) {
        image = await Task.detached { PhotoStore.thumbnail(at: url, maxPixel: 300) }.value
      }
  }
}

private struct PhotoDetail: View {
  let photo: PhotoStore.Photo
  var store: PhotoStore
  @Environment(\.dismiss) private var dismiss
  @State private var image: UIImage?

  var body: some View {
    ZStack {
      Color.black.ignoresSafeArea()
      if photo.isVideo {
        RecordingPlayer(url: photo.url)
      } else if let image {
        // Photos are full resolution, so Live Text works best here.
        if LiveTextImage.isSupported {
          LiveTextImage(image: image)
        } else {
          Image(uiImage: image).resizable().scaledToFit()
        }
      } else {
        ProgressView().tint(.white)
      }
    }
    .toolbar {
      ToolbarItemGroup(placement: .bottomBar) {
        // The share sheet includes "Save Image" for the Photos library.
        ShareLink(item: photo.url)
        Spacer()
        Button(role: .destructive) {
          store.delete(photo)
          dismiss()
        } label: {
          Image(systemName: "trash")
        }
        .accessibilityLabel("Delete photo")
      }
    }
    .task(id: photo.url) {
      guard !photo.isVideo else { return }
      image = await Task.detached { UIImage(contentsOfFile: photo.url.path) }.value
    }
  }
}

private struct RecordingPlayer: View {
  let url: URL
  @State private var player: AVPlayer?

  var body: some View {
    VideoPlayer(player: player)
      .onAppear {
        player = AVPlayer(url: url)
        player?.play()
      }
      .onDisappear { player?.pause() }
  }
}
