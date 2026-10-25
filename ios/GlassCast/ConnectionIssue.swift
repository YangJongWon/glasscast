import Foundation
import MWDATCamera
import MWDATCore

/// How this build reaches the glasses, fixed by Info.plist at build time (see the
/// "Glasses link" build phase): Bluetooth Classic through the accessory protocol, or the
/// Wi-Fi network the glasses host.
enum GlassesLink {
  case bluetooth, wifi

  static let current: GlassesLink =
    Bundle.main.object(forInfoDictionaryKey: "UISupportedExternalAccessoryProtocols") == nil ? .wifi : .bluetooth

  var label: String { self == .wifi ? "Wi-Fi" : "Bluetooth" }
}

/// A problem with the glasses, in words a user can act on: what happened and what to do.
/// Shown as a banner over the live view instead of the SDK's raw error text.
struct ConnectionIssue: Equatable {
  enum Kind: Equatable {
    /// The glasses aren't reachable; usually fixed by waking or reconnecting them.
    case connection
    /// The glasses stopped on purpose (heat, battery, folded); wait or fix, then retry.
    case device
    /// Something GlassCast or the user needs to change (update, permission).
    case setup
  }

  let kind: Kind
  let title: String
  let message: String
  /// Whether "Try again" makes sense.
  var canRetry = true

  var symbol: String {
    switch kind {
    case .connection: return "antenna.radiowaves.left.and.right.slash"
    case .device: return "exclamationmark.triangle"
    case .setup: return "gearshape"
    }
  }

  // MARK: - Common cases

  static let notConnected: ConnectionIssue =
    switch GlassesLink.current {
    case .bluetooth:
      ConnectionIssue(
        kind: .connection,
        title: "Glasses not connected",
        message:
          "Unfold your glasses, put them on and keep them near your phone. If they still don't connect, open the Meta AI app once to wake the connection."
      )
    case .wifi:
      ConnectionIssue(
        kind: .connection,
        title: "Glasses Wi-Fi not joined",
        message:
          "This build streams over the Wi-Fi network your glasses host. Join it in Settings → Wi-Fi (the Meta AI app can show it, e.g. while importing media), come back, and tap Try again."
      )
    }

  static let lost = ConnectionIssue(
    kind: .connection,
    title: "Connection to your glasses was lost",
    message:
      "The glasses may have been taken off, folded, or moved out of Bluetooth range. Put them back on and tap Try again."
  )

  static let noGlasses = ConnectionIssue(
    kind: .connection,
    title: "No glasses found",
    message:
      "Make sure your glasses are turned on, paired in the Meta AI app, and near your phone."
  )

  // MARK: - From SDK errors

  init(kind: Kind, title: String, message: String, canRetry: Bool = true) {
    self.kind = kind
    self.title = title
    self.message = message
    self.canRetry = canRetry
  }

  init(_ error: DeviceSessionError) {
    switch error {
    case .noEligibleDevice:
      self = .noGlasses
    case .unexpectedError(let description) where description.localizedCaseInsensitiveContains("not connected"):
      self = .notConnected
    case .thermalCritical, .thermalEmergency:
      self = .hot
    case .batteryCritical:
      self = .battery
    case .peakPowerShutdown:
      self = .power
    case .datAppOnTheGlassesUpdateRequired:
      self.init(
        kind: .setup, title: "Glasses need an update",
        message: "Open the Meta AI app and install the latest update for your glasses, then try again.",
        canRetry: false)
    case .insufficientSDKVersion:
      self.init(
        kind: .setup, title: "GlassCast needs an update",
        message: "This version of GlassCast is too old for your glasses. Install the latest version.",
        canRetry: false)
    case .dwaUnavailable:
      self.init(
        kind: .connection, title: "Meta AI app isn't connected to your glasses",
        message: "Open the Meta AI app, check that it shows your glasses as connected, then come back and try again.")
    case .sessionAlreadyStopped, .sessionAlreadyExists, .sessionIdle, .capabilityAlreadyActive, .capabilityNotFound,
      .dwaOutOfStuRange, .unexpectedError:
      self.init(
        kind: .connection, title: "Couldn't start live view",
        message: "The connection to your glasses got out of step. Tap Try again. (\(error.localizedDescription))")
    @unknown default:
      self.init(kind: .connection, title: "Couldn't start live view", message: error.localizedDescription)
    }
  }

