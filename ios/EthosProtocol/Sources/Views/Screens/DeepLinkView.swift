import SwiftUI

// MARK: - Deep Link Views

struct DeepLinkView: View {
    let link: UniversalLinkRouter.DeepLink
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            switch link {
            case .vaultInvitation(let vaultID):
                VaultInvitationView(vaultID: vaultID)
            case .beneficiaryAcceptance(let vaultID, let token):
                BeneficiaryAcceptanceView(vaultID: vaultID, token: token)
            case .vaultAction(let vaultID, let action):
                VaultActionDeepLinkView(vaultID: vaultID, action: action)
            }
        }
    }
}

struct VaultInvitationView: View {
    let vaultID: String
    @Environment(\.dismiss) var dismiss

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "envelope.open.fill")
                .font(.system(size: 56))
                .foregroundStyle(.blue)
                .accessibilityHidden(true)
            Text("Vault Invitation").font(.title.bold())
            VStack(spacing: 8) {
                Text("You have been invited to a vault.")
                    .foregroundStyle(.secondary)
                CopyableIDView(fullID: vaultID, displayLength: 16)
            }
            .multilineTextAlignment(.center)
            Button("Open App") { dismiss() }
                .buttonStyle(.borderedProminent)
        }
        .padding(32)
        .navigationTitle("Invitation")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }
}

struct VaultActionDeepLinkView: View {
    let vaultID: String
    let action: UniversalLinkRouter.VaultAction
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var isProcessing = false
    @State private var error: String?
    @State private var isLoading = false
    @State private var hasAttemptedLoad = false
    @State private var showWithdrawSheet = false
    @State private var showManageBeneficiary = false

    private var vault: Vault? { vaultStore.vaults.first { $0.id == vaultID } }

    var body: some View {
        Group {
            if isLoading && vault == nil {
                ProgressView("Loading vault…")
                    .respectsReduceMotion()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                switch action {
                case .viewDetails:
                    if let vault {
                        VaultDetailView(vault: vault)
                    } else {
                        vaultNotFoundContent
                    }
                case .checkIn:
                    actionContent(
                        title: "Check In",
                        systemImage: "checkmark.circle.fill",
                        description: "Confirm check-in for vault \(vaultID.prefix(16))…"
                    ) {
                        guard let vault else { error = "Vault not found"; return }
                        isProcessing = true
                        error = nil
                        Task {
                            do {
                                try await BiometricService.shared.authenticate(reason: "Confirm vault check-in")
                                await vaultStore.checkIn(vault: vault)
                                dismiss()
                            } catch let checkInError {
                                self.error = checkInError.localizedDescription
                            }
                            isProcessing = false
                        }
                    }
                case .withdraw:
                    actionContent(
                        title: "Withdraw",
                        systemImage: "arrow.up.circle.fill",
                        description: "Withdraw funds from vault \(vaultID.prefix(16))…"
                    ) {
                        if let vault { showWithdrawSheet = true }
                        else { error = "Vault not found" }
                    }
                case .manageBeneficiary:
                    actionContent(
                        title: "Manage Beneficiary",
                        systemImage: "person.2.fill",
                        description: "Update the beneficiary for vault \(vaultID.prefix(16))…"
                    ) {
                        guard vault != nil else { error = "Vault not found"; return }
                        showManageBeneficiary = true
                    }
                }
            }
        }
        .task {
            await loadVaultIfNeeded()
        }
        .sheet(isPresented: $showWithdrawSheet) {
            if let vault {
                NavigationStack { WithdrawView(vault: vault) }
            }
        }
        .sheet(isPresented: $showManageBeneficiary) {
            if let vault {
                NavigationStack { ManageBeneficiaryView(vault: vault) }
            }
        }
    }

    private func loadVaultIfNeeded() async {
        guard !hasAttemptedLoad else { return }
        hasAttemptedLoad = true

        if vault == nil {
            isLoading = true
            await vaultStore.load()
            isLoading = false
        }
    }

    private var vaultNotFoundContent: some View {
        ContentUnavailableView(
            "Vault Not Found",
            systemImage: "lock.slash",
            description: Text("Vault \(vaultID.prefix(16))… could not be loaded.")
        )
        .navigationTitle("Vault")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }

    private func actionContent(
        title: String,
        systemImage: String,
        description: String,
        onAction: @escaping () -> Void
    ) -> some View {
        VStack(spacing: 24) {
            Image(systemName: systemImage)
                .font(.system(size: 56))
                .foregroundStyle(.blue)
                .accessibilityLabel(title)
                .accessibilityHidden(false)
            Text(title).font(.title.bold())
            Text(description).multilineTextAlignment(.center).foregroundStyle(.secondary)
            if let error { Text(error).foregroundStyle(.red).font(.caption) }
            Button(action: onAction) {
                Text(isProcessing ? "Processing…" : title).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isProcessing || (action == .checkIn && vault == nil))
        }
        .padding(32)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }
}

struct BeneficiaryAcceptanceView: View {
    let vaultID: String
    let token: String
    @Environment(\.dismiss) var dismiss
    @State private var isAccepting = false
    @State private var error: String?
    @State private var accepted = false

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 56))
                .foregroundStyle(.green)
                .accessibilityHidden(true)
            Text("Accept Beneficiary Role").font(.title.bold())
            VStack(spacing: 8) {
                Text("You have been nominated as a beneficiary for vault:")
                    .foregroundStyle(.secondary)
                CopyableIDView(fullID: vaultID, displayLength: 16)
            }
            .multilineTextAlignment(.center)
            if accepted {
                Label("Accepted", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                if let error { Text(error).foregroundStyle(.red).font(.caption) }
                Button(action: accept) {
                    Label(isAccepting ? "Accepting…" : "Accept", systemImage: "hand.thumbsup.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isAccepting)
            }
        }
        .padding(32)
        .navigationTitle("Beneficiary Acceptance")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Dismiss") { dismiss() } } }
    }

    private func accept() {
        isAccepting = true
        Task {
            do {
                try await APIClient.shared.acceptBeneficiary(vaultID: vaultID, token: token)
                if let credentialID = KeychainService.shared.loadCredentialID() {
                    ICloudSyncService.shared.save(vaultID: vaultID, credentialID: credentialID)
                }
                accepted = true
            } catch {
                self.error = error.localizedDescription
            }
            isAccepting = false
        }
    }
}
