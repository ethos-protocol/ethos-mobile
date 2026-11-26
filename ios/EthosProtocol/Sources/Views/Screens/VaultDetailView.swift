import SwiftUI

// MARK: - Vault Detail

struct VaultDetailView: View {
    /// How often `refreshTTLPeriodically` polls the server, in nanoseconds (60 s).
    static let ttlRefreshInterval: UInt64 = 60_000_000_000

    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @State private var isCheckingIn = false
    @State private var biometricError: String?
    @State private var show2FASetup = false
    @State private var show2FAVerify = false
    @State private var show2FASwitch = false
    @State private var twoFactorStatus: TwoFactorStatus?
    @State private var twoFactorLoadError: String?
    @State private var showDeposit = false
    @State private var showWithdraw = false
    @State private var showManageBeneficiary = false
    @State private var showNotificationPreferences = false
    /// Server-anchored TTL baseline, reconciled on every poll and `vault_updated`
    /// push (#221, #223); the server value always wins on conflict.
    @State private var ttlCountdown: TTLCountdown? = nil
    /// Locally ticked display value, updated every second by `tickTTLLocally`
    /// between the (up to 60 s apart) reconciliations above.
    @State private var ttlRemaining: UInt64? = nil

    var body: some View {
        List {
            Section("Overview") {
                LabeledContent("Balance", value: vault.formattedBalance)
                LabeledContent("Status", value: vault.status.rawValue.capitalized)
                HStack {
                    Text("Beneficiary")
                    Spacer()
                    CopyableIDView(fullID: vault.beneficiary, displayLength: 16)
                }
                if let ttl = ttlRemaining {
                    LabeledContent("TTL Remaining", value: formatDuration(ttl))
                }
                // Issue #472
                NavigationLink(destination: VaultQRCodeView(vault: vault)) {
                    Label("Share via QR Code", systemImage: "qrcode")
                }
            }

            Section("Two-Factor Authentication") {
                if let error = twoFactorLoadError {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Failed to load 2FA status")
                            .foregroundStyle(.red)
                            .font(.subheadline.bold())
                        Text(error)
                            .foregroundStyle(.secondary)
                            .font(.caption)
                        Button(action: { Task { await load2FAStatus() } }) {
                            Label("Retry", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.bordered)
                    }
                } else if let status = twoFactorStatus {
                    if status.enabled {
                        LabeledContent("2FA", value: status.method.map { "\($0.rawValue.uppercased())" } ?? "Enabled")
                        LabeledContent("Verified", value: status.verified ? "Yes" : "No")
                        if !status.verified {
                            Button("Verify Now") { show2FAVerify = true }
                        }
                        // #225: Switch method without disabling first.
                        if status.availableMethods.count > 1 {
                            Button("Switch Method") { show2FASwitch = true }
                        }
                        Button("Disable 2FA", role: .destructive) { disable2FA() }
                    } else {
                        Button("Enable 2FA") { show2FASetup = true }
                    }
                } else {
                    ProgressView()
                        .respectsReduceMotion()
                        .task { await load2FAStatus() }
                }
            }

            Section {
                Button(action: checkIn) {
                    Label(isCheckingIn ? "Checking in…" : "Check In Now", systemImage: "checkmark.circle.fill")
                }
                .disabled(isCheckingIn || vault.status != .active)
                if let error = biometricError {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
                if vaultStore.queuedCheckInCount > 0 {
                    Label("Check-in queued — will retry automatically when back online", systemImage: "clock.arrow.circlepath")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }

            Section("Funds") {
                Button(action: { showDeposit = true }) {
                    Label("Deposit", systemImage: "plus.circle.fill")
                }
                Button(action: { showWithdraw = true }) {
                    Label("Withdraw", systemImage: "arrow.up.circle.fill")
                }
                .disabled(vault.status != .active)
            }

            Section {
                Button(action: { showManageBeneficiary = true }) {
                    Label("Manage Beneficiary", systemImage: "person.2.fill")
                }
            }

            Section {
                Button(action: { showNotificationPreferences = true }) {
                    Label("Expiry Notifications", systemImage: "bell.fill")
                }
            }

            // Issue #479: local action history and frequency stats.
            Section {
                NavigationLink(destination: VaultHistoryView(vault: vault)) {
                    Label("Action History", systemImage: "clock.arrow.circlepath")
                }
            }
        }
        .navigationTitle("Vault")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { VaultActionLog.record(.view, for: vault.id) }
        // `.task` auto-cancels when the view disappears, so this polling loop
        // (and the in-flight `getTTL` request it may be awaiting) stops cleanly
        // on navigating away instead of continuing to run in the background.
        // The real-time event subscription (#20) rides along on the same task:
        // opened before the loop starts, torn down via `defer` once it exits.
        .task {
            vaultStore.subscribeToEvents(vaultID: vault.id, socket: VaultEventSocket(baseURL: APIClient.shared.baseURL))
            defer { vaultStore.unsubscribeFromEvents() }
            async let polling: () = refreshTTLPeriodically()
            async let ticking: () = tickTTLLocally()
            _ = await (polling, ticking)
        }
        // The socket delivers `vault_updated` into `vaultStore.vaults` (see
        // subscribeToEvents), not directly to this view's local TTL state — pick
        // it up here so a push reconciles the countdown exactly like a poll does,
        // per the shared rule in shared/api-contract.md (#223).
        .onChange(of: vaultStore.vaults) { _, updated in
            if let ttl = updated.first(where: { $0.id == vault.id })?.ttlRemaining {
                reconcileTTL(ttl)
            }
        }
        .sheet(isPresented: $show2FASetup) {
            // #227: Pass available methods so setup only offers what the server allows.
            TwoFactorSetupView(
                vaultID: vault.id,
                availableMethods: twoFactorStatus?.availableMethods ?? TwoFactorMethod.allCases
            )
        }
        .sheet(isPresented: $show2FAVerify) {
            TwoFactorVerifyView(
                vaultID: vault.id,
                method: twoFactorStatus?.method ?? .totp,
                provisioningUri: nil,
                secret: nil,
                onVerified: { Task { await load2FAStatus() } }
            )
        }
        // #225: Switch method sheet — new method set up before old one is torn down.
        .sheet(isPresented: $show2FASwitch) {
            if let status = twoFactorStatus, let currentMethod = status.method {
                TwoFactorSwitchView(
                    vaultID: vault.id,
                    currentMethod: currentMethod,
                    availableMethods: status.availableMethods
                )
            }
        }
        .sheet(isPresented: $showDeposit) {
            DepositView(vault: vault)
        }
        .sheet(isPresented: $showWithdraw) {
            NavigationStack { WithdrawView(vault: vault) }
        }
        .sheet(isPresented: $showManageBeneficiary) {
            NavigationStack { ManageBeneficiaryView(vault: vault) }
        }
        .sheet(isPresented: $showNotificationPreferences) {
            NavigationStack { VaultNotificationPreferencesView(vaultID: vault.id) }
        }
        .trackScreen("VaultDetail")
    }

    private func load2FAStatus() async {
        twoFactorLoadError = nil
        do {
            let status = try await APIClient.shared.get2FAStatus(vaultID: vault.id)
            ifNotCancelled { twoFactorStatus = status }
        } catch {
            ifNotCancelled {
                twoFactorLoadError = error.localizedDescription
                twoFactorStatus = nil
            }
        }
    }

    private func refreshTTL() async {
        guard let ttl = try? await APIClient.shared.getTTL(vaultID: vault.id) else { return }
        ifNotCancelled { reconcileTTL(ttl) }
    }

    /// Polls the server TTL every 60 s for as long as the view is on screen.
    /// The `.task` modifier that calls this cancels it automatically on disappear.
    private func refreshTTLPeriodically() async {
        if let seed = vault.ttlRemaining { reconcileTTL(seed) }   // seed with value from vault list
        while !Task.isCancelled {
            await refreshTTL()
            try? await Task.sleep(nanoseconds: Self.ttlRefreshInterval)
        }
    }

    /// Ticks the displayed TTL down once a second between server reconciliations,
    /// so the countdown never visibly freezes while waiting on the next 60 s poll
    /// or `vault_updated` push (#221).
    private func tickTTLLocally() async {
        while !Task.isCancelled {
            if let countdown = ttlCountdown {
                ifNotCancelled { ttlRemaining = countdown.remaining() }
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    /// Applies a fresh server TTL value — from either a poll or a `vault_updated`
    /// push — as the new countdown baseline. The server value always wins over
    /// whatever the local tick had drifted to (#221, #223).
    private func reconcileTTL(_ serverValue: UInt64) {
        if ttlCountdown == nil {
            ttlCountdown = TTLCountdown(serverValue: serverValue)
        } else {
            ttlCountdown?.reconcile(serverValue: serverValue)
        }
        ttlRemaining = serverValue
    }

    // Not cancelled from `.onDisappear`: unlike the read-only TTL/2FA-status
    // polling above, this is a mutating request already in flight — cancelling
    // the Task wouldn't stop the server from processing it, it would just make
    // the app forget whether it succeeded. `ifNotCancelled` still guards the
    // state write in case cancellation reaches here some other way.
    //
    // #120: Biometric gate is enforced via Disable2FACoordinator before the API
    // call.  Disabling 2FA is at least as security-sensitive as a check-in — it
    // must require explicit user confirmation.
    private func disable2FA() {
        Task {
            do {
                try await Disable2FACoordinator().run(vaultID: vault.id)
                if !Task.isCancelled { await load2FAStatus() }
            } catch {
                ifNotCancelled { biometricError = error.localizedDescription }
            }
        }
    }

    private func checkIn() {
        biometricError = nil
        isCheckingIn = true
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm vault check-in")
                if !Task.isCancelled {
                    await vaultStore.checkIn(vault: vault)
                    HapticFeedbackService.shared.success()
                }
            } catch {
                ifNotCancelled {
                    HapticFeedbackService.shared.error()
                    biometricError = error.localizedDescription
                }
            }
            ifNotCancelled { isCheckingIn = false }
        }
    }

    private func formatDuration(_ seconds: UInt64) -> String {
        DateTimeFormatter.shared.formatDurationInSeconds(seconds)
    }
}
