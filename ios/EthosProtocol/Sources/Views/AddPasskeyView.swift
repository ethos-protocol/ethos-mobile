import SwiftUI

/// Lets an already-authenticated user register an additional passkey (#207) — e.g. for a
/// second device — without going through the account-recovery flow RecoverAccessView drives
/// for a signed-out user. Presented as a sheet from PasskeyManagementView.
struct AddPasskeyView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    let onAdded: (PasskeyCredential) -> Void

    @State private var username = ""

    private var validationResult: Result<String, UsernameValidation.ValidationError> {
        UsernameValidation.validate(username)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if case .failure(let validationError) = validationResult, !username.isEmpty {
                        Text(validationError.errorDescription ?? "Invalid username")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("Confirm Your Username")
                } footer: {
                    Text("You'll be prompted for Face ID or Touch ID to create the new passkey on this device.")
                }
                if let error = authStore.error {
                    Section { Text(error.message).font(.caption).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Add Another Passkey")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        guard case .success(let validUsername) = validationResult else { return }
                        Task {
                            if let credential = await authStore.addPasskey(username: validUsername) {
                                onAdded(credential)
                                dismiss()
                            }
                        }
                    }
                    .disabled(!isValid || authStore.isLoading)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var isValid: Bool {
        if case .success = validationResult { return true }
        return false
    }
}
