import CoreImage.CIFilterBuiltins
import SwiftUI

struct LiveView: View {
  @Bindable var manager: WearablesManager
  @State private var showShareSheet = false

  var body: some View {
    ZStack {
      Color.black.ignoresSafeArea()

      if let frame = manager.frame {
        Image(uiImage: frame)
          .resizable()
          .scaledToFit()
          .ignoresSafeArea()
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
        StatusBar(manager: manager)
        Spacer()
        Controls(manager: manager, showShareSheet: $showShareSheet)
      }
      .padding(16)
    }
    .sheet(isPresented: $showShareSheet) {
      ShareSheet(manager: manager)
        .presentationDetents([.medium])
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
      if manager.viewerCount > 0 {
        Label("\(manager.viewerCount)", systemImage: "desktopcomputer")
      }
      Menu {
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

private struct Controls: View {
  @Bindable var manager: WearablesManager
  @Binding var showShareSheet: Bool

  var body: some View {
    VStack(spacing: 12) {
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
          .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(manager.wantsStream ? .red : .accentColor)

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
  }
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
        Text("Open this address in a browser on the same Wi-Fi. For OBS or VLC use \(url)/stream")
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
