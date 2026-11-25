import SwiftUI

// MARK: - PIN Setup

struct PINSetupView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var pinInput = ""
    @State private var pinConfirm = ""
    @State private var error: String?
    @State private var isSetupInProgress = false

    var pinsMatch: Bool {
        !pinInput.isEmpty && pinInput == pinConfirm
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.blue)
                    Text("Set up Your PIN").font(.title.bold())
                    Text("This 6-digit PIN will be used if biometric authentication is unavailable")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.vertical, 16)

                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Enter 6-digit PIN").font(.caption.bold()).foregroundStyle(.secondary)
                        SecureField("PIN", text: $pinInput)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .font(.system(size: 18, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 150)
                            .padding(12)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                            .disabled(isSetupInProgress)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Confirm PIN").font(.caption.bold()).foregroundStyle(.secondary)
                        SecureField("Confirm PIN", text: $pinConfirm)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.numberPad)
                            .font(.system(size: 18, weight: .medium, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: 150)
                            .padding(12)
                            .background(Color(.systemGray6))
                            .cornerRadius(8)
                            .disabled(isSetupInProgress)
                    }

                    if let error {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    if !pinInput.isEmpty && !pinConfirm.isEmpty && pinInput != pinConfirm {
                        Text("PINs do not match")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                Spacer()

                VStack(spacing: 12) {
                    Button(action: setupPIN) {
                        Label(isSetupInProgress ? "Setting up…" : "Continue", systemImage: "checkmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!pinsMatch || isSetupInProgress)

                    Button(action: { dismiss() }) {
                        Text("Skip for Now")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isSetupInProgress)
                }
            }
            .padding(32)
        }
    }

    private func setupPIN() {
        guard !isSetupInProgress else { return }
        isSetupInProgress = true
        error = nil

        do {
            try PINAuthenticationService.shared.setupPIN(pinInput)
            authStore.showPINSetup = false
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }

        isSetupInProgress = false
    }
}
