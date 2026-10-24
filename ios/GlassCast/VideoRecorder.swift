import AVFoundation
import CoreMedia
import UIKit

/// Records live-view frames (as shown, zoom included) and, optionally, 16 kHz mono PCM
/// sound into an H.264/AAC MP4 file.
///
/// Thread-safe: frames and audio arrive on SDK and audio threads and are written on one
/// serial queue. Both are timestamped with the host clock on arrival, so they line up.
final class VideoRecorder: @unchecked Sendable {
  enum RecorderError: LocalizedError {
    case cannotStart(String)
    var errorDescription: String? {
      switch self {
      case .cannotStart(let reason): return "Couldn't start recording: \(reason)"
      }
    }
  }

  private let queue = DispatchQueue(label: "dev.glasscast.recorder")
  private var writer: AVAssetWriter?
  private var videoInput: AVAssetWriterInput?
  private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
  private var audioInput: AVAssetWriterInput?
  private var audioFormat: CMAudioFormatDescription?
  private var sessionStarted = false
  private var sessionStart = CMTime.zero
  private var url: URL?
  private var includesAudio = false

  /// Starts a new recording. The video size is taken from the first frame.
  func start(to url: URL, includesAudio: Bool) {
    queue.async { [self] in
      self.url = url
      self.includesAudio = includesAudio
      sessionStarted = false
      writer = nil
    }
  }

  /// Finishes the file. `completion` runs on the main queue with the file, or an error.
  func stop(completion: @escaping @MainActor (Result<URL, Error>) -> Void) {
    queue.async { [self] in
      guard let writer, let url, sessionStarted else {
        // Nothing was recorded (no frame arrived).
        self.writer = nil
        self.url = nil
        DispatchQueue.main.async {
          completion(.failure(RecorderError.cannotStart("no video arrived from the glasses")))
        }
        return
      }
      videoInput?.markAsFinished()
      audioInput?.markAsFinished()
      writer.finishWriting { [self] in
        let result: Result<URL, Error> =
          writer.status == .completed ? .success(url) : .failure(writer.error ?? RecorderError.cannotStart("unknown"))
        queue.async { [self] in
          self.writer = nil
          self.url = nil
          videoInput = nil
          audioInput = nil
          adaptor = nil
        }
        DispatchQueue.main.async { completion(result) }
      }
    }
  }

  // MARK: - Feeding

  func append(_ image: UIImage) {
    let time = CMClockGetTime(CMClockGetHostTimeClock())
    queue.async { [self] in
      guard url != nil, let cgImage = image.cgImage ?? Self.render(image) else { return }
      if writer == nil { setUpWriter(width: cgImage.width, height: cgImage.height) }
      guard let writer, let videoInput, let adaptor else { return }
      if !sessionStarted {
        guard writer.startWriting() else { return }
        writer.startSession(atSourceTime: time)
        sessionStart = time
        sessionStarted = true
      }
      guard videoInput.isReadyForMoreMediaData, let pool = adaptor.pixelBufferPool,
        let buffer = Self.pixelBuffer(from: cgImage, pool: pool)
      else { return }
      adaptor.append(buffer, withPresentationTime: time)
    }
  }

  /// 16 kHz mono little-endian Int16 PCM, as served at `/audio`.
  func appendAudio(_ pcm: Data) {
    let time = CMClockGetTime(CMClockGetHostTimeClock())
    queue.async { [self] in
      guard sessionStarted, let audioInput, let audioFormat, audioInput.isReadyForMoreMediaData,
        let sample = Self.sampleBuffer(pcm: pcm, format: audioFormat, time: time),
        CMSampleBufferGetPresentationTimeStamp(sample) >= sessionStart
      else { return }
      audioInput.append(sample)
    }
  }

  // MARK: - Setup

  private func setUpWriter(width: Int, height: Int) {
    guard let url, let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return }
    // H.264 needs even dimensions.
    let width = width & ~1
    let height = height & ~1
    let video = AVAssetWriterInput(
      mediaType: .video,
      outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.h264,
        AVVideoWidthKey: width,
        AVVideoHeightKey: height,
        AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 4_000_000],
      ])
    video.expectsMediaDataInRealTime = true
    guard writer.canAdd(video) else { return }
    writer.add(video)
    adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: video,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
      ])
    videoInput = video

    if includesAudio {
      var asbd = AudioStreamBasicDescription(
        mSampleRate: PCM16Converter.sampleRate, mFormatID: kAudioFormatLinearPCM,
        mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
        mBytesPerPacket: 2, mFramesPerPacket: 1, mBytesPerFrame: 2, mChannelsPerFrame: 1,
        mBitsPerChannel: 16, mReserved: 0)
      CMAudioFormatDescriptionCreate(
        allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil, magicCookieSize: 0,
        magicCookie: nil, extensions: nil, formatDescriptionOut: &audioFormat)
      let audio = AVAssetWriterInput(
        mediaType: .audio,
        outputSettings: [
          AVFormatIDKey: kAudioFormatMPEG4AAC,
          AVSampleRateKey: PCM16Converter.sampleRate,
          AVNumberOfChannelsKey: 1,
          AVEncoderBitRateKey: 32_000,
        ])
      audio.expectsMediaDataInRealTime = true
      if writer.canAdd(audio) {
        writer.add(audio)
        audioInput = audio
      }
    }
    self.writer = writer
  }

  // MARK: - Conversions

  private static func render(_ image: UIImage) -> CGImage? {
    UIGraphicsImageRenderer(size: image.size).image { _ in image.draw(at: .zero) }.cgImage
  }

  private static func pixelBuffer(from image: CGImage, pool: CVPixelBufferPool) -> CVPixelBuffer? {
    var buffer: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess, let buffer else {
      return nil
    }
    CVPixelBufferLockBaseAddress(buffer, [])
    defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
    let width = CVPixelBufferGetWidth(buffer)
    let height = CVPixelBufferGetHeight(buffer)
    guard
      let context = CGContext(
        data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
    else { return nil }
    // Zoom changes the frame size mid-recording; scale every frame to the file's size.
    context.interpolationQuality = .medium
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    return buffer
  }

  private static func sampleBuffer(pcm: Data, format: CMAudioFormatDescription, time: CMTime) -> CMSampleBuffer? {
    let frames = pcm.count / 2
    guard frames > 0 else { return nil }
    var block: CMBlockBuffer?
    guard
      CMBlockBufferCreateWithMemoryBlock(
        allocator: nil, memoryBlock: nil, blockLength: pcm.count, blockAllocator: nil,
        customBlockSource: nil, offsetToData: 0, dataLength: pcm.count, flags: 0, blockBufferOut: &block)
        == kCMBlockBufferNoErr, let block
    else { return nil }
    let copied = pcm.withUnsafeBytes {
      CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: pcm.count)
    }
    guard copied == kCMBlockBufferNoErr else { return nil }
    // The chunk ends now; its first sample was captured `duration` earlier.
    let duration = CMTime(value: CMTimeValue(frames), timescale: CMTimeScale(PCM16Converter.sampleRate))
    var sample: CMSampleBuffer?
    CMAudioSampleBufferCreateReadyWithPacketDescriptions(
      allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: frames,
      presentationTimeStamp: CMTimeSubtract(time, duration), packetDescriptions: nil, sampleBufferOut: &sample)
    return sample
  }
}
