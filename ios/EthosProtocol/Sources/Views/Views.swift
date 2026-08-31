import SwiftUI

struct RootView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if authStore.isAuthenticated {
                VaultListView()
            } else {
                AuthView()
            }

            // Re-lock gate: shown atop the vault list after the app has spent long enough
            // in the background (AuthStore.handleScenePhaseChange), independent of privacy
            // overlay below which covers *every* backgrounding regardless of duration.
            if authStore.isAuthenticated && authStore.isLocked {
                LockScreenView()
            }

            // Covers the vault list/balances the instant the app stops being active, so
            // the system's app-switcher snapshot never captures sensitive content.
            if authStore.isAuthenticated && scenePhase != .active {
                PrivacyOverlayView()
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            authStore.handleScenePhaseChange(newPhase)
        }
    }
}

private struct PrivacyOverlayView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(.blue)
        }
        .ignoresSafeArea()
        .transition(.opacity)
    }
}

private struct LockScreenView: View {
    @EnvironmentObject var authStore: AuthStore
    @State private var error: String?
    @State private var isUnlocking = false

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 24) {
                Image(systemName: "faceid")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)
                Text("Ethos-Protocol Locked").font(.title.bold())
                if let error {
                    Text(error).foregroundStyle(.red).font(.caption).multilineTextAlignment(.center)
                }
                Button(action: unlock) {
                    Label(isUnlocking ? "Unlocking…" : "Unlock", systemImage: "faceid")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isUnlocking)
            }
            .padding(32)
        }
        .onAppear(perform: unlock)
    }

    private func unlock() {
        guard !isUnlocking else { return }
        isUnlocking = true
        error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Unlock Ethos-Protocol")
                authStore.isLocked = false
            } catch {
                self.error = error.localizedDescription
            }
            isUnlocking = false
        }
    }
}

// MARK: - Copyable ID View

struct CopyableIDView: View {
    let fullID: String
    let displayLength: Int
    @State private var showCopiedFeedback = false

    var displayID: String {
        String(fullID.prefix(displayLength)) + "…"
    }

    var body: some View {
        HStack(spacing: 8) {
            Text(displayID).font(.headline)
            Button(action: copyToClipboard) {
                Image(systemName: "doc.on.doc")
                    .font(.caption)
                    .foregroundStyle(.blue)
            }
            .accessibilityLabel("Copy full ID")
        }
        .contentShape(Rectangle())
        .contextMenu {
            Button(action: copyToClipboard) {
                Label("Copy Full ID", systemImage: "doc.on.doc")
            }
        }
    }

    private func copyToClipboard() {
        UIPasteboard.general.string = fullID
        showCopiedFeedback = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            showCopiedFeedback = false
        }
    }
}

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

// MARK: - Auth

struct AuthView: View {
    @EnvironmentObject var authStore: AuthStore
    @State private var username = ""
    @State private var showRegister = false
    @State private var showRecovery = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)
                    .accessibilityHidden(true)
                Text("Ethos-Protocol").font(.largeTitle.bold())
                Text("Secure digital inheritance").foregroundStyle(.secondary)

                if let error = authStore.error {
                    ErrorActionView(error: error, retry: { Task { await authStore.signIn() } })
                }

                Button(action: { Task { await authStore.signIn() } }) {
                    Label("Sign in with Passkey", systemImage: "person.badge.key.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(authStore.isLoading)

                Button("Create account") { showRegister = true }
                    .foregroundStyle(.blue)

                Button("Lost your device?") { showRecovery = true }
                    .foregroundStyle(.secondary)
                    .font(.footnote)
            }
            .padding(32)
            .overlay { if authStore.isLoading { ProgressView() } }
            .sheet(isPresented: $showRegister) { RegisterView() }
            .sheet(isPresented: $showRecovery) { RecoverAccessView() }
        }
    }
}

