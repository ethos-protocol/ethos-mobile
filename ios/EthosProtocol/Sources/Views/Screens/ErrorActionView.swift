import SwiftUI

// MARK: - Error Presentation

/// Renders an ErrorPresentation's message + recovery suggestion, plus a "Try
/// Again" button (when the error is retryable and a retry action is supplied)
/// and a "Contact Support" mail link (when the error warrants escalating).
struct ErrorActionView: View {
    let error: ErrorPresentation
    var retry: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(error.message)
                .foregroundStyle(.red)
                .font(.caption)
            if let suggestion = error.recoverySuggestion {
                Text(suggestion)
                    .foregroundStyle(.secondary)
                    .font(.caption2)
            }
            HStack(spacing: 16) {
                if error.showsRetry, let retry {
                    Button("Try Again", action: retry)
                        .font(.caption.bold())
                }
                if error.showsContactSupport {
                    Link("Contact Support", destination: SupportContact.mailURL(errorMessage: error.message))
                        .font(.caption.bold())
                }
            }
        }
        .multilineTextAlignment(.leading)
    }
}

enum SupportContact {
    static let email = "support@ethos-protocol.app"

    static func mailURL(errorMessage: String) -> URL {
        var components = URLComponents(string: "mailto:\(email)")!
        components.queryItems = [
            URLQueryItem(name: "subject", value: "Ethos-Protocol app issue"),
            URLQueryItem(name: "body", value: "I ran into this error:\n\(errorMessage)")
        ]
        return components.url ?? URL(string: "mailto:\(email)")!
    }
}
