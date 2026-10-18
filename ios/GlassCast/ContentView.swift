import SwiftUI

struct ContentView: View {
  @Bindable var manager: WearablesManager

  var body: some View {
    Group {
      if manager.isRegistered {
        LiveView(manager: manager)
      } else {
        ConnectView(manager: manager)
      }
    }
    .alert(
      "Something went wrong",
      isPresented: Binding(
        get: { manager.errorMessage != nil },
        set: { if !$0 { manager.errorMessage = nil } })
    ) {
      Button("OK") { manager.errorMessage = nil }
    } message: {
      Text(manager.errorMessage ?? "")
    }
  }
}

/// Shown until the app is registered with the glasses through the Meta AI app.
struct ConnectView: View {
  var manager: WearablesManager

  var body: some View {
    VStack(spacing: 24) {
      Spacer()
      Image(systemName: "eyeglasses")
        .font(.system(size: 72, weight: .light))
        .foregroundStyle(.tint)
      VStack(spacing: 8) {
        Text("GlassCast")
          .font(.largeTitle.bold())
        Text("See what your Ray-Ban Meta glasses see, live on this phone and on any computer on the same Wi-Fi.")
          .multilineTextAlignment(.center)
          .foregroundStyle(.secondary)
      }
      Spacer()
      VStack(spacing: 12) {
        Button {
          manager.connectGlasses()
        } label: {
          Text(manager.registrationState == .registering ? "Connecting…" : "Connect my glasses")
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(manager.registrationState == .registering)

        Text("You'll be sent to the Meta AI app to approve the connection.")
          .font(.footnote)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
      }
    }
    .padding(24)
  }
}