struct RegisterView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var username = ""

    private var validationResult: Result<String, UsernameValidation.ValidationError> {
        UsernameValidation.validate(username)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if case .failure(let validationError) = validationResult, !username.isEmpty {
                        Text(validationError.errorDescription ?? "Invalid username")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                if let error = authStore.error {
                    Section { ErrorActionView(error: error, retry: { Task { await authStore.register(username: username) } }) }
                }
            }
            .navigationTitle("Create Account")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Register") {
                        guard case .success(let validUsername) = validationResult else { return }
                        Task { await authStore.register(username: validUsername); dismiss() }
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

struct RecoverAccessView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var email = ""
    @State private var backupCode = ""
    @State private var username = ""

    private var canSubmit: Bool {
        !email.isEmpty && !backupCode.isEmpty && !username.isEmpty
            && !authStore.isLoading && !authStore.isRecoveryBlocked
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Email", text: $email)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                        .disabled(authStore.isRecoveryBlocked)
                    TextField("Backup code", text: $backupCode)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(authStore.isRecoveryBlocked)
                } header: {
                    Text("Verify your identity")
                } footer: {
                    Text("Enter the email and backup code from when you created your account. We'll use them to link a new passkey on this device.")
                }
                Section("New Passkey") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(authStore.isRecoveryBlocked)
                }
                // #212: Escalating cooldown after repeated recovery-code failures.
                if authStore.isRecoveryBlocked {
                    Section {
                        Label("Too many attempts — wait \(authStore.recoveryCooldownSecondsRemaining)s",
                              systemImage: "timer")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                } else if authStore.recoveryFailureCount > 0 {
                    Section {
                        Text("\(authStore.recoveryFailureCount) failed attempt\(authStore.recoveryFailureCount == 1 ? "" : "s")")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                if let error = authStore.error {
                    Section { Text(error.message).foregroundStyle(.red).font(.caption) }
                }
            }
            .navigationTitle("Recover Access")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Link Passkey") {
                        Task {
                            await authStore.recoverAccess(email: email, backupCode: backupCode, username: username)
                            if authStore.isAuthenticated { dismiss() }
                        }
                    }
                    .disabled(!canSubmit)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Vault List

struct VaultListView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @EnvironmentObject var authStore: AuthStore
    @State private var showCreate = false
    @State private var showDeepLinkSheet = false
    @State private var showSettings = false
    // #219: search/filter over the vaults already fetched into vaultStore.vaults.
    @State private var searchText = ""
    @State private var statusFilter: VaultListFilter = .all
    // #118: Non-blocking jailbreak/root warning. Dismissed by the user; does not
    // block access to the app, consistent with the "secure digital inheritance" posture.
    @State private var showIntegrityWarning = IntegrityService.shared.isJailbroken
    // #214: Blocking confirmation before sign-out when this is the account's last
    // remaining passkey — unlike #118, this one gates the action itself.
    @State private var showLastPasskeySignOutWarning = false

    /// #219: filtered view over every vault page already fetched — search and
    /// status filter both apply client-side, so they work across paginated
    /// results without an extra fetch.
    private var filteredVaults: [Vault] {
        VaultListFiltering.filter(vaultStore.vaults, searchText: searchText, statusFilter: statusFilter)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let error = vaultStore.error {
                    ErrorActionView(error: error, retry: { Task { await vaultStore.load() } })
                        .padding()
                }
                if vaultStore.isLoading && vaultStore.vaults.isEmpty {
                    ProgressView("Loading vaults…")
                } else if vaultStore.vaults.isEmpty {
                    ContentUnavailableView("No Vaults", systemImage: "lock.open", description: Text("Create your first vault to get started."))
                } else {
                    VaultStatusFilterRow(selection: $statusFilter)
                    if filteredVaults.isEmpty {
                        ContentUnavailableView("No Matching Vaults", systemImage: "magnifyingglass", description: Text("Try a different search or filter."))
                    } else {
                        List {
                            ForEach(filteredVaults) { vault in
                                NavigationLink(destination: VaultDetailView(vault: vault)) {
                                    VaultRowView(vault: vault)
                                }
                            }
                            // Load More only makes sense against the unfiltered list — a
                            // filtered view already searched everything fetched so far.
                            if vaultStore.hasMorePages && searchText.isEmpty && statusFilter == .all {
                                LoadMoreRow(isLoading: vaultStore.isLoadingMore) {
                                    Task { await vaultStore.loadMore() }
                                }
                            }
                        }
                        .refreshable { await vaultStore.load() }
                    }
                }
            }
            .searchable(text: $searchText, prompt: "Search by label or ID")
            .navigationTitle("My Vaults")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: { showCreate = true }) { Image(systemName: "plus") }
                        .accessibilityLabel("Create new vault")
                }
                ToolbarItem(placement: .secondaryAction) {
                    Menu {
                        NavigationLink(destination: SettingsView()) {
                            Label("Settings", systemImage: "gear")
                        }
                        Button("Sign Out") {
                            Task {
                                if await authStore.isLastRemainingPasskey() {
                                    showLastPasskeySignOutWarning = true
                                } else {
                                    await authStore.signOut()
                                }
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            // #214: This is the account's only registered passkey — signing out here
            // with no recovery already in hand could permanently lock the user out of
            // a vault holding real funds, so this confirmation blocks the sign-out.
            .alert("This Is Your Only Passkey", isPresented: $showLastPasskeySignOutWarning) {
                Button("Cancel", role: .cancel) {}
                Button("Sign Out Anyway", role: .destructive) { Task { await authStore.signOut() } }
            } message: {
                Text("No other device has a passkey for this account. If you sign out without a way to recover access (your account's recovery email and backup code), you could be permanently locked out of any vaults you own.")
            }
            .task { await vaultStore.load() }
            .sheet(isPresented: $showCreate) { CreateVaultView() }
            .sheet(isPresented: $showDeepLinkSheet, onDismiss: { vaultStore.pendingDeepLink = nil }) {
                if let link = vaultStore.pendingDeepLink {
                    DeepLinkView(link: link)
                }
            }
            .onChange(of: vaultStore.pendingDeepLink) { _, link in
                if link != nil { showDeepLinkSheet = true }
            }
            // #a11y-live-region: the offline banner being labeled isn't enough — VoiceOver only
            // announces a view on first appearance or on an explicit accessibility notification.
            // vaultsCacheAge flipping between nil (online) and non-nil (offline) needs an
            // explicit UIAccessibility.post(notification: .announcement) so the transition
            // itself — not just the banner's static label — reaches a VoiceOver user, matching
            // the Android-side announceForAccessibility fix in Screens.kt.
            .onChange(of: vaultStore.vaultsCacheAge == nil) { wasOnlineBefore, isOnlineNow in
                let message = isOnlineNow ? "Back online" : "Offline — showing cached data"
                UIAccessibility.post(notification: .announcement, argument: message)
            }
            // #118: Non-blocking jailbreak warning — dismissible by the user.
            .alert("Security Warning", isPresented: $showIntegrityWarning) {
                Button("I Understand", role: .cancel) { showIntegrityWarning = false }
            } message: {
                Text("This device appears to be jailbroken. Your vault data, passkeys, and 2FA secrets may be at greater risk. Consider using a stock device for maximum security.")
            }
        }
    }

    // Surfaces staleness (issue #25) and any check-ins still waiting to sync (issue #28) above
    // the vault list, so both stay visible without blocking the list itself.
    @ViewBuilder
    private var statusBanners: some View {
        if let age = vaultStore.vaultsCacheAge {
            StatusBannerView(
                text: "Offline — showing vaults from \(Self.relativeAge(age))",
                systemImage: "wifi.slash",
                color: .orange)
        }
        if vaultStore.queuedCheckInCount > 0 {
            StatusBannerView(
                text: vaultStore.queuedCheckInCount == 1
                    ? "1 check-in queued — will retry when back online"
                    : "\(vaultStore.queuedCheckInCount) check-ins queued — will retry when back online",
                systemImage: "clock.arrow.circlepath",
                color: .blue)
        }
    }

    private static func relativeAge(_ interval: TimeInterval) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(fromTimeInterval: -interval)
    }
}

