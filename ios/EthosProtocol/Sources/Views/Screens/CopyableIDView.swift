import SwiftUI

// MARK: - Copyable ID View

struct CopyableIDView: View {
    let fullID: String
    let displayLength: Int
    @State private var showCopiedFeedback = false

    var displayID: String {
        String(fullID.prefix(displayLength)) + "…"
    }

    var body: some View {
        HStack(spacing: 8) {
            Text(displayID).font(.headline)
            Button(action: copyToClipboard) {
                Image(systemName: "doc.on.doc")
                    .font(.caption)
                    .foregroundStyle(.blue)
            }
            .accessibilityLabel("Copy full ID")
        }
        .contentShape(Rectangle())
        .contextMenu {
            Button(action: copyToClipboard) {
                Label("Copy Full ID", systemImage: "doc.on.doc")
            }
        }
    }

    private func copyToClipboard() {
        SensitiveClipboard.copy(fullID)
        showCopiedFeedback = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            showCopiedFeedback = false
        }
    }
}
