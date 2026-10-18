import MWDATCamera
import MWDATCore
import Observation
import UIKit

/// Owns the DAT SDK lifecycle: registration, the `DeviceSession`, and the camera
/// `Stream`. Every frame goes to the on-screen preview and to `MJPEGServer`.
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

  // MARK: - Observed state

  private(set) var registrationState: RegistrationState
  private(set) var hasActiveDevice = false
  private(set) var sessionState: DeviceSessionState = .idle
  private(set) var streamState: StreamState = .stopped
  private(set) var frame: UIImage?
  private(set) var fps: Double = 0
  private(set) var viewerCount = 0
  var quality: Quality = .medium {
    didSet { if oldValue != quality { restartStreamForNewQuality() } }
  }
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

  init(wearables: WearablesInterface = Wearables.shared, server: MJPEGServer = MJPEGServer()) {
    self.wearables = wearables
    self.server = server
    self.deviceSelector = AutoDeviceSelector(wearables: wearables)
    self.registrationState = wearables.registrationState

    server.onViewerCountChange = { [weak self] count in self?.viewerCount = count }
    server.onError = { [weak self] message in self?.errorMessage = message }
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
    session?.stop()
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
    // `.raw` delivers decoded frames that `makeUIImage()` can render directly.
    let config = StreamConfiguration(
      videoCodec: .raw,
      resolution: quality.resolution,
      frameRate: Self.frameRate
    )
    do {
      guard let newCamera = try session.addCamera(config: config) else {
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

  private func restartStreamForNewQuality() {
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
      Task { @MainActor in self?.errorMessage = error.localizedDescription }
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

    stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.errorMessage = error.localizedDescription }
    }.store(in: streamTokens)
  }

  private func handleStreamState(_ state: StreamState) {
    streamState = state
    server.update { $0.state = Self.statusName(state) }
    guard state == .stopped else { return }

    streamTokens.clear()
    camera?.stop()
    camera = nil
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
