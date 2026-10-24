import MWDATCamera
import MWDATCore
import Observation
import UIKit

/// Owns the DAT SDK lifecycle: registration, the `DeviceSession`, and the camera
/// `Stream`. Every frame goes to the on-screen preview and to `MJPEGServer`, and, when
/// the user turns sound on, the glasses' microphone goes to the server's `/audio` route.
@Observable
@MainActor
final class WearablesManager {
  enum Quality: String, CaseIterable, Identifiable {
    case low, medium, high

    var id: String { rawValue }
    var label: String {
      switch self {
      case .low: return "360p"
      case .medium: return "504p"
      case .high: return "720p"
      }
    }
    var resolution: StreamingResolution {
      switch self {
      case .low: return .low
      case .medium: return .medium
      case .high: return .high
      }
    }
  }

  static let frameRate: UInt = 24

  #if EXPERIMENTAL_AUDIO
  /// Where sound comes from. Only Debug builds compile this: the camera stream's audio
  /// is an experimental DAT feature, and apps using it can't be published yet.
  enum AudioSource: String, CaseIterable, Identifiable {
    /// Hands-free profile; standard Bluetooth, fine to ship.
    case bluetooth
    /// PCM delivered with the camera stream, in sync with the video (experimental).
    case cameraStream

    var id: String { rawValue }
    var label: String {
      switch self {
      case .bluetooth: return "Bluetooth (HFP)"
      case .cameraStream: return "Camera stream (experimental)"
      }
    }
  }
  #endif

  #if EXPERIMENTAL_PHOTO
  /// How photos are taken. Only Debug builds compile this: `Camera.photo` is an
  /// experimental DAT feature, and apps using it can't be published yet.
  enum PhotoSource: String, CaseIterable, Identifiable {
    /// `Stream.capturePhoto`, taken while live view runs; fine to ship.
    case stream
    /// `Camera.photo` at full resolution and high quality (experimental).
    case fullQuality

    var id: String { rawValue }
    var label: String {
      switch self {
      case .stream: return "Standard"
      case .fullQuality: return "Full quality (experimental)"
      }
    }
  }
  #endif

  // MARK: - Observed state

  private(set) var registrationState: RegistrationState
  private(set) var hasActiveDevice = false
  private(set) var sessionState: DeviceSessionState = .idle
  private(set) var streamState: StreamState = .stopped
  private(set) var frame: UIImage?
  private(set) var fps: Double = 0
  private(set) var viewerCount = 0
  var quality: Quality = .medium {
    didSet { if oldValue != quality { restartStream() } }
  }
  /// Off by default and on every launch; the microphone only runs while this is on and
  /// live view is running.
  private(set) var audioEnabled = false
  private(set) var isCapturingAudio = false
  #if EXPERIMENTAL_AUDIO
  private(set) var audioSource: AudioSource = .bluetooth
  #endif
  /// Photos taken with the glasses camera.
  let photos = PhotoStore()
  /// A photo was requested and hasn't arrived yet.
  private(set) var isTakingPhoto = false
  /// Transfer progress of a full-quality photo, 0...1; nil otherwise.
  private(set) var photoProgress: Double?
  #if EXPERIMENTAL_PHOTO
  var photoSource: PhotoSource = .stream
  #endif
  var errorMessage: String?
  var showCameraPermissionPrompt = false

  var isRegistered: Bool { registrationState == .registered }
  var isStreaming: Bool { streamState == .streaming }
  /// Streaming was requested and hasn't been stopped (session or stream may still be starting).
  private(set) var wantsStream = false

  var statusText: String {
    switch streamState {
    case .streaming: return "Live"
    case .paused: return "Paused on glasses"
    case .starting: return "Starting camera…"
    case .waitingForDevice: return "Waiting for glasses…"
    case .stopping: return "Stopping…"
    case .stopped:
      if sessionState == .starting { return "Connecting to glasses…" }
      return hasActiveDevice ? "Ready" : "Put on your glasses"
    }
  }

  // MARK: - Private

  let server: MJPEGServer
  @ObservationIgnored private let wearables: WearablesInterface
  @ObservationIgnored private let deviceSelector: AutoDeviceSelector
  @ObservationIgnored private var session: DeviceSession?
  @ObservationIgnored private var camera: MWDATCamera.Camera?
  @ObservationIgnored private let sessionTokens = ListenerTokenBag()
  @ObservationIgnored private let streamTokens = ListenerTokenBag()
  @ObservationIgnored private var restartAfterStop = false
  @ObservationIgnored private var frameTimes: [CFTimeInterval] = []
  @ObservationIgnored private var tasks: [Task<Void, Never>] = []
  @ObservationIgnored private let mic = GlassesMic()
  @ObservationIgnored private var photoTimeout: Task<Void, Never>?
  #if EXPERIMENTAL_PHOTO
  @ObservationIgnored private var photoObserved = false
  @ObservationIgnored private var photoState: PhotoState = .stopped
  @ObservationIgnored private var pendingFullQualityPhoto = false
  #endif

