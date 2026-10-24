import Foundation
import Network
import UIKit

/// Re-broadcasts the glasses' live view over the local network as MJPEG.
///
/// Routes (identical on Android and in `tools/mock_server.py`):
/// - `GET /`             → the bundled `viewer.html`
/// - `GET /stream`       → `multipart/x-mixed-replace` JPEG stream
/// - `GET /snapshot.jpg` → the newest frame
/// - `GET /audio`        → glasses microphone as an endless 16 kHz mono WAV (503 while off)
/// - `GET /status`       → JSON `{state, fps, width, height, clients, audio}`
///
/// All mutable state is confined to `queue`. Only the newest frame is kept, and each
/// stream client is sent the newest frame whenever its previous send finishes, so a
/// slow viewer skips frames instead of building up a backlog.
final class MJPEGServer: @unchecked Sendable {
  static let port: UInt16 = 8080
  static let maxStreamClients = 4
  static let maxAudioClients = 4
  static let bonjourType = "_glasscast._tcp"
  private static let boundary = "glasscast"
  private static let jpegQuality: CGFloat = 0.7
  private static let maxRequestBytes = 16 * 1024
  /// About one second of 16 kHz Int16 audio. A viewer further behind loses the oldest samples.
  private static let maxAudioBacklog = 32_000

  struct Status {
    var state = "stopped"
    var fps = 0.0
    var width = 0
    var height = 0
  }

  /// Called on the main queue whenever the number of `/stream` viewers changes.
  var onViewerCountChange: ((Int) -> Void)?
  /// Called on the main queue when the listener fails (e.g. port already in use).
  var onError: ((String) -> Void)?

  private let queue = DispatchQueue(label: "dev.glasscast.mjpeg-server")
  private var listener: NWListener?
  private var streamClients: [ObjectIdentifier: StreamClient] = [:]
  private var audioClients: [ObjectIdentifier: AudioClient] = [:]
  private var audioAvailable = false
  private var status = Status()

  private var latestImage: UIImage?
  private var latestJPEG: Data?
  private var latestSeq = 0
  private var encodedSeq = -1

  private lazy var viewerHTML: Data = {
    guard let url = Bundle.main.url(forResource: "viewer", withExtension: "html"),
      let data = try? Data(contentsOf: url)
    else {
      return Data("<h1>GlassCast</h1><p>viewer.html missing from app bundle. <a href=\"stream\">Open the raw stream</a>.</p>".utf8)
    }
    return data
  }()

  // MARK: - Lifecycle