  init(_ error: StreamError) {
    switch error {
    case .deviceNotFound, .deviceNotConnected:
      self = .notConnected
    case .timeout:
      self.init(
        kind: .connection, title: "The glasses didn't respond",
        message: "Keep your phone close to the glasses and try again. A lower quality setting needs less Bluetooth bandwidth.")
    case .videoStreamingError:
      self.init(
        kind: .connection, title: "Video from the glasses was interrupted",
        message: "The Bluetooth link may be weak. Move your phone closer or choose 360p, then try again.")
    case .audioStreamingError:
      self.init(
        kind: .connection, title: "Sound from the glasses was interrupted",
        message: "Try turning sound off and on again.")
    case .permissionDenied:
      self.init(
        kind: .setup, title: "Camera access is off",
        message: "Allow GlassCast to use the glasses camera in the Meta AI app, then try again.")
    case .hingesClosed:
      self.init(
        kind: .device, title: "Your glasses are folded",
        message: "Unfold your glasses and put them on to continue.")
    case .thermalHot:
      self = .hot
    case .batteryLow:
      self = .battery
    case .peakPowerLimit:
      self = .power
    case .internalError, .photoCaptureFailed:
      self.init(
        kind: .connection, title: "Live view stopped",
        message: "Something went wrong on the glasses. Tap Try again. (\(error.localizedDescription))")
    @unknown default:
      self.init(kind: .connection, title: "Live view stopped", message: error.localizedDescription)
    }
  }

  init(_ error: PermissionError) {
    switch error {
    case .noDevice:
      self = .noGlasses
    case .noDeviceWithConnection, .connectionError:
      self = .notConnected
    case .metaAINotInstalled:
      self = .metaAIMissing
    case .requestInProgress:
      self.init(
        kind: .setup, title: "Waiting for your answer in Meta AI",
        message: "Finish the camera permission request in the Meta AI app, then come back.")
    case .requestTimeout:
      self.init(
        kind: .setup, title: "Camera permission wasn't confirmed",
        message: "The Meta AI app didn't answer in time. Tap Try again and approve camera access there.")
    case .internalError:
      self.init(kind: .setup, title: "Couldn't ask for camera access", message: error.localizedDescription)
    @unknown default:
      self.init(kind: .setup, title: "Couldn't ask for camera access", message: error.localizedDescription)
    }
  }

  /// Text for registration ("Connect my glasses") failures, shown as an alert.
  static func registrationMessage(for error: Error) -> String {
    switch error as? RegistrationError {
    case .metaAINotInstalled: return metaAIMissing.message
    case .networkUnavailable: return "Connecting your glasses needs the internet. Check your connection and try again."
    case .configurationInvalid:
      return "GlassCast's Meta app settings are invalid. Check META_APP_ID and CLIENT_TOKEN in Secrets.xcconfig, or turn on Developer Mode in the Meta AI app."
    case .alreadyRegistered: return "Your glasses are already connected."
    default: return "Couldn't connect your glasses: \(error.localizedDescription)"
    }
  }

  private static let metaAIMissing = ConnectionIssue(
    kind: .setup, title: "Meta AI app not installed",
    message: "GlassCast connects to your glasses through the Meta AI app. Install it from the App Store, pair your glasses, then try again.",
    canRetry: false)

  private static let hot = ConnectionIssue(
    kind: .device, title: "Your glasses are too warm",
    message: "The camera paused to cool down. Take the glasses off for a few minutes, then try again. A lower quality setting runs cooler.")

  private static let battery = ConnectionIssue(
    kind: .device, title: "Glasses battery is low",
    message: "Charge your glasses in their case, then try again.")

  private static let power = ConnectionIssue(
    kind: .device, title: "Glasses need more power",
    message: "The glasses can't power the camera right now, usually because the battery is low. Charge them, then try again.")
}
