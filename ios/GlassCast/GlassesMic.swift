import AVFoundation

/// Captures the glasses' microphone over Bluetooth HFP and delivers 16 kHz mono
/// 16-bit PCM chunks for `MJPEGServer`'s `/audio` route.
///
/// HFP is the only route to the glasses' microphones. It carries 8 kHz (or 16 kHz
/// wideband) voice tuned to the wearer, and it switches the glasses' speakers out of
/// A2DP while active. The phone's own microphone is never used as a fallback.
final class GlassesMic {
  enum MicError: LocalizedError {
    case permissionDenied
    case glassesMicUnavailable

    var errorDescription: String? {
      switch self {
      case .permissionDenied:
        return "Microphone access is off. Turn it on for GlassCast in Settings."
      case .glassesMicUnavailable:
        return "Couldn't find the glasses microphone. Make sure the glasses are connected over Bluetooth."
      }
    }
  }

  private let engine = AVAudioEngine()

  private(set) var isRunning = false

  static func requestPermission() async -> Bool {
    await AVAudioApplication.requestRecordPermission()
  }

  /// Routes input to the glasses and starts capturing. `onChunk` runs on an audio thread.
  func start(onChunk: @escaping (Data) -> Void) throws {
    guard !isRunning else { return }
    guard AVAudioApplication.shared.recordPermission == .granted else { throw MicError.permissionDenied }

    let session = AVAudioSession.sharedInstance()
    // HFP is the profile that carries the glasses' microphones.
    try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothHFP])
    try session.setActive(true)
    guard let glasses = session.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) else {
      try? session.setActive(false, options: .notifyOthersOnDeactivation)
      throw MicError.glassesMicUnavailable
    }
    try session.setPreferredInput(glasses)

    let input = engine.inputNode
    let inputFormat = input.outputFormat(forBus: 0)
    let converter = PCM16Converter()
    input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { buffer, _ in
      guard let data = converter.convert(buffer) else { return }
      onChunk(data)
    }
    do {
      try engine.start()
    } catch {
      input.removeTap(onBus: 0)
      try? session.setActive(false, options: .notifyOthersOnDeactivation)
      throw error
    }
    isRunning = true
  }

  func stop() {
    guard isRunning else { return }
    isRunning = false
    engine.inputNode.removeTap(onBus: 0)
    engine.stop()
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
  }
}
