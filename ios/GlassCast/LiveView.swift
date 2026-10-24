import CoreImage.CIFilterBuiltins
import SwiftUI

struct LiveView: View {
  @Bindable var manager: WearablesManager
  @State private var showShareSheet = false
  @State private var showGallery = false
  @State private var lookUp: FrozenFrame?
  @State private var pinchStartZoom: CGFloat?

  var body: some View {
    ZStack {
      Color.black.ignoresSafeArea()

      if let frame = manager.frame {
        Image(uiImage: frame)
          .resizable()
          .scaledToFit()
          .ignoresSafeArea()
          .gesture(
            MagnifyGesture()
              .onChanged { value in
                let start = pinchStartZoom ?? manager.zoom
                pinchStartZoom = start
                manager.zoom = min(max(start * value.magnification, DigitalZoom.range.lowerBound), DigitalZoom.range.upperBound)
              }
              .onEnded { _ in pinchStartZoom = nil })
      } else {
        VStack(spacing: 12) {
          if manager.wantsStream {
            ProgressView().tint(.white)
          } else {
            Image(systemName: "eyeglasses")
              .font(.system(size: 48, weight: .light))
          }
          Text(manager.statusText)
        }
        .foregroundStyle(.white.opacity(0.8))
      }

      VStack {
        StatusBar(manager: manager)
        Spacer()
        Controls(
          manager: manager, showShareSheet: $showShareSheet, showGallery: $showGallery,
          lookUp: $lookUp)
      }
      .padding(16)
    }
    .sheet(isPresented: $showShareSheet) {
      ShareSheet(manager: manager)
        .presentationDetents([.medium])
    }
    .sheet(isPresented: $showGallery) {
      GalleryView(store: manager.photos)
    }
    .fullScreenCover(item: $lookUp) { frame in
      LookUpView(image: frame.image)
    }
    .confirmationDialog(
      "Allow camera access",
      isPresented: $manager.showCameraPermissionPrompt,
      titleVisibility: .visible
    ) {
      Button("Open Meta AI") { manager.confirmCameraPermission() }
      Button("Cancel", role: .cancel) { manager.stopStreaming() }
    } message: {
      Text("GlassCast needs permission to use the glasses camera. You'll approve it in the Meta AI app.")
    }
  }
}

private struct StatusBar: View {
  var manager: WearablesManager

  var body: some View {
    HStack(spacing: 8) {
      Label {
        Text(manager.statusText)
      } icon: {
        Circle()
          .fill(manager.isStreaming ? Color.red : Color.gray)
          .frame(width: 8, height: 8)
      }
      if manager.isStreaming {
        Text("\(Int(manager.fps)) fps")
          .monospacedDigit()
      }
      Spacer()
      if let since = manager.recordingSince {
        RecordingTimer(since: since)
      }
      if manager.isCapturingAudio {
        Image(systemName: "mic.fill")
          .foregroundStyle(.red)
          .accessibilityLabel("Glasses microphone on")
      }
      if manager.viewerCount > 0 {
        Label("\(manager.viewerCount)", systemImage: "desktopcomputer")
      }
      Menu {
        #if EXPERIMENTAL_AUDIO
        Picker(
          "Sound source",
          selection: Binding(get: { manager.audioSource }, set: { manager.setAudioSource($0) })
        ) {
          ForEach(WearablesManager.AudioSource.allCases) { source in
            Text(source.label).tag(source)
          }
        }
        #endif
        #if EXPERIMENTAL_PHOTO
        Picker(
          "Photo quality",
          selection: Binding(get: { manager.photoSource }, set: { manager.photoSource = $0 })
        ) {
          ForEach(WearablesManager.PhotoSource.allCases) { source in
            Text(source.label).tag(source)
          }
        }
        #endif
        Button("Disconnect glasses", role: .destructive) { manager.disconnectGlasses() }
      } label: {
        Image(systemName: "ellipsis.circle")
      }
    }
    .font(.subheadline)
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    .background(.ultraThinMaterial, in: Capsule())
  }
}