  /// Starts listening on port 8080. Safe to call repeatedly (e.g. on every return to
  /// the foreground, since iOS tears listeners down while the app is suspended).
  func start() {
    queue.async { [self] in
      guard listener == nil else { return }
      do {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: Self.port)!)
        listener.service = NWListener.Service(type: Self.bonjourType)
        listener.newConnectionHandler = { [weak self] connection in
          self?.accept(connection)
        }
        listener.stateUpdateHandler = { [weak self] state in
          guard let self else { return }
          switch state {
          case .failed(let error):
            report("Desktop sharing stopped: \(error.localizedDescription)")
            stopListener()
          case .cancelled:
            stopListener()
          default:
            break
          }
        }
        self.listener = listener
        listener.start(queue: queue)
      } catch {
        report("Couldn't start desktop sharing: \(error.localizedDescription)")
      }
    }
  }

  func stop() {
    queue.async { [self] in stopListener() }
  }

  // MARK: - Feeding frames and status

  /// Hands the server a new frame. Called from the SDK's frame callback thread.
  func publish(_ image: UIImage) {
    queue.async { [self] in
      latestImage = image
      latestSeq += 1
      // Only spend time on JPEG encoding when someone is actually watching.
      guard !streamClients.isEmpty else { return }
      for client in streamClients.values where !client.isSending {
        send(latestFrameJPEG(), to: client)
      }
    }
  }

  func update(_ change: @escaping (inout Status) -> Void) {
    queue.async { [self] in change(&status) }
  }

  /// Opens or closes `/audio`. Closing hangs up every audio viewer.
  func setAudioAvailable(_ available: Bool) {
    queue.async { [self] in
      audioAvailable = available
      guard !available else { return }
      for client in audioClients.values {
        client.connection.cancel()
      }
      audioClients.removeAll()
    }
  }

  /// Hands the server 16 kHz mono Int16 PCM. Called from the audio capture thread.
  func publishAudio(_ pcm: Data) {
    queue.async { [self] in
      guard audioAvailable else { return }
      for client in audioClients.values {
        client.pending.append(pcm)
        if client.pending.count > Self.maxAudioBacklog {
          // Keep sample alignment when dropping.
          let excess = (client.pending.count - Self.maxAudioBacklog + 1) & ~1
          client.pending.removeFirst(excess)
        }
        if !client.isSending { flushAudio(to: client) }
      }
    }
  }

  // MARK: - Connections

  private func accept(_ connection: NWConnection) {
    connection.start(queue: queue)
    receiveRequest(on: connection, buffer: Data())
  }

  /// Reads until the end of the HTTP request headers, then routes the request.
  private func receiveRequest(on connection: NWConnection, buffer: Data) {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
      guard let self else { return }
      var buffer = buffer
      if let data { buffer.append(data) }

      if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
        route(String(decoding: buffer[..<end.lowerBound], as: UTF8.self), on: connection)
      } else if error != nil || isComplete || buffer.count > Self.maxRequestBytes {
        connection.cancel()
      } else {
        receiveRequest(on: connection, buffer: buffer)
      }
    }
  }

  private func route(_ head: String, on connection: NWConnection) {
    let requestLine = head.split(separator: "\r\n", maxSplits: 1).first ?? ""
    let parts = requestLine.split(separator: " ")
    guard parts.count >= 2, parts[0] == "GET" || parts[0] == "HEAD" else {
      respond(on: connection, status: "405 Method Not Allowed", type: "text/plain", body: Data("Method not allowed\n".utf8))
      return
    }
    let path = parts[1].split(separator: "?", maxSplits: 1).first.map(String.init) ?? "/"

    switch path {
    case "/", "/index.html":
      respond(on: connection, status: "200 OK", type: "text/html; charset=utf-8", body: viewerHTML)
    case "/status":
      respond(on: connection, status: "200 OK", type: "application/json", body: statusJSON())
    case "/snapshot.jpg":
      if let jpeg = latestFrameJPEG() {
        respond(on: connection, status: "200 OK", type: "image/jpeg", body: jpeg)
      } else {
        respond(on: connection, status: "503 Service Unavailable", type: "text/plain", body: Data("No frame yet\n".utf8))
      }
    case "/stream":
      startStream(on: connection)
    case "/audio":
      startAudio(on: connection)
    default:
      respond(on: connection, status: "404 Not Found", type: "text/plain", body: Data("Not found\n".utf8))
    }
  }

  private func respond(on connection: NWConnection, status: String, type: String, body: Data) {
    var response = Data(
      ("HTTP/1.1 \(status)\r\n"
        + "Content-Type: \(type)\r\n"
        + "Content-Length: \(body.count)\r\n"
        + "Cache-Control: no-store\r\n"
        + "Access-Control-Allow-Origin: *\r\n"
        + "Connection: close\r\n\r\n").utf8)
    response.append(body)
    connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
  }

  // MARK: - MJPEG stream

  private func startStream(on connection: NWConnection) {
    guard streamClients.count < Self.maxStreamClients else {
      respond(on: connection, status: "503 Service Unavailable", type: "text/plain", body: Data("Too many viewers\n".utf8))
      return
    }
    let header = Data(
      ("HTTP/1.1 200 OK\r\n"
        + "Content-Type: multipart/x-mixed-replace; boundary=\(Self.boundary)\r\n"
        + "Cache-Control: no-store\r\n"
        + "Access-Control-Allow-Origin: *\r\n"
        + "Connection: close\r\n\r\n").utf8)

    let client = StreamClient(connection: connection)
    streamClients[ObjectIdentifier(client)] = client
    viewerCountChanged()

    connection.stateUpdateHandler = { [weak self, weak client] state in
      guard let self, let client else { return }
      switch state {
      case .failed, .cancelled:
        remove(client)
      default:
        break
      }
    }
    // The viewer never sends anything after the request; a completed read means it hung up.
    watchForHangUp(connection)

    client.isSending = true
    connection.send(content: header, completion: .contentProcessed { [weak self, weak client] error in
      guard let self, let client else { return }
      client.isSending = false
      if error != nil {
        connection.cancel()
      } else if let jpeg = latestFrameJPEG() {
        send(jpeg, to: client)
      }
    })
  }

  private func watchForHangUp(_ connection: NWConnection) {
    connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] _, _, isComplete, error in
      guard let self else { return }
      if isComplete || error != nil {
        connection.cancel()
      } else if connection.state == .ready {
        watchForHangUp(connection)
      }
    }
  }

  private func send(_ jpeg: Data?, to client: StreamClient) {
    guard let jpeg else { return }
    let seq = latestSeq
    var part = Data(
      ("--\(Self.boundary)\r\n"
        + "Content-Type: image/jpeg\r\n"
        + "Content-Length: \(jpeg.count)\r\n\r\n").utf8)
    part.append(jpeg)
    part.append(Data("\r\n".utf8))

    client.isSending = true
    client.connection.send(content: part, completion: .contentProcessed { [weak self, weak client] error in
      guard let self, let client else { return }
      client.isSending = false
      client.sentSeq = seq
      if error != nil {
        client.connection.cancel()
      } else if latestSeq != client.sentSeq {
        // A newer frame arrived while this one was in flight: send it now.
        send(latestFrameJPEG(), to: client)
      }
    })
  }

  // MARK: - Audio stream

  private func startAudio(on connection: NWConnection) {
    guard audioAvailable else {
      respond(on: connection, status: "503 Service Unavailable", type: "text/plain", body: Data("Audio is off\n".utf8))
      return
    }
    guard audioClients.count < Self.maxAudioClients else {
      respond(on: connection, status: "503 Service Unavailable", type: "text/plain", body: Data("Too many listeners\n".utf8))
      return
    }
    var header = Data(
      ("HTTP/1.1 200 OK\r\n"
        + "Content-Type: audio/wav\r\n"
        + "Cache-Control: no-store\r\n"
        + "Access-Control-Allow-Origin: *\r\n"
        + "Connection: close\r\n\r\n").utf8)
    header.append(Self.wavHeader(sampleRate: Int(PCM16Converter.sampleRate)))

    let client = AudioClient(connection: connection)
    audioClients[ObjectIdentifier(client)] = client
    connection.stateUpdateHandler = { [weak self, weak client] state in
      guard let self, let client else { return }
      switch state {
      case .failed, .cancelled:
        audioClients.removeValue(forKey: ObjectIdentifier(client))
      default:
        break
      }
    }
    watchForHangUp(client.connection)

    client.isSending = true
    connection.send(content: header, completion: .contentProcessed { [weak self, weak client] error in
      guard let self, let client else { return }
      client.isSending = false
      if error != nil {
        connection.cancel()
      } else {
        flushAudio(to: client)
      }
    })
  }

  private func flushAudio(to client: AudioClient) {
    guard !client.pending.isEmpty else { return }
    let chunk = client.pending
    client.pending = Data()
    client.isSending = true
    client.connection.send(content: chunk, completion: .contentProcessed { [weak self, weak client] error in
      guard let self, let client else { return }
      client.isSending = false
      if error != nil {
        client.connection.cancel()
      } else {
        flushAudio(to: client)
      }
    })
  }

  /// WAV header for an endless mono 16-bit stream. The size fields are left at their
  /// maximum, which browsers, VLC and OBS (ffmpeg) treat as "read until the connection ends".
  private static func wavHeader(sampleRate: Int) -> Data {
    var data = Data()
    func append<T: FixedWidthInteger>(_ value: T) {
      withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
    data.append(Data("RIFF".utf8))
    append(UInt32.max)
    data.append(Data("WAVEfmt ".utf8))
    append(UInt32(16))  // fmt chunk size
    append(UInt16(1))  // PCM
    append(UInt16(1))  // mono
    append(UInt32(sampleRate))
    append(UInt32(sampleRate * 2))  // byte rate
    append(UInt16(2))  // block align
    append(UInt16(16))  // bits per sample
    data.append(Data("data".utf8))
    append(UInt32.max)
    return data
  }

  private func remove(_ client: StreamClient) {
    guard streamClients.removeValue(forKey: ObjectIdentifier(client)) != nil else { return }
    viewerCountChanged()
  }

  // MARK: - Helpers

  /// JPEG for the newest frame, encoded at most once per frame.
  private func latestFrameJPEG() -> Data? {
    if encodedSeq != latestSeq, let image = latestImage {
      latestJPEG = image.jpegData(compressionQuality: Self.jpegQuality)
      encodedSeq = latestSeq
    }
    return latestJPEG
  }

  private func statusJSON() -> Data {
    let body: [String: Any] = [
      "app": "GlassCast",
      "platform": "ios",
      "state": status.state,
      "fps": (status.fps * 10).rounded() / 10,
      "width": status.width,
      "height": status.height,
      "clients": streamClients.count,
      "audio": audioAvailable,
    ]
    return (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
  }

  private func stopListener() {
    listener?.cancel()
    listener = nil
    for client in streamClients.values {
      client.connection.cancel()
    }
    streamClients.removeAll()
    for client in audioClients.values {
      client.connection.cancel()
    }
    audioClients.removeAll()
    viewerCountChanged()
  }

  private func viewerCountChanged() {
    let count = streamClients.count
    DispatchQueue.main.async { [weak self] in self?.onViewerCountChange?(count) }
  }

  private func report(_ message: String) {
    DispatchQueue.main.async { [weak self] in self?.onError?(message) }
  }
}

/// One open `/stream` connection. Only touched on the server queue.
private final class StreamClient {
  let connection: NWConnection
  var isSending = false
  var sentSeq = -1

  init(connection: NWConnection) {
    self.connection = connection
  }
}

/// One open `/audio` connection. Only touched on the server queue.
private final class AudioClient {
  let connection: NWConnection
  var isSending = false
  var pending = Data()

  init(connection: NWConnection) {
    self.connection = connection
  }
}
