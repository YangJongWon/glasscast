import SwiftUI
import VisionKit

/// An image with Live Text and Visual Look Up: select and copy or translate text, open
/// links and QR codes, and look up plants, animals, landmarks and more. Runs on device
/// through VisionKit, with no API key.
struct LiveTextImage: UIViewRepresentable {
  let image: UIImage

  static var isSupported: Bool { ImageAnalyzer.isSupported }

  func makeUIView(context: Context) -> UIImageView {
    let view = UIImageView()
    view.contentMode = .scaleAspectFit
    view.isUserInteractionEnabled = true
    view.addInteraction(context.coordinator.interaction)
    view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
    return view
  }

  func updateUIView(_ view: UIImageView, context: Context) {
    guard view.image !== image else { return }
    view.image = image
    context.coordinator.analyze(image)
  }

  func makeCoordinator() -> Coordinator { Coordinator() }

  @MainActor
  final class Coordinator {
    let interaction = ImageAnalysisInteraction()
    private let analyzer = ImageAnalyzer()
    private var task: Task<Void, Never>?

    func analyze(_ image: UIImage) {
      interaction.analysis = nil
      task?.cancel()
      task = Task {
        let configuration = ImageAnalyzer.Configuration([.text, .visualLookUp, .machineReadableCode])
        guard let analysis = try? await analyzer.analyze(image, configuration: configuration),
          !Task.isCancelled
        else { return }
        interaction.analysis = analysis
        interaction.preferredInteractionTypes = .automatic
        // Shows the Live Text and Look Up buttons in the corner.
        interaction.setSupplementaryInterfaceHidden(false, animated: true)
      }
    }
  }
}

/// A frozen live-view frame with Live Text and Visual Look Up.
struct LookUpView: View {
  let image: UIImage
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      ZStack {
        Color.black.ignoresSafeArea()
        LiveTextImage(image: image)
      }
      .navigationTitle("Look up")
      .navigationBarTitleDisplayMode(.inline)
      .toolbarColorScheme(.dark, for: .navigationBar)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { dismiss() }
        }
      }
    }
  }
}