private struct Controls: View {
  @Bindable var manager: WearablesManager
  @Binding var showShareSheet: Bool
  @Binding var showGallery: Bool
  @Binding var lookUp: FrozenFrame?

  var body: some View {
    VStack(spacing: 12) {
      ZoomChips(manager: manager)
      ShutterRow(manager: manager, showGallery: $showGallery, lookUp: $lookUp)

      Picker("Quality", selection: $manager.quality) {
        ForEach(WearablesManager.Quality.allCases) { quality in
          Text(quality.label).tag(quality)
        }
      }
      .pickerStyle(.segmented)

      HStack(spacing: 12) {
        Button {
          if manager.wantsStream {
            manager.stopStreaming()
          } else {
            manager.startStreaming()
          }
        } label: {
          Label(
            manager.wantsStream ? "Stop" : "Start live view",
            systemImage: manager.wantsStream ? "stop.fill" : "play.fill"
          )
          .lineLimit(1)
          .minimumScaleFactor(0.8)
          .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(manager.wantsStream ? .red : .accentColor)

        Button {
          manager.setAudioEnabled(!manager.audioEnabled)
        } label: {
          Image(systemName: manager.audioEnabled ? "mic.fill" : "mic.slash")
            .frame(minWidth: 24)
        }
        .buttonStyle(.bordered)
        .tint(manager.audioEnabled ? .red : nil)
        .accessibilityLabel(manager.audioEnabled ? "Turn off glasses microphone" : "Turn on glasses microphone")

        Button {
          showShareSheet = true
        } label: {
          Label("Desktop", systemImage: "display")
        }
        .buttonStyle(.bordered)
      }
      .controlSize(.large)
    }
    .padding(12)
    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
  }
}

/// Starts and stops recording the live view to an MP4 in the gallery.
private struct RecordButton: View {
  var manager: WearablesManager

  var body: some View {
    Button {
      if manager.isRecording { manager.stopRecording() } else { manager.startRecording() }
    } label: {
      ZStack {
        RoundedRectangle(cornerRadius: 10).fill(.white.opacity(0.1))
        if manager.isRecording {
          RoundedRectangle(cornerRadius: 4).fill(.red).frame(width: 18, height: 18)
        } else {
          Circle().fill(.red).frame(width: 22, height: 22)
        }
      }
      .frame(width: 48, height: 48)
    }
    .disabled(!manager.isStreaming && !manager.isRecording)
    .opacity(manager.isStreaming || manager.isRecording ? 1 : 0.4)
    .accessibilityLabel(manager.isRecording ? "Stop recording" : "Record video")
  }
}

/// Red "REC 0:12" counter shown while recording.
private struct RecordingTimer: View {
  let since: Date

  var body: some View {
    TimelineView(.periodic(from: since, by: 1)) { context in
      let seconds = max(0, Int(context.date.timeIntervalSince(since)))
      Label(String(format: "%d:%02d", seconds / 60, seconds % 60), systemImage: "record.circle")
        .foregroundStyle(.red)
        .monospacedDigit()
    }
    .accessibilityLabel("Recording")
  }
}

/// Camera-style zoom presets. Pinch the preview for anything in between.
private struct ZoomChips: View {
  var manager: WearablesManager
  private let presets: [CGFloat] = [1, 1.5, 2, 3]

  var body: some View {
    HStack(spacing: 8) {
      ForEach(presets, id: \.self) { preset in
        let selected = abs(manager.zoom - preset) < 0.05
        Button {
          withAnimation(.snappy) { manager.zoom = preset }
        } label: {
          Text(selected ? Self.label(manager.zoom) : Self.label(preset))
            .font(.footnote.weight(.semibold).monospacedDigit())
            .foregroundStyle(selected ? .yellow : .white)
            .frame(width: 44, height: 32)
            .background(.black.opacity(selected ? 0.6 : 0.3), in: Capsule())
        }
        .accessibilityLabel("Zoom \(Self.label(preset))")
      }
      // Shows a pinched value that isn't one of the presets.
      if !presets.contains(where: { abs(manager.zoom - $0) < 0.05 }) {
        Text(Self.label(manager.zoom))
          .font(.footnote.weight(.semibold).monospacedDigit())
          .foregroundStyle(.yellow)
          .frame(width: 44, height: 32)
          .background(.black.opacity(0.6), in: Capsule())
      }
    }
  }

