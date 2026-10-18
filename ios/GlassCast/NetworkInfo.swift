import Darwin
import Foundation

enum NetworkInfo {
  /// The phone's IPv4 address on Wi-Fi (`en0`), or `nil` when Wi-Fi is off.
  static func wifiIPv4Address() -> String? {
    var addresses: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&addresses) == 0, let first = addresses else { return nil }
    defer { freeifaddrs(addresses) }

    for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
      let interface = pointer.pointee
      guard let address = interface.ifa_addr,
        address.pointee.sa_family == UInt8(AF_INET),
        String(cString: interface.ifa_name) == "en0"
      else { continue }

      var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
      if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
        return String(cString: host)
      }
    }
    return nil
  }

  /// The URL a desktop on the same network opens to watch the stream.
  static func viewerURL() -> String? {
    wifiIPv4Address().map { "http://\($0):\(MJPEGServer.port)" }
  }
}