/// Status filter chip row for the vault list (#219).
struct VaultStatusFilterRow: View {
    @Binding var selection: VaultListFilter

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(VaultListFilter.allCases) { filter in
                    Button(action: { selection = filter }) {
                        Text(filter.label)
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(selection == filter ? Color.accentColor : Color.secondary.opacity(0.15))
                            .foregroundStyle(selection == filter ? Color.white : Color.primary)
                            .clipShape(Capsule())
                    }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
    }
}

struct StatusBannerView: View {
    let text: String
    let systemImage: String
    let color: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal)
            .padding(.vertical, 6)
            .background(color.opacity(0.1))
    }
}

/// Trailing row in VaultListView's list: a "Load More" button while a further
/// page is available, or a spinner while that page is being fetched.
struct LoadMoreRow: View {
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        HStack {
            Spacer()
            if isLoading {
                ProgressView()
            } else {
                Button("Load More", action: action)
                    .font(.subheadline)
            }
            Spacer()
        }
        .listRowSeparator(.hidden)
    }
}

struct VaultRowView: View {
    let vault: Vault

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                // #218: prefer the user-set label; fall back to the truncated
                // (but still copyable) ID when none is set.
                if let label = vault.label {
                    Text(label)
                        .font(.headline)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                } else {
                    CopyableIDView(fullID: vault.id, displayLength: 12)
                }
                Spacer()
                StatusBadge(status: vault.status)
            }
            Text(vault.formattedBalance)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            if vault.isExpiringSoon {
                Label("Expiring soon!", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .padding(.vertical, 4)
    }
}

