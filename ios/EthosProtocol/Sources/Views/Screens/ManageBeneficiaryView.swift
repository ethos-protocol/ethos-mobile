import SwiftUI

// MARK: - Manage Beneficiary

struct ManageBeneficiaryView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var newBeneficiary = ""
    @State private var showConfirmation = false
    @State private var isUpdating = false
    @State private var error: String?
    @State private var updated = false

    private var isAddressValid: Bool {
        BeneficiaryUpdate.isValidNewBeneficiary(newBeneficiary, currentBeneficiary: vault.beneficiary)
    }

    var body: some View {
        Group {
            if showConfirmation {
                confirmationContent
            } else {
                formContent
            }
        }
        .navigationTitle("Manage Beneficiary")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var formContent: some View {
        Form {
            Section("Current Beneficiary") {
                Text(vault.beneficiary).font(.system(.body, design: .monospaced))
            }
            Section("New Beneficiary") {
                TextField("Stellar address", text: $newBeneficiary)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(.body, design: .monospaced))
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Continue") { showConfirmation = true }.disabled(!isAddressValid)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private var confirmationContent: some View {
        VStack(spacing: 24) {
            Image(systemName: "person.2.fill").font(.system(size: 56)).foregroundStyle(.blue)
            Text("Confirm New Beneficiary").font(.title.bold())
            VStack(alignment: .leading, spacing: 8) {
                Text("From").foregroundStyle(.secondary).font(.caption)
                Text(vault.beneficiary).font(.system(.body, design: .monospaced))
                Text("To").foregroundStyle(.secondary).font(.caption)
                Text(newBeneficiary).font(.system(.body, design: .monospaced))
            }
            if updated {
                Label("Beneficiary Updated", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                if let error { Text(error).foregroundStyle(.red).font(.caption) }
                Button(action: confirm) {
                    Text(isUpdating ? "Updating…" : "Confirm Change").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isUpdating)
                Button("Back") { showConfirmation = false }.disabled(isUpdating)
            }
        }
        .padding(32)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
    }

    private func confirm() {
        isUpdating = true; error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm beneficiary change")
                await vaultStore.updateBeneficiary(vault: vault, newBeneficiary: newBeneficiary)
                if let storeError = vaultStore.error {
                    error = storeError.message
                } else {
                    updated = true
                }
            } catch {
                self.error = error.localizedDescription
            }
            isUpdating = false
        }
    }
}
