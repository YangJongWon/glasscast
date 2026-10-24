import CoreImage.CIFilterBuiltins
import SwiftUI

struct LiveView: View {
  @Bindable var manager: WearablesManager
  @State private var showShareSheet = false
  @State private var showGallery = false
  @State private var lookUp: FrozenFrame?
  @State private var pinchStartZoom: CGFloat?
  @State private var mode: CaptureMode = .photo
  /// Collapsed: just the live view, with a small handle to bring the controls back.
  @State private var controlsHidden = false

  var body: some View {
    ZStack {
      Color.black.ignoresSafeArea()

      if let frame = manager.frame {
        Image(uiImage: frame)
          .resizable()
          .scaledToFit()
          .ignoresSafeArea()
          .onTapGesture { toggleControls() }
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
        if !controlsHidden {
          StatusBar(manager: manager)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
        Spacer()
        if controlsHidden {
          CollapsedBar(manager: manager, expand: toggleControls)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        } else {
          VStack(spacing: 4) {
            // Floats over the preview, above the panel, as in the Camera app.
            ZoomChips(manager: manager)
            Controls(
              manager: manager, mode: $mode, showShareSheet: $showShareSheet, showGallery: $showGallery,
              lookUp: $lookUp, collapse: toggleControls)
          }
          .transition(.move(edge: .bottom).combined(with: .opacity))
        }
      }
      .padding(16)
    }
    .statusBarHidden(controlsHidden)
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

extension LiveView {
  fileprivate func toggleControls() {
    withAnimation(.snappy) { controlsHidden.toggle() }
  }
}

/// What's left when the controls are folded away: a handle to unfold them, and the
/// recording timer so a running recording is never invisible.
private struct CollapsedBar: View {
  var manager: WearablesManager
  let expand: () -> Void

  var body: some View {
    HStack(spacing: 10) {
      if let since = manager.recordingSince {
        RecordingTimer(since: since)
          .font(.subheadline)
      }
      Button(action: expand) {
        Image(systemName: "chevron.up")
          .font(.headline)
          .frame(width: 56, height: 32)
      }
      .accessibilityLabel("Show controls")
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 6)
    .background(.ultraThinMaterial, in: Capsule())
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

/// What the shutter does.
enum CaptureMode: String, CaseIterable, Identifiable {
  case photo, video

  var id: String { rawValue }
  var label: String { self == .photo ? "PHOTO" : "VIDEO" }
}

private struct Controls: View {
  @Bindable var manager: WearablesManager
  @Binding var mode: CaptureMode
  @Binding var showShareSheet: Bool
  @Binding var showGallery: Bool
  @Binding var lookUp: FrozenFrame?
  let collapse: () -> Void

  var body: some View {
    VStack(spacing: 12) {
      Button(action: collapse) {
        Image(systemName: "chevron.compact.down")
          .font(.title2)
          .foregroundStyle(.secondary)
          .frame(maxWidth: .infinity, minHeight: 16)
      }
      .accessibilityLabel("Hide controls")
      .padding(.bottom, -6)
      ModeSwitcher(mode: $mode, locked: manager.isRecording)
      ShutterRow(manager: manager, mode: $mode, showGallery: $showGallery, lookUp: $lookUp)

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
    .simultaneousGesture(
      DragGesture(minimumDistance: 30).onEnded { value in
        if value.translation.height > 60, value.translation.height > abs(value.translation.width) {
          collapse()
        }
      })
  }
}

/// PHOTO / VIDEO, switched by tapping a label or swiping left and right. Locked while
/// recording so a stray swipe can't interrupt it.
private struct ModeSwitcher: View {
  @Binding var mode: CaptureMode
  let locked: Bool

  var body: some View {
    HStack(spacing: 24) {
      ForEach(CaptureMode.allCases) { item in
        Button {
          select(item)
        } label: {
          Text(item.label)
            .font(.footnote.weight(.semibold))
            .tracking(1.5)
            .foregroundStyle(mode == item ? .yellow : .white.opacity(0.7))
        }
        .accessibilityAddTraits(mode == item ? .isSelected : [])
      }
    }
    .frame(maxWidth: .infinity, minHeight: 28)
    .contentShape(Rectangle())
    .gesture(
      DragGesture(minimumDistance: 20).onEnded { value in
        // Swipe left for the mode on the right, right for the one on the left.
        guard abs(value.translation.width) > abs(value.translation.height) else { return }
        select(value.translation.width < 0 ? .video : .photo)
      })
    .disabled(locked)
    .sensoryFeedback(.selection, trigger: mode)
  }

  private func select(_ item: CaptureMode) {
    withAnimation(.snappy) { mode = item }
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

/// Latest photo on the left, the shutter in the middle, Look Up on the right.
private struct ShutterRow: View {
  var manager: WearablesManager
  @Binding var mode: CaptureMode
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

      Shutter(manager: manager, mode: mode)

      Group {
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

/// One shutter for both modes: a white disc takes a photo with the glasses; a red disc
/// starts recording and turns into a red square that stops it.
private struct Shutter: View {
  var manager: WearablesManager
  let mode: CaptureMode

  var body: some View {
    Button(action: press) {
      ZStack {
        Circle().strokeBorder(.white, lineWidth: 4).frame(width: 68, height: 68)
        switch mode {
        case .photo:
          Circle().fill(.white).frame(width: 54, height: 54)
          if manager.isTakingPhoto {
            if let progress = manager.photoProgress {
              ProgressView(value: progress).progressViewStyle(.circular).tint(.black)
            } else {
              ProgressView().tint(.black)
            }
          }
        case .video:
          RoundedRectangle(cornerRadius: manager.isRecording ? 6 : 27)
            .fill(.red)
            .frame(width: manager.isRecording ? 28 : 54, height: manager.isRecording ? 28 : 54)
        }
      }
      .animation(.snappy, value: manager.isRecording)
    }
    .disabled(!enabled)
    .opacity(enabled ? 1 : 0.4)
    .accessibilityLabel(accessibilityLabel)
  }

  private var enabled: Bool {
    switch mode {
    case .photo: return manager.isStreaming && !manager.isTakingPhoto
    case .video: return manager.isStreaming || manager.isRecording
    }
  }

  private var accessibilityLabel: String {
    switch mode {
    case .photo: return "Take a photo with the glasses"
    case .video: return manager.isRecording ? "Stop recording" : "Record video"
    }
  }

  private func press() {
    switch mode {
    case .photo: manager.takePhoto()
    case .video: manager.isRecording ? manager.stopRecording() : manager.startRecording()
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