struct StatusBadge: View {
    let status: Vault.VaultStatus
    var body: some View {
        Text(status.rawValue.capitalized)
            .font(.caption.bold())
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
    private var color: Color {
        switch status {
        case .active:   return .green
        case .expired:  return .orange
        case .released: return .blue
        case .paused:   return .gray
        }
    }
}

// MARK: - Connection Status Badge (#255)

/// Small "Live" / "Reconnecting" / "Polling" indicator driven by the WebSocket state.
struct ConnectionStatusBadge: View {
    let state: VaultEventSocket.ConnectionState

    var body: some View {
        Label(label, systemImage: icon)
            .font(.caption2.bold())
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.1))
            .clipShape(Capsule())
    }

    private var label: String {
        switch state {
        case .connected:         return "Live"
        case .connecting:        return "Connecting…"
        case .disconnected:      return "Reconnecting…"
        case .fallbackToPolling: return "Polling"
        }
    }

    private var icon: String {
        switch state {
        case .connected:         return "dot.radiowaves.left.and.right"
        case .connecting:        return "dot.radiowaves.left.and.right"
        case .disconnected:      return "arrow.clockwise"
        case .fallbackToPolling: return "arrow.clockwise"
        }
    }

    private var color: Color {
        switch state {
        case .connected:         return .green
        case .connecting:        return .blue
        case .disconnected:      return .orange
        case .fallbackToPolling: return .gray
        }
    }
}

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
    @State private var twoFactorStatus: TwoFactorStatus?
    @State private var twoFactorLoadError: String?
    @State private var showDeposit = false
    @State private var showWithdraw = false
    @State private var showManageBeneficiary = false
    @State private var showRenameVault = false
    /// Local TTL snapshot that updates every 60 s via `refreshTTLPeriodically`.
    @State private var ttlRemaining: UInt64? = nil

    var body: some View {
        List {
            Section("Overview") {
                if let label = vault.label {
                    LabeledContent("Label", value: label)
                }
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
                LabeledContent("Connection") {
                    ConnectionStatusBadge(state: vaultStore.socketConnectionState)
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
                        Button("Disable 2FA", role: .destructive) { disable2FA() }
                    } else {
                        Button("Enable 2FA") { show2FASetup = true }
                    }
                } else {
                    ProgressView()
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
                Button(action: { showRenameVault = true }) {
                    Label(vault.label == nil ? "Add Label" : "Rename Vault", systemImage: "pencil")
                }
                NavigationLink(destination: VaultHistoryView(vaultID: vault.id)) {
                    Label("Activity History", systemImage: "clock.arrow.circlepath")
                }
            }
        }
        .navigationTitle(vault.displayName)
        .navigationBarTitleDisplayMode(.inline)
        // `.task` auto-cancels when the view disappears, so this polling loop
        // (and the in-flight `getTTL` request it may be awaiting) stops cleanly
        // on navigating away instead of continuing to run in the background.
        // The real-time event subscription (#20) rides along on the same task:
        // opened before the loop starts, torn down via `defer` once it exits.
        .task {
            vaultStore.subscribeToEvents(vaultID: vault.id, socket: VaultEventSocket(baseURL: APIClient.shared.baseURL))
            defer { vaultStore.unsubscribeFromEvents() }
            await refreshTTLPeriodically()
        }
        .sheet(isPresented: $show2FASetup) {
            TwoFactorSetupView(vaultID: vault.id)
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
        .sheet(isPresented: $showDeposit) {
            DepositView(vault: vault)
        }
        .sheet(isPresented: $showWithdraw) {
            NavigationStack { WithdrawView(vault: vault) }
        }
        .sheet(isPresented: $showManageBeneficiary) {
            NavigationStack { ManageBeneficiaryView(vault: vault) }
        }
        .sheet(isPresented: $showRenameVault) {
            NavigationStack { RenameVaultView(vault: vault) }
        }
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
        ifNotCancelled { ttlRemaining = ttl }
    }

    /// Polls the server TTL every 60 s for as long as the view is on screen.
    /// The `.task` modifier that calls this cancels it automatically on disappear.
    private func refreshTTLPeriodically() async {
        ttlRemaining = vault.ttlRemaining   // seed with value from vault list
        while !Task.isCancelled {
            await refreshTTL()
            try? await Task.sleep(nanoseconds: Self.ttlRefreshInterval)
        }
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
                if !Task.isCancelled { await vaultStore.checkIn(vault: vault) }
            } catch {
                ifNotCancelled { biometricError = error.localizedDescription }
            }
            ifNotCancelled { isCheckingIn = false }
        }
    }

    private func formatDuration(_ seconds: UInt64) -> String {
        let days = seconds / 86_400
        let hours = (seconds % 86_400) / 3_600
        if days > 0 { return "\(days)d \(hours)h" }
        return "\(hours)h"
    }
}

