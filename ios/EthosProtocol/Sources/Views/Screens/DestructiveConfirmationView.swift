import SwiftUI

// MARK: - Destructive Confirmation (#220)

/// Shared confirmation UI for any irreversible, destructive vault action
/// (delete, archive, …) on either platform. The confirm button stays disabled
/// until the user types `requiredText` (typically the vault's own name/ID)
/// exactly — a plain Yes/No tap is not enough given the financial and
/// beneficiary implications of getting this wrong. `onConfirm` is only ever invoked once `DestructiveConfirmation.isConfirmed` is true.
struct DestructiveConfirmationView: View {
    let title: String
    let message: String
    let requiredText: String
    var confirmTitle: String = "Delete"
    let onConfirm: () -> Void

    @State private var confirmation: DestructiveConfirmation

    init(title: String, message: String, requiredText: String,
         confirmTitle: String = "Delete", onConfirm: @escaping () -> Void) {
        self.title = title
        self.message = message
        self.requiredText = requiredText
        self.confirmTitle = confirmTitle
        self.onConfirm = onConfirm
        _confirmation = State(initialValue: DestructiveConfirmation(requiredText: requiredText))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("Type \"\(requiredText)\" to confirm.")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(requiredText, text: $confirmation.enteredText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            Button(confirmTitle, role: .destructive) {
                // Guarded via confirmIfMatched, not just `.disabled` below — `.disabled`
                // only stops a tap from reaching this closure through the button's own
                // gesture recognizer; it is not what makes this safe.
                confirmation.confirmIfMatched(onConfirm)
            }
            .disabled(!confirmation.isConfirmed)
            .buttonStyle(.borderedProminent)
            .tint(.red)
        }
        .padding()
    }
}