  static func label(_ zoom: CGFloat) -> String {
    zoom.truncatingRemainder(dividingBy: 1) < 0.05 ? "\(Int(zoom))×" : String(format: "%.1f×", zoom)
  }
}

/// Latest photo on the left, the glasses shutter in the middle.
private struct ShutterRow: View {
  var manager: WearablesManager
  @Binding var showGallery: Bool
  @Binding var lookUp: FrozenFrame?

  var body: some View {
    HStack {
      Button {
        showGallery = true
      } label: {
        Group {
          if let thumbnail = manager.photos.latestThumbnail {
            Image(uiImage: thumbnail).resizable().scaledToFill()
          } else {
            Image(systemName: "photo.on.rectangle").foregroundStyle(.secondary)
          }
        }
        .frame(width: 48, height: 48)
        .background(.white.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 10))
      }
      .accessibilityLabel("Photos")
      .frame(maxWidth: .infinity, alignment: .leading)

      Button {
        manager.takePhoto()
      } label: {
        ZStack {
          Circle().strokeBorder(.white, lineWidth: 4).frame(width: 68, height: 68)
          Circle().fill(.white).frame(width: 54, height: 54)
          if manager.isTakingPhoto {
            if let progress = manager.photoProgress {
              ProgressView(value: progress).progressViewStyle(.circular).tint(.black)
            } else {
              ProgressView().tint(.black)
            }
          }
        }
      }
      .disabled(!manager.isStreaming || manager.isTakingPhoto)
      .opacity(manager.isStreaming ? 1 : 0.4)
      .accessibilityLabel("Take a photo with the glasses")

      HStack(spacing: 10) {
        RecordButton(manager: manager)
        if LiveTextImage.isSupported {
          Button {
            lookUp = manager.frame.map(FrozenFrame.init)
          } label: {
            Image(systemName: "text.viewfinder")
              .font(.title2)
              .frame(width: 48, height: 48)
              .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
          }
          .disabled(manager.frame == nil)
          .accessibilityLabel("Look up what the glasses see")
        }
      }
      .frame(maxWidth: .infinity, alignment: .trailing)
    }
  }
}

/// A live-view frame frozen for Look Up.
struct FrozenFrame: Identifiable {
  let id = UUID()
  let image: UIImage
}

/// How to open the stream on a computer: URL, QR code and OBS/VLC address.
private struct ShareSheet: View {
  var manager: WearablesManager

  var body: some View {
    VStack(spacing: 16) {
      Text("Watch on a computer")
        .font(.headline)
      if let url = NetworkInfo.viewerURL() {
        if let qr = Self.qrCode(for: url) {
          Image(uiImage: qr)
            .interpolation(.none)
            .resizable()
            .frame(width: 160, height: 160)
        }
        Text(url)
          .font(.title3.monospaced())
          .textSelection(.enabled)
        Text("Open this address in a browser on the same Wi-Fi. For OBS or VLC use \(url)/stream, and \(url)/audio for sound.")
          .font(.footnote)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
        Text(manager.viewerCount == 1 ? "1 viewer connected" : "\(manager.viewerCount) viewers connected")
          .font(.footnote)
      } else {
        Text("Connect this phone to Wi-Fi to share the live view with a computer.")
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
      }
    }
    .padding(24)
  }

  private static func qrCode(for string: String) -> UIImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(string.utf8)
    guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
      let cgImage = CIContext().createCGImage(output, from: output.extent)
    else { return nil }
    return UIImage(cgImage: cgImage)
  }
}