// MARK: - Create Vault

struct CreateVaultView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var beneficiary = ""
    @State private var intervalDays = 30.0
    @State private var isCreating = false
    @State private var error: String?
    // #215: Vault creation commits real funds to a TTL-gated structure — require an
    // explicit review step showing exactly what's about to be submitted before the
    // POST /vaults call fires, rather than submitting straight from the input form.
    @State private var isConfirming = false

    var body: some View {
        NavigationStack {
            if isConfirming {
                confirmationForm
            } else {
                inputForm
            }
        }
    }

    private var inputForm: some View {
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
        }
        .navigationTitle("New Vault")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Next") { isConfirming = true }.disabled(!isBeneficiaryValid)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private var confirmationForm: some View {
        Form {
            Section {
                LabeledContent("Beneficiary") {
                    Text(beneficiary)
                        .font(.system(.footnote, design: .monospaced))
                        .multilineTextAlignment(.trailing)
                }
                LabeledContent("Check-in Interval", value: "\(Int(intervalDays)) days")
            } header: {
                Text("Review Vault")
            } footer: {
                Text("If you don't check in within the interval above, the vault's funds release to the beneficiary address shown. Double-check the address — this cannot be undone once created.")
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .navigationTitle("Confirm Vault")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isCreating ? "Creating…" : "Confirm & Create") { create() }.disabled(isCreating)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Back") { isConfirming = false }.disabled(isCreating)
            }
        }
        .overlay { if isCreating { ProgressView() } }
    }

    private var isBeneficiaryValid: Bool {
        StellarAddress.isValidPublicKey(StellarAddress.sanitize(beneficiary))
    }

    private func create() {
        let sanitized = StellarAddress.sanitize(beneficiary)
        guard StellarAddress.isValidPublicKey(sanitized) else { return }
        isCreating = true
        Task {
            do {
                let interval = UInt64(intervalDays * 86_400)
                let vault = try await APIClient.shared.createVault(beneficiary: sanitized, checkInInterval: interval)
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
            .overlay { if isDepositing { ProgressView() } }
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

// MARK: - Withdraw

struct WithdrawView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var amountText = ""
    @State private var isWithdrawing = false
    @State private var error: String?
    // #216: extra confirmation gate for a large withdrawal — shown instead of
    // withdrawing immediately when isLargeWithdrawal is true.
    @State private var showLargeWithdrawalConfirmation = false

    private var amountStroops: Int64? { VaultAmount.parseStroops(amountText) }

    private var isAmountValid: Bool {
        guard let amount = amountStroops else { return false }
        return VaultAmount.hasSufficientBalance(amount: amount, vaultBalance: vault.balance)
    }

    private var isLargeWithdrawal: Bool {
        guard let amount = amountStroops else { return false }
        return VaultAmount.isLargeWithdrawal(
            amount: amount, vaultBalance: vault.balance,
            thresholdBps: WithdrawalThreshold.largeWithdrawalBps)
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
                } else if isLargeWithdrawal {
                    Label("This is a large withdrawal relative to the vault's balance.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .font(.caption)
                }
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .navigationTitle("Withdraw")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isWithdrawing ? "Withdrawing…" : "Withdraw") { attemptWithdraw() }
                    .disabled(!isAmountValid || isWithdrawing)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
        .overlay { if isWithdrawing { ProgressView() } }
        // #216: a large withdrawal needs an explicit extra tap before it proceeds,
        // on top of the biometric gate every withdrawal already requires.
        .confirmationDialog(
            "Withdraw \(amountText.isEmpty ? "" : amountText) XLM?",
            isPresented: $showLargeWithdrawalConfirmation,
            titleVisibility: .visible
        ) {
            Button("Withdraw", role: .destructive) { withdraw() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This withdraws a large share of the vault's balance. This cannot be undone.")
        }
    }

    private func attemptWithdraw() {
        if isLargeWithdrawal {
            showLargeWithdrawalConfirmation = true
        } else {
            withdraw()
        }
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

// MARK: - Vault History (#217)

struct VaultHistoryView: View {
    let vaultID: String
    @State private var events: [VaultHistoryEvent] = []
    @State private var nextCursor: String?
    @State private var isLoading = false
    @State private var isLoadingMore = false
    @State private var error: String?

    private var hasMorePages: Bool { nextCursor != nil }

    var body: some View {
        Group {
            if isLoading && events.isEmpty {
                ProgressView("Loading history…")
            } else if let error, events.isEmpty {
                ErrorActionView(error: ErrorPresentation(message: error, showsRetry: true),
                                retry: { Task { await load() } })
            } else if events.isEmpty {
                ContentUnavailableView("No Activity Yet", systemImage: "clock",
                                        description: Text("Check-ins, deposits, and withdrawals will show up here."))
            } else {
                List {
                    ForEach(events) { event in
                        VaultHistoryRowView(event: event)
                    }
                    if hasMorePages {
                        LoadMoreRow(isLoading: isLoadingMore) {
                            Task { await loadMore() }
                        }
                    }
                }
                .refreshable { await load() }
            }
        }
        .navigationTitle("Activity")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        isLoading = true; error = nil
        do {
            let page = try await APIClient.shared.getVaultHistory(vaultID: vaultID)
            events = page.events
            nextCursor = page.nextCursor
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    private func loadMore() async {
        guard !isLoadingMore, let cursor = nextCursor else { return }
        isLoadingMore = true
        do {
            let page = try await APIClient.shared.getVaultHistory(vaultID: vaultID, cursor: cursor)
            events += page.events
            nextCursor = page.nextCursor
        } catch {
            self.error = error.localizedDescription
        }
        isLoadingMore = false
    }
}

struct VaultHistoryRowView: View {
    let event: VaultHistoryEvent

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(event.displayTitle).font(.headline).foregroundStyle(.primary)
            if let amount = event.amount {
                Text(String(format: "%.7f XLM", Double(amount) / 10_000_000))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if let beneficiary = event.beneficiary {
                Text("New beneficiary: \(beneficiary)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(event.timestamp, style: .date) + Text(" ") + Text(event.timestamp, style: .time)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .padding(.vertical, 2)
    }
}

// MARK: - Rename Vault (#218)

struct RenameVaultView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @Environment(\.dismiss) var dismiss
    @State private var labelText: String
    @State private var isUpdating = false
    @State private var error: String?

    init(vault: Vault) {
        self.vault = vault
        _labelText = State(initialValue: vault.label ?? "")
    }

    private var trimmedLabel: String {
        labelText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        Form {
            Section("Label") {
                TextField("e.g. Emergency Fund", text: $labelText)
                Text("Shown instead of the vault ID in your vault list. Leave blank to clear it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
        }
        .navigationTitle(vault.label == nil ? "Add Label" : "Rename Vault")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isUpdating ? "Saving…" : "Save") { save() }
                    .disabled(isUpdating)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private func save() {
        isUpdating = true; error = nil
        let newLabel = trimmedLabel.isEmpty ? nil : trimmedLabel
        Task {
            await vaultStore.updateLabel(vault: vault, label: newLabel)
            if let storeError = vaultStore.error {
                error = storeError.message
            } else {
                dismiss()
            }
            isUpdating = false
        }
    }
}

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
        let sanitized = StellarAddress.sanitize(newBeneficiary)
        isUpdating = true; error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm beneficiary change")
                await vaultStore.updateBeneficiary(vault: vault, newBeneficiary: sanitized)
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

// MARK: - 2FA Views

// #228: Copyable TOTP secret with auto-clear clipboard and one-time security warning.
private struct TOTPSecretCopyView: View {
    let secret: String
    @State private var showCopied = false
    @State private var showWarning = false
    private static let warnedKey = "com.ethosprotocol.totp_copy_warned"
    private static let clearDelay: TimeInterval = 30

    var body: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                Label(secret, systemImage: "key.fill")
                    .font(.system(.caption, design: .monospaced))
                    .lineLimit(1)
            }
            Button(action: copySecret) {
                Image(systemName: showCopied ? "checkmark" : "doc.on.doc")
                    .font(.caption)
                    .foregroundStyle(showCopied ? .green : .blue)
            }
            .accessibilityLabel(showCopied ? "Copied" : "Copy TOTP secret")
        }
        .alert("Security Notice", isPresented: $showWarning) {
            Button("I Understand", role: .cancel) {
                UserDefaults.standard.set(true, forKey: Self.warnedKey)
                performCopy()
            }
        } message: {
            Text("Your 2FA secret will be copied to the clipboard and automatically cleared after 30 seconds. Clipboard managers and other apps may capture it before it is cleared. Treat this secret like a password.")
        }
    }

    private func copySecret() {
        if UserDefaults.standard.bool(forKey: Self.warnedKey) {
            performCopy()
        } else {
            showWarning = true
        }
    }

    private func performCopy() {
        UIPasteboard.general.string = secret
        showCopied = true
        // #228: Auto-clear the clipboard after 30 seconds.
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.clearDelay) {
            if UIPasteboard.general.string == self.secret {
                UIPasteboard.general.string = ""
            }
            showCopied = false
        }
    }
}

struct TwoFactorSetupView: View {
    let vaultID: String
    @Environment(\.dismiss) var dismiss
    @State private var selectedMethod: TwoFactorMethod = .totp
    @State private var phone = ""
    @State private var email = ""
    @State private var setupResponse: Enable2FAResponse?
    @State private var showVerify = false
    @State private var isSettingUp = false
    @State private var error: String?
    @State private var setupComplete = false

    // #202: A method + "code sent" flag restored from a prior, interrupted setup —
    // used only when process death happened after the code was sent but before the
    // response object (and its non-persisted secret/provisioningUri) was recreated.
    @State private var restoredSession: PendingTwoFactorSession?

    var body: some View {
        NavigationStack {
            if let response = setupResponse {
                TwoFactorVerifyView(
                    vaultID: vaultID,
                    method: response.method,
                    provisioningUri: response.provisioningUri,
                    secret: response.secret,
                    onVerified: { setupComplete = true; PendingTwoFactorSessionStore.shared.clear(for: vaultID) }
                )
            } else if let restoredSession {
                TwoFactorVerifyView(
                    vaultID: vaultID,
                    method: restoredSession.method,
                    provisioningUri: nil,
                    secret: nil,
                    onVerified: { setupComplete = true; PendingTwoFactorSessionStore.shared.clear(for: vaultID) }
                )
            } else {
                Form {
                    Section("Authentication Method") {
                        Picker("Method", selection: $selectedMethod) {
                            ForEach(TwoFactorMethod.allCases, id: \.self) { method in
                                Text(methodLabel(method)).tag(method)
                            }
                        }
                    }

                    if selectedMethod == .sms {
                        Section("SMS Number") {
                            TextField("Phone number", text: $phone)
                                .keyboardType(.phonePad)
                        }
                    }

                    if selectedMethod == .email {
                        Section("Email Address") {
                            TextField("Email", text: $email)
                                .keyboardType(.emailAddress)
                                .autocapitalization(.none)
                        }
                    }

                    if let error { Section { Text(error).foregroundStyle(.red).font(.caption) } }
                }
                .navigationTitle("Enable 2FA")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Continue") { setup() }
                            .disabled(isSettingUp || !canContinue)
                    }
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
                .overlay { if isSettingUp { ProgressView() } }
            }
        }
        .interactiveDismissDisabled(setupComplete == false)
        .onAppear {
            // #202: Restore a still-valid in-progress session on relaunch instead of
            // forcing the user back through method selection and a fresh code send.
            restoredSession = PendingTwoFactorSessionStore.shared.session(for: vaultID)
        }
    }

    private var canContinue: Bool {
        switch selectedMethod {
        case .totp: return true
        case .sms:  return !phone.trimmingCharacters(in: .whitespaces).isEmpty
        case .email: return !email.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func methodLabel(_ method: TwoFactorMethod) -> String {
        switch method {
        case .totp:  return "Authenticator App (TOTP)"
        case .sms:   return "SMS Code"
        case .email: return "Email Code"
        }
    }

    private func setup() {
        isSettingUp = true; error = nil
        Task {
            do {
                let response = try await APIClient.shared.enable2FA(
                    vaultID: vaultID,
                    method: selectedMethod,
                    phone: selectedMethod == .sms ? phone : nil,
                    email: selectedMethod == .email ? email : nil
                )
                setupResponse = response
                // #202: Persist just enough to resume at the verify step if the process
                // dies before setupComplete — never the secret/provisioningUri or the OTP.
                PendingTwoFactorSessionStore.shared.save(
                    PendingTwoFactorSession(method: selectedMethod, codeSent: true, createdAt: Date()),
                    for: vaultID
                )
            } catch {
                self.error = error.localizedDescription
            }
            isSettingUp = false
        }
    }
}

// #228: Copyable TOTP secret with auto-clear clipboard and one-time security warning.
private struct TOTPSecretCopyView: View {
    let secret: String
    @State private var showCopied = false
    @State private var showWarning = false
    private static let warnedKey = "com.ethosprotocol.totp_copy_warned"
    private static let clearDelay: TimeInterval = 30

    var body: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                Label(secret, systemImage: "key.fill")
                    .font(.system(.caption, design: .monospaced))
                    .lineLimit(1)
            }
            Button(action: copySecret) {
                Image(systemName: showCopied ? "checkmark" : "doc.on.doc")
                    .font(.caption)
                    .foregroundStyle(showCopied ? .green : .blue)
            }
            .accessibilityLabel(showCopied ? "Copied" : "Copy TOTP secret")
        }
        .alert("Security Notice", isPresented: $showWarning) {
            Button("I Understand", role: .cancel) {
                UserDefaults.standard.set(true, forKey: Self.warnedKey)
                performCopy()
            }
        } message: {
            Text("Your 2FA secret will be copied to the clipboard and automatically cleared after 30 seconds. Clipboard managers and other apps may capture it before it is cleared. Treat this secret like a password.")
        }
    }

    private func copySecret() {
        if UserDefaults.standard.bool(forKey: Self.warnedKey) {
            performCopy()
        } else {
            showWarning = true
        }
    }

    private func performCopy() {
        UIPasteboard.general.string = secret
        showCopied = true
        // #228: Auto-clear the clipboard after 30 seconds.
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.clearDelay) {
            if UIPasteboard.general.string == self.secret {
                UIPasteboard.general.string = ""
            }
            showCopied = false
        }
    }
}

struct TwoFactorVerifyView: View {
    let vaultID: String
    let method: TwoFactorMethod
    let provisioningUri: String?
    let secret: String?
    let onVerified: () -> Void
    @Environment(\.dismiss) var dismiss

    @State private var otp = ""
    @State private var isVerifying = false
    @State private var error: String?

    // #119: Escalating cooldown after repeated OTP failures.
    @StateObject private var rateLimiter = OTPRateLimiter()

    private var isInitialSetup: Bool {
        provisioningUri != nil || secret != nil
    }

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: iconName)
                .font(.system(size: 56))
                .foregroundStyle(.blue)
                .accessibilityLabel("Two-factor authentication via \(methodLabel)")

            Text(titleText).font(.title.bold())

            VStack(spacing: 8) {
                if method == .totp, let uri = provisioningUri {
                    Text("Scan this URI in your authenticator app:").foregroundStyle(.secondary)
                    Text(uri).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                    if let secret {
                        TOTPSecretCopyView(secret: secret)
                    }
                } else if method == .totp {
                    Text("Enter the 6-digit code from your authenticator app.").foregroundStyle(.secondary)
                } else {
                    Text("A verification code has been sent to your \(methodLabel).").foregroundStyle(.secondary)
                }
            }

            TextField("Enter 6-digit code", text: $otp)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)
                .frame(maxWidth: 200)
                .multilineTextAlignment(.center)
                .font(.title2)
                .disabled(rateLimiter.isBlocked)
                // #230: Positional accessibility label so VoiceOver announces entry progress
                // (e.g. "3 of 6 digits entered") rather than just the placeholder text.
                .accessibilityLabel("OTP code field")
                .accessibilityValue(otp.isEmpty ? "empty" : "\(otp.count) of 6 digits entered")
                .accessibilityHint("Enter the 6-digit verification code")
                .accessibilityLabel("OTP code field")
                .accessibilityValue(otp.isEmpty ? "empty" : "\(otp.count) of 6 digits entered")
                .accessibilityHint("Enter the 6-digit verification code")

            // #119: Show remaining cooldown when the user is locked out.
            if rateLimiter.isBlocked {
                Label("Too many attempts — wait \(rateLimiter.cooldownSecondsRemaining)s",
                      systemImage: "timer")
                    .font(.caption)
                    .foregroundStyle(.orange)
            } else if rateLimiter.failureCount > 0 {
                Text("\(rateLimiter.failureCount) failed attempt\(rateLimiter.failureCount == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let error { Text(error).foregroundStyle(.red).font(.caption) }

            Button(action: verify) {
                Label(isVerifying ? "Verifying…" : "Verify", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(otp.count != 6 || isVerifying || rateLimiter.isBlocked)
        }
        .padding(32)
        .navigationTitle("Verify 2FA")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
    }

    private var titleText: String {
        if method == .totp && isInitialSetup {
            return "Verify Setup"
        } else if method == .totp {
            return "Re-verify Authenticator"
        } else {
            return "Verify Setup"
        }
    }

    private var iconName: String {
        switch method {
        case .totp:  return "lock.shield.fill"
        case .sms:   return "message.fill"
        case .email: return "envelope.fill"
        }
    }

    private var methodLabel: String {
        switch method {
        case .totp:  return "authenticator app"
        case .sms:   return "phone"
        case .email: return "email"
        }
    }

    private func verify() {
        isVerifying = true; error = nil
        Task {
            do {
                try await APIClient.shared.verify2FA(vaultID: vaultID, otp: otp)
                rateLimiter.reset()   // #119: Reset on success
                onVerified()
                dismiss()
            } catch {
                self.error = error.localizedDescription
                rateLimiter.recordFailure()   // #119: Record failure and possibly start cooldown
            }
            isVerifying = false
        }
    }
}

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
