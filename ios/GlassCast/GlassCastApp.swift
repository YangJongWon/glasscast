import MWDATCore
import SwiftUI

@main
struct GlassCastApp: App {
  @State private var manager: WearablesManager
  @Environment(\.scenePhase) private var scenePhase

  init() {
    do {
      try Wearables.configure()
    } catch {
      NSLog("[GlassCast] Failed to configure Wearables SDK: \(error)")
    }
    _manager = State(initialValue: WearablesManager())
  }

  var body: some Scene {
    WindowGroup {
      ContentView(manager: manager)
        // Meta AI returns here after registration and permission prompts.
        .onOpenURL { url in manager.handleCallback(url) }
        // iOS tears the listener down while suspended; bring it back on return.
        .onChange(of: scenePhase) { _, phase in
          if phase == .active { manager.server.start() }
        }
    }
  }
}
