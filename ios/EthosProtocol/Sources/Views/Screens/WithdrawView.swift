import SwiftUI

// MARK: - Withdraw

struct WithdrawView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var amountText = ""
    @State private var isWithdrawing = false
    @State private var error: String?

    private var amountStroops: Int64? { VaultAmount.parseStroops(amountText) }

    private var isAmountValid: Bool {
        guard let amount = amountStroops else { return false }
        return VaultAmount.hasSufficientBalance(amount: amount, vaultBalance: vault.balance)
    }

    var body: some View {
        Form {
            Section("Vault") {
                LabeledContent("Available Balance", value: vault.formattedBalance)
            }
            Section("Amount") {
                TextField("XLM amount", text: $amountText)
                    .keyboardType(.decimalPad)
                if let amount = amountStroops, amount > vault.balance {
                    Text("Amount exceeds available balance.").foregroundStyle(.red).font(.caption)
                }
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .navigationTitle("Withdraw")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isWithdrawing ? "Withdrawing…" : "Withdraw") { withdraw() }
                    .disabled(!isAmountValid || isWithdrawing)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .overlay { if isWithdrawing { ProgressView().respectsReduceMotion() } }
    }

    private func withdraw() {
        guard let amount = amountStroops else { return }
        isWithdrawing = true; error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm vault withdrawal")
                await vaultStore.withdraw(vault: vault, amount: amount)
                if let storeError = vaultStore.error {
                    error = storeError.message
                } else {
                    dismiss()
                }
            } catch {
                self.error = error.localizedDescription
            }
            isWithdrawing = false
        }
    }
}
