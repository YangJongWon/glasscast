import AVFoundation

/// Resamples PCM buffers of any format to 16 kHz mono Int16, the format `/audio` serves.
/// Not thread-safe: feed it from one thread at a time.
final class PCM16Converter {
  static let sampleRate: Double = 16_000
  static let format = AVAudioFormat(
    commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: 1, interleaved: true)!

  private var converter: AVAudioConverter?

  /// Returns the buffer as raw little-endian Int16 bytes, or nil if it couldn't be converted.
  func convert(_ buffer: AVAudioPCMBuffer) -> Data? {
    if converter?.inputFormat != buffer.format {
      converter = AVAudioConverter(from: buffer.format, to: Self.format)
    }
    guard let converter else { return nil }
    let ratio = Self.sampleRate / buffer.format.sampleRate
    let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 16
    guard let output = AVAudioPCMBuffer(pcmFormat: Self.format, frameCapacity: capacity) else { return nil }

    var consumed = false
    var error: NSError?
    converter.convert(to: output, error: &error) { _, status in
      if consumed {
        status.pointee = .noDataNow
        return nil
      }
      consumed = true
      status.pointee = .haveData
      return buffer
    }
    guard error == nil, output.frameLength > 0, let samples = output.int16ChannelData else { return nil }
    return Data(bytes: samples[0], count: Int(output.frameLength) * MemoryLayout<Int16>.size)
  }
}