  init(wearables: WearablesInterface = Wearables.shared, server: MJPEGServer = MJPEGServer()) {
    self.wearables = wearables
    self.server = server
    self.deviceSelector = AutoDeviceSelector(wearables: wearables)
    self.registrationState = wearables.registrationState

    server.onViewerCountChange = { [weak self] count in self?.viewerCount = count }
    server.onError = { [weak self] message in self?.errorMessage = message }
    server.onCaptureRequest = { [weak self] in self?.takePhoto() }
    server.start()

    tasks.append(Task { [weak self] in
      for await state in wearables.registrationStateStream() {
        self?.registrationState = state
      }
    })
    tasks.append(Task { [weak self, deviceSelector] in
      for await deviceId in deviceSelector.activeDeviceStream() {
        self?.hasActiveDevice = deviceId != nil
      }
    })
  }

  // MARK: - Registration

  func connectGlasses() {
    guard registrationState != .registering else { return }
    Task {
      do {
        try await wearables.startRegistration()
      } catch {
        errorMessage = String(describing: error)
      }
    }
  }

  func disconnectGlasses() {
    stopStreaming()
    Task {
      do {
        try await wearables.startUnregistration()
      } catch {
        errorMessage = String(describing: error)
      }
    }
  }

  /// Forwards Meta AI callback URLs (registration / permission results) to the SDK.
  func handleCallback(_ url: URL) {
    guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
      components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
    else { return }
    Task {
      do {
        _ = try await wearables.handleUrl(url)
      } catch {
        errorMessage = String(describing: error)
      }
    }
  }

  // MARK: - Streaming

  /// Starts a device session if needed; the camera is added once the session reports `.started`.
  func startStreaming() {
    wantsStream = true
    UIApplication.shared.isIdleTimerDisabled = true
    // Meta recommends setting up HFP before the camera stream starts.
    updateAudioCapture()
    if let session {
      if session.state == .started { Task { await beginStreamIfPermitted() } }
      return
    }
    do {
      let newSession = try wearables.createSession(deviceSelector: deviceSelector)
      session = newSession
      // Subscribe before start() so no initial transitions are missed.
      observe(newSession)
      sessionState = .starting
      try newSession.start()
    } catch {
      errorMessage = error.localizedDescription
      cleanupSession()
    }
  }

  /// Ends the session; the SDK cascades the stop to the camera and its stream.
  func stopStreaming() {
    wantsStream = false
    restartAfterStop = false
    UIApplication.shared.isIdleTimerDisabled = false
    updateAudioCapture()
    session?.stop()
  }

  // MARK: - Photos

  /// Asks the glasses camera for a photo. It arrives through the stream's photo publisher.
  func takePhoto() {
    guard isStreaming, let camera, !isTakingPhoto else { return }
    isTakingPhoto = true
    photoTimeout?.cancel()
    photoTimeout = Task { [weak self] in
      try? await Task.sleep(for: .seconds(20))
      guard !Task.isCancelled else { return }
      self?.photoFailed("The photo didn't arrive from the glasses. Try again.")
    }
    #if EXPERIMENTAL_PHOTO
    if photoSource == .fullQuality {
      takeFullQualityPhoto(with: camera)
      return
    }
    #endif
    if !camera.stream.capturePhoto(format: .jpeg) {
      photoFailed("The glasses couldn't take a photo right now. Try again.")
    }
  }

  private func didCapturePhoto(_ data: Data) {
    guard isTakingPhoto else { return }
    photoTimeout?.cancel()
    isTakingPhoto = false
    photoProgress = nil
    // Keep everything as JPEG so the gallery, sharing and /photo.jpg agree.
    let isJPEG = data.starts(with: [0xFF, 0xD8])
    guard let jpeg = isJPEG ? data : UIImage(data: data)?.jpegData(compressionQuality: 0.92) else {
      errorMessage = "The glasses sent a photo GlassCast couldn't read."
      return
    }
    do {
      try photos.add(jpeg)
      server.publishPhoto(jpeg)
      UINotificationFeedbackGenerator().notificationOccurred(.success)
    } catch {
      errorMessage = "Couldn't save the photo: \(error.localizedDescription)"
    }
  }

  private func photoFailed(_ message: String) {
    guard isTakingPhoto else { return }
    photoTimeout?.cancel()
    isTakingPhoto = false
    photoProgress = nil
    errorMessage = message
  }

