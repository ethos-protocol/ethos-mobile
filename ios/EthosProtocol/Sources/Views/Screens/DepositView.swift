import SwiftUI

// MARK: - Deposit

struct DepositView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var amountText = ""
    @State private var isDepositing = false
    @State private var error: String?

    private var amountStroops: Int64? { VaultAmount.parseStroops(amountText) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Vault") {
                    LabeledContent("Current Balance", value: vault.formattedBalance)
                }
                Section("Amount") {
                    TextField("XLM amount", text: $amountText)
                        .keyboardType(.decimalPad)
                }
                if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
            }
            .navigationTitle("Deposit")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(isDepositing ? "Depositing…" : "Deposit") { deposit() }
                        .disabled(amountStroops == nil || isDepositing)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .overlay { if isDepositing { ProgressView().respectsReduceMotion() } }
        }
    }

    private func deposit() {
        guard let amount = amountStroops else { return }
        isDepositing = true; error = nil
        Task {
            await vaultStore.deposit(vault: vault, amount: amount)
            if let storeError = vaultStore.error {
                error = storeError.message
            } else {
                dismiss()
            }
            isDepositing = false
        }
    }
}
