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
  /// Landscape only: fill the screen (cropping top and bottom) instead of fitting the
  /// portrait video between black bars. Affects this screen only, not viewers or files.
  @AppStorage("landscapeFill") private var landscapeFill = false
  @State private var toast: String?
  @State private var toastTask: Task<Void, Never>?
  @Environment(\.verticalSizeClass) private var verticalSizeClass

  private var isLandscape: Bool { verticalSizeClass == .compact }

  var body: some View {
    ZStack {
      Color.black.ignoresSafeArea()

      if let frame = manager.frame {
        Image(uiImage: frame)
          .resizable()
          .aspectRatio(contentMode: isLandscape && landscapeFill ? .fill : .fit)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .clipped()
          .ignoresSafeArea()
        // Covers the whole screen, black bars included, so pinch and tap work anywhere.
        Color.clear
          .contentShape(Rectangle())
          .ignoresSafeArea()
          .onTapGesture(count: 2) { if isLandscape { toggleFill() } }
          .onTapGesture { toggleControls() }
          .gesture(
            MagnifyGesture()
              .onChanged { value in
                // Pinching takes over from auto framing.
                if manager.autoFrame { manager.autoFrame = false }
                let start = pinchStartZoom ?? manager.zoom
                pinchStartZoom = start
                manager.zoom = min(max(start * value.magnification, FrameProcessor.zoomRange.lowerBound), FrameProcessor.zoomRange.upperBound)
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
          if let hint = manager.statusHint {
            Text(hint)
              .font(.footnote)
              .multilineTextAlignment(.center)
              .foregroundStyle(.white.opacity(0.6))
              .padding(.horizontal, 40)
          }
          if manager.streamState == .paused {
            Button {
              manager.retryConnection()
            } label: {
              Label("Try again", systemImage: "arrow.clockwise")
            }
            .buttonStyle(.bordered)
            .tint(.white)
            .padding(.top, 4)
          }
        }
        .foregroundStyle(.white.opacity(0.8))
      }

      if isLandscape {
        landscapeOverlay
      } else {
        portraitOverlay
      }

      if let toast {
        Text(toast)
          .font(.subheadline.weight(.semibold))
          .padding(.horizontal, 14)
          .padding(.vertical, 8)
          .background(.black.opacity(0.7), in: Capsule())
          .foregroundStyle(.white)
          .transition(.opacity)
      }
    }
    .onChange(of: isLandscape) { _, landscape in
      // The full panel doesn't fit in landscape: start folded, unfold to the side panel.
      withAnimation(.snappy) { controlsHidden = landscape }
    }
    .statusBarHidden(controlsHidden || isLandscape)
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

  private var portraitOverlay: some View {
      VStack {
        if !controlsHidden {
          StatusBar(manager: manager)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
        // Shown even with the controls folded away: the user needs to know.
        if let issue = manager.connectionIssue {
          ConnectionBanner(
            issue: issue, retry: manager.retryConnection, dismiss: manager.dismissConnectionIssue
          )
          .transition(.move(edge: .top).combined(with: .opacity))
        }
        Spacer()
        if controlsHidden {
          CollapsedBar(manager: manager, expand: toggleControls)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        } else {
          VStack(spacing: 8) {
            // Float over the preview, above the panel, as in the Camera app.
            AutoToggles(manager: manager)
              .frame(maxWidth: .infinity, alignment: .trailing)
            ZoomChips(manager: manager)
            Controls(
              manager: manager, mode: $mode, showShareSheet: $showShareSheet, showGallery: $showGallery,
              lookUp: $lookUp, collapse: toggleControls)
          }
          .transition(.move(edge: .bottom).combined(with: .opacity))
        }
      }
      .padding(16)
      .animation(.snappy, value: manager.connectionIssue)
  }

  /// Landscape: status and banners on the left, a slim capture panel on the right. Quality,
  /// sound, desktop sharing and the auto toggles stay in portrait.
  private var landscapeOverlay: some View {
    HStack(alignment: .top, spacing: 12) {
      VStack(alignment: .leading, spacing: 8) {
        if !controlsHidden {
          StatusBar(manager: manager)
            .frame(maxWidth: 420)
            .transition(.opacity)
        }
        if let issue = manager.connectionIssue {
          ConnectionBanner(
            issue: issue, retry: manager.retryConnection, dismiss: manager.dismissConnectionIssue
          )
          .frame(maxWidth: 420)
        }
        Spacer()
        if controlsHidden {
          CollapsedBar(manager: manager, expand: toggleControls)
            .transition(.opacity)
        }
      }
      .frame(maxWidth: .infinity, alignment: .leading)

      if !controlsHidden {
        ZoomChips(manager: manager, axis: .vertical)
          .frame(maxHeight: .infinity)
        SidePanel(
          manager: manager, mode: $mode, showGallery: $showGallery, lookUp: $lookUp,
          collapse: toggleControls
        )
        .transition(.move(edge: .trailing).combined(with: .opacity))
      }
    }
    .padding(12)
    .animation(.snappy, value: manager.connectionIssue)
  }
}

extension LiveView {
  fileprivate func toggleControls() {
    withAnimation(.snappy) { controlsHidden.toggle() }
  }

  fileprivate func toggleFill() {
    withAnimation(.snappy) { landscapeFill.toggle() }
    var message = landscapeFill ? "Fill screen" : "Fit whole view"
    // Filling a landscape screen keeps about a third of the portrait frame's height.
    if landscapeFill, manager.quality != .high { message += " · 720p looks sharper" }
    show(message)
  }

  fileprivate func show(_ message: String) {
    withAnimation { toast = message }
    toastTask?.cancel()
    toastTask = Task {
      try? await Task.sleep(for: .seconds(1.8))
      guard !Task.isCancelled else { return }
      withAnimation { toast = nil }
    }
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

/// What went wrong with the glasses and what to do, with Try again when it can help.
private struct ConnectionBanner: View {
  let issue: ConnectionIssue
  let retry: () -> Void
  let dismiss: () -> Void

  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack(alignment: .top, spacing: 10) {
        Image(systemName: issue.symbol)
          .font(.title3)
          .foregroundStyle(issue.kind == .device ? .orange : .red)
        VStack(alignment: .leading, spacing: 4) {
          Text(issue.title).font(.subheadline.weight(.semibold))
          Text(issue.message).font(.footnote).foregroundStyle(.secondary)
        }
        Spacer(minLength: 0)
        Button(action: dismiss) {
          Image(systemName: "xmark").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
        }
        .accessibilityLabel("Dismiss")
      }
      if issue.canRetry {
        Button(action: retry) {
          Label("Try again", systemImage: "arrow.clockwise").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.small)
      }
    }
    .padding(12)
    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
    .accessibilityElement(children: .contain)
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
        Section("Glasses link: \(GlassesLink.current.label)") {
          Button("Disconnect glasses", role: .destructive) { manager.disconnectGlasses() }
        }
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
  var axis: Axis = .horizontal

  var body: some View {
    let layout = axis == .horizontal ? AnyLayout(HStackLayout(spacing: 24)) : AnyLayout(VStackLayout(spacing: 14))
    layout {
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
    .frame(maxWidth: axis == .horizontal ? .infinity : nil, minHeight: 28)
    .contentShape(Rectangle())
    .gesture(
      DragGesture(minimumDistance: 20).onEnded { value in
        // Swipe toward the next mode: left/up for VIDEO, right/down for PHOTO.
        let along = axis == .horizontal ? value.translation.width : value.translation.height
        let across = axis == .horizontal ? value.translation.height : value.translation.width
        guard abs(along) > abs(across) else { return }
        select(along < 0 ? .video : .photo)
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

/// Auto enhance, stabilization and auto framing, each a round toggle. A short caption
/// says what was switched, since the icons alone are easy to mix up.
private struct AutoToggles: View {
  @Bindable var manager: WearablesManager
  @State private var caption: String?
  @State private var captionTask: Task<Void, Never>?

  var body: some View {
    HStack(spacing: 10) {
      if let caption {
        Text(caption)
          .font(.footnote.weight(.semibold))
          .padding(.horizontal, 10)
          .padding(.vertical, 6)
          .background(.black.opacity(0.6), in: Capsule())
          .transition(.opacity)
      }
      toggle("wand.and.stars", "Auto enhance", isOn: $manager.autoEnhance)
      toggle("hand.raised.slash", "Stabilize", isOn: $manager.stabilize)
      toggle("person.crop.rectangle", "Auto framing", isOn: $manager.autoFrame)
    }
    .foregroundStyle(.white)
  }

  private func toggle(_ symbol: String, _ name: String, isOn: Binding<Bool>) -> some View {
    Button {
      isOn.wrappedValue.toggle()
      show("\(name) \(isOn.wrappedValue ? "on" : "off")")
    } label: {
      Image(systemName: symbol)
        .font(.system(size: 17, weight: .semibold))
        .foregroundStyle(isOn.wrappedValue ? .yellow : .white)
        .frame(width: 40, height: 40)
        .background(.black.opacity(isOn.wrappedValue ? 0.6 : 0.3), in: Circle())
    }
    .accessibilityLabel(name)
    .accessibilityValue(isOn.wrappedValue ? "On" : "Off")
    .sensoryFeedback(.selection, trigger: isOn.wrappedValue)
  }

  private func show(_ text: String) {
    withAnimation { caption = text }
    captionTask?.cancel()
    captionTask = Task {
      try? await Task.sleep(for: .seconds(1.5))
      guard !Task.isCancelled else { return }
      withAnimation { caption = nil }
    }
  }
}

/// Camera-style zoom presets. Pinch the preview for anything in between.
private struct ZoomChips: View {
  var manager: WearablesManager
  var axis: Axis = .horizontal
  private let presets: [CGFloat] = [1, 1.5, 2, 3]

  var body: some View {
    let layout = axis == .horizontal ? AnyLayout(HStackLayout(spacing: 8)) : AnyLayout(VStackLayout(spacing: 8))
    layout {
      if manager.autoFrame {
        Text("AUTO")
          .font(.footnote.weight(.semibold))
          .foregroundStyle(.yellow)
          .frame(width: 56, height: 32)
          .background(.black.opacity(0.6), in: Capsule())
          .accessibilityLabel("Zoom follows auto framing")
      }
      ForEach(presets, id: \.self) { preset in
        let selected = !manager.autoFrame && abs(manager.zoom - preset) < 0.05
        Button {
          withAnimation(.snappy) {
            manager.autoFrame = false
            manager.zoom = preset
          }
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
      if !manager.autoFrame, !presets.contains(where: { abs(manager.zoom - $0) < 0.05 }) {
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
      GalleryButton(manager: manager, showGallery: $showGallery)
        .frame(maxWidth: .infinity, alignment: .leading)
      Shutter(manager: manager, mode: mode)
      LookUpButton(manager: manager, lookUp: $lookUp)
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
  }
}

/// Latest photo or recording; opens the gallery.
private struct GalleryButton: View {
  var manager: WearablesManager
  @Binding var showGallery: Bool

  var body: some View {
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
  }
}

/// Freezes the current frame for Live Text and Visual Look Up.
private struct LookUpButton: View {
  var manager: WearablesManager
  @Binding var lookUp: FrozenFrame?

  var body: some View {
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
}

/// The landscape capture panel: fold handle, PHOTO / VIDEO, shutter, gallery and look up,
/// and start/stop.
private struct SidePanel: View {
  var manager: WearablesManager
  @Binding var mode: CaptureMode
  @Binding var showGallery: Bool
  @Binding var lookUp: FrozenFrame?
  let collapse: () -> Void

  var body: some View {
    VStack(spacing: 12) {
      Button(action: collapse) {
        Image(systemName: "chevron.compact.right")
          .font(.title2)
          .foregroundStyle(.secondary)
          .frame(maxWidth: .infinity, minHeight: 20)
      }
      .accessibilityLabel("Hide controls")
      ModeSwitcher(mode: $mode, locked: manager.isRecording, axis: .vertical)
      Spacer(minLength: 0)
      Shutter(manager: manager, mode: mode)
      Spacer(minLength: 0)
      HStack(spacing: 10) {
        GalleryButton(manager: manager, showGallery: $showGallery)
        LookUpButton(manager: manager, lookUp: $lookUp)
      }
      Button {
        if manager.wantsStream { manager.stopStreaming() } else { manager.startStreaming() }
      } label: {
        Image(systemName: manager.wantsStream ? "stop.fill" : "play.fill")
          .frame(maxWidth: .infinity, minHeight: 28)
      }
      .buttonStyle(.borderedProminent)
      .tint(manager.wantsStream ? .red : .accentColor)
      .accessibilityLabel(manager.wantsStream ? "Stop live view" : "Start live view")
    }
    .padding(12)
    .frame(width: 128)
    .frame(maxHeight: .infinity)
    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
    .simultaneousGesture(
      DragGesture(minimumDistance: 30).onEnded { value in
        if value.translation.width > 60, value.translation.width > abs(value.translation.height) { collapse() }
      })
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