  #if EXPERIMENTAL_PHOTO
  private func takeFullQualityPhoto(with camera: MWDATCamera.Camera) {
    if !photoObserved {
      photoObserved = true
      observe(camera.photo)
    }
    if photoState == .started {
      camera.photo.capturePhoto(resolution: .full, quality: .high)
    } else {
      // Starting the photo capability takes a moment; capture once it reports `.started`.
      pendingFullQualityPhoto = true
      camera.photo.start()
    }
  }

  private func observe(_ photo: MWDATCamera.Photo) {
    photo.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.handlePhotoState(state) }
    }.store(in: streamTokens)
    photo.photoDataPublisher.listen { [weak self] capture in
      Task { @MainActor in self?.didCapturePhoto(capture.imageData) }
    }.store(in: streamTokens)
    photo.transferProgressPublisher.listen { [weak self] progress in
      Task { @MainActor in self?.photoProgress = progress.fraction }
    }.store(in: streamTokens)
    photo.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.photoFailed(error.localizedDescription) }
    }.store(in: streamTokens)
  }

  private func handlePhotoState(_ state: PhotoState) {
    photoState = state
    if state == .started, pendingFullQualityPhoto {
      pendingFullQualityPhoto = false
      camera?.photo.capturePhoto(resolution: .full, quality: .high)
    }
  }
  #endif

  // MARK: - Audio

  func setAudioEnabled(_ enabled: Bool) {
    guard enabled != audioEnabled else { return }
    guard enabled else {
      audioEnabled = false
      updateAudioCapture()
      return
    }
    Task {
      guard await requestAudioPermission() else { return }
      audioEnabled = true
      updateAudioCapture()
    }
  }

  #if EXPERIMENTAL_AUDIO
  func setAudioSource(_ source: AudioSource) {
    guard source != audioSource else { return }
    Task {
      let previous = audioSource
      audioSource = source
      if audioEnabled, !(await requestAudioPermission()) {
        audioSource = previous
        return
      }
      updateAudioCapture()
    }
  }
  #endif

  private var usesStreamAudio: Bool {
    #if EXPERIMENTAL_AUDIO
    return audioSource == .cameraStream
    #else
    return false
    #endif
  }

  /// iOS microphone permission for HFP; the Meta AI microphone permission for stream audio.
  private func requestAudioPermission() async -> Bool {
    guard usesStreamAudio else {
      if await GlassesMic.requestPermission() { return true }
      errorMessage = GlassesMic.MicError.permissionDenied.localizedDescription
      return false
    }
    do {
      if try await wearables.checkPermissionStatus(.microphone) == .granted { return true }
      if try await wearables.requestPermission(.microphone) == .granted { return true }
      errorMessage = "Microphone permission was denied in the Meta AI app."
    } catch {
      errorMessage = error.localizedDescription
    }
    return false
  }

  /// Runs the sound source exactly while sound is on and live view is wanted.
  private func updateAudioCapture() {
    let useMic = audioEnabled && wantsStream && !usesStreamAudio
    if useMic != mic.isRunning {
      if useMic {
        let server = self.server
        do {
          try mic.start { server.publishAudio($0) }
        } catch {
          audioEnabled = false
          errorMessage = error.localizedDescription
        }
      } else {
        mic.stop()
      }
    }
    let streamAudio = audioEnabled && wantsStream && usesStreamAudio
    server.setAudioAvailable(mic.isRunning || streamAudio)
    isCapturingAudio = mic.isRunning || streamAudio

    // The audio codec is fixed per stream, so restart the camera stream when it changes.
    if wantsStream, let camera, (camera.stream.streamConfiguration.audioCodec != nil) != streamAudio {
      restartStream()
    }
  }

  /// Called after the user confirms the switch to the Meta AI app for camera permission.
  func confirmCameraPermission() {
    showCameraPermissionPrompt = false
    Task {
      do {
        guard try await wearables.requestPermission(.camera) == .granted else {
          errorMessage = "Camera permission was denied in the Meta AI app."
          return
        }
        beginStream()
      } catch {
        errorMessage = error.localizedDescription
      }
    }
  }

  private func beginStreamIfPermitted() async {
    guard wantsStream, camera == nil else { return }
    do {
      if try await wearables.checkPermissionStatus(.camera) == .granted {
        beginStream()
      } else {
        showCameraPermissionPrompt = true
      }
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  private func beginStream() {
    guard wantsStream, camera == nil, let session, session.state == .started else { return }
    do {
      guard let newCamera = try session.addCamera(config: makeStreamConfiguration()) else {
        errorMessage = "Couldn't start the glasses camera. Try again."
        return
      }
      camera = newCamera
      observe(newCamera.stream)
      streamState = .starting
      newCamera.stream.start()
    } catch {
      camera = nil
      errorMessage = error.localizedDescription
    }
  }

  private func makeStreamConfiguration() -> StreamConfiguration {
    // `.raw` delivers decoded frames that `makeUIImage()` can render directly.
    #if EXPERIMENTAL_AUDIO
    if audioEnabled && usesStreamAudio {
      return StreamConfiguration(
        videoCodec: .raw,
        audioCodec: .pcm(sampleRate: .rate16000, numberOfChannels: 1),
        resolution: quality.resolution,
        frameRate: Self.frameRate
      )
    }
    #endif
    return StreamConfiguration(videoCodec: .raw, resolution: quality.resolution, frameRate: Self.frameRate)
  }

  private func restartStream() {
    guard let camera else { return }
    restartAfterStop = true
    camera.stop()
  }

  // MARK: - Observation

  private func observe(_ session: DeviceSession) {
    session.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.handleSessionState(state) }
    }.store(in: sessionTokens)
    session.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.handleSessionError(error) }
    }.store(in: sessionTokens)
  }

  private func handleSessionState(_ state: DeviceSessionState) {
    sessionState = state
    switch state {
    case .started:
      Task { await beginStreamIfPermitted() }
    case .stopped:
      cleanupSession()
    default:
      break
    }
  }

  /// A session that fails before it starts may never report `.stopped`. Drop it so the
  /// button returns to "Start live view" and the next tap creates a fresh session.
  private func handleSessionError(_ error: DeviceSessionError) {
    errorMessage = error.localizedDescription
    guard sessionState != .started else { return }
    session?.stop()
    cleanupSession()
  }

  private func observe(_ stream: MWDATCamera.Stream) {
    let server = self.server
    stream.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.handleStreamState(state) }
    }.store(in: streamTokens)

    stream.videoFramePublisher.listen { [weak self] frame in
      guard let image = frame.makeUIImage() else { return }
      // Hand the frame to the server straight from the SDK thread; the UI hops to main.
      server.publish(image)
      Task { @MainActor in self?.didReceive(image) }
    }.store(in: streamTokens)

    stream.photoDataPublisher.listen { [weak self] photo in
      Task { @MainActor in self?.didCapturePhoto(photo.data) }
    }.store(in: streamTokens)

    stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor in
        if error == .photoCaptureFailed {
          self?.photoFailed(error.localizedDescription)
        } else {
          self?.errorMessage = error.localizedDescription
        }
      }
    }.store(in: streamTokens)

    #if EXPERIMENTAL_AUDIO
    if stream.streamConfiguration.audioCodec != nil {
      let converter = PCM16Converter()
      stream.audioFramePublisher.listen { frame in
        guard let pcm = converter.convert(frame.pcmBuffer) else { return }
        server.publishAudio(pcm)
      }.store(in: streamTokens)
    }
    #endif
  }

  private func handleStreamState(_ state: StreamState) {
    streamState = state
    server.update { $0.state = Self.statusName(state) }
    guard state == .stopped else { return }

    streamTokens.clear()
    camera?.stop()
    camera = nil
    photoFailed("Live view stopped before the photo arrived.")
    #if EXPERIMENTAL_PHOTO
    photoObserved = false
    photoState = .stopped
    pendingFullQualityPhoto = false
    #endif
    frame = nil
    fps = 0
    frameTimes.removeAll()
    server.update { $0.fps = 0 }

    if restartAfterStop {
      restartAfterStop = false
      beginStream()
    }
  }

  private func didReceive(_ image: UIImage) {
    frame = image
    let now = CACurrentMediaTime()
    frameTimes.append(now)
    frameTimes.removeAll { now - $0 > 1 }
    fps = Double(frameTimes.count)

    let fps = self.fps
    let width = Int(image.size.width * image.scale)
    let height = Int(image.size.height * image.scale)
    server.update {
      $0.fps = fps
      $0.width = width
      $0.height = height
    }
  }

  private func cleanupSession() {
    sessionTokens.clear()
    session = nil
    sessionState = .stopped
    // Stopped by the user, or the glasses dropped the session (taken off, out of range).
    wantsStream = false
    UIApplication.shared.isIdleTimerDisabled = false
    updateAudioCapture()
  }

  /// Names used by `/status` and `web/viewer.html`.
  private static func statusName(_ state: StreamState) -> String {
    switch state {
    case .streaming: return "streaming"
    case .paused: return "paused"
    case .starting: return "starting"
    case .waitingForDevice: return "waitingForDevice"
    case .stopping, .stopped: return "stopped"
    }
  }
}
