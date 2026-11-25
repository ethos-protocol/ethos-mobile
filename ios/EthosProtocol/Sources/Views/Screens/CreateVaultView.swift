import SwiftUI

// MARK: - Create Vault

struct CreateVaultView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var beneficiary = ""
    @State private var intervalDays = 30.0
    @State private var isCreating = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Beneficiary") {
                    TextField("Stellar address", text: $beneficiary)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                    if !beneficiary.isEmpty && !isBeneficiaryValid {
                        Text("Enter a valid Stellar address (56 characters, starting with G).")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                Section("Check-in Interval") {
                    Slider(value: $intervalDays, in: 1...365, step: 1)
                    Text("\(Int(intervalDays)) days").foregroundStyle(.secondary)
                }

                if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
            }
            .navigationTitle("New Vault")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { create() }.disabled(!isBeneficiaryValid || isCreating)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var isBeneficiaryValid: Bool {
        StellarAddress.isValidPublicKey(beneficiary)
    }

    private func create() {
        guard isBeneficiaryValid else { return }
        isCreating = true
        Task {
            do {
                let interval = UInt64(intervalDays * 86_400)
                let vault = try await APIClient.shared.createVault(beneficiary: beneficiary, checkInInterval: interval)
                if let credentialID = KeychainService.shared.loadCredentialID() {
                    ICloudSyncService.shared.save(vaultID: vault.id, credentialID: credentialID)
                }
                await vaultStore.load()
                dismiss()
            } catch { self.error = error.localizedDescription }
            isCreating = false
        }
    }
}
