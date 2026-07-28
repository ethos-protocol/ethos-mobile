import SwiftUI

struct RootView: View {
    @EnvironmentObject var authStore: AuthStore

    var body: some View {
        if authStore.isAuthenticated {
            VaultListView()
        } else {
            AuthView()
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

// MARK: - Auth

struct AuthView: View {
    @EnvironmentObject var authStore: AuthStore
    @State private var username = ""
    @State private var showRegister = false

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
                    Text(error).foregroundStyle(.red).font(.caption).multilineTextAlignment(.center)
                }

                Button(action: { Task { await authStore.signIn() } }) {
                    Label("Sign in with Passkey", systemImage: "person.badge.key.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(authStore.isLoading)

                Button("Create account") { showRegister = true }
                    .foregroundStyle(.blue)
            }
            .padding(32)
            .overlay { if authStore.isLoading { ProgressView() } }
            .sheet(isPresented: $showRegister) { RegisterView() }
        }
    }
}

struct RegisterView: View {
    @EnvironmentObject var authStore: AuthStore
    @Environment(\.dismiss) var dismiss
    @State private var username = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                if let error = authStore.error {
                    Section { Text(error).foregroundStyle(.red).font(.caption) }
                }
            }
            .navigationTitle("Create Account")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Register") {
                        Task { await authStore.register(username: username); dismiss() }
                    }
                    .disabled(username.isEmpty || authStore.isLoading)
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
    // #118: Non-blocking jailbreak/root warning. Dismissed by the user; does not
    // block access to the app, consistent with the "secure digital inheritance" posture.
    @State private var showIntegrityWarning = IntegrityService.shared.isJailbroken

    var body: some View {
        NavigationStack {
            Group {
                if vaultStore.isLoading && vaultStore.vaults.isEmpty {
                    ProgressView("Loading vaults…")
                } else if vaultStore.vaults.isEmpty {
                    ContentUnavailableView("No Vaults", systemImage: "lock.open", description: Text("Create your first vault to get started."))
                } else {
                    List(vaultStore.vaults) { vault in
                        NavigationLink(destination: VaultDetailView(vault: vault)) {
                            VaultRowView(vault: vault)
                        }
                    }
                    .refreshable { await vaultStore.load() }
                }
            }
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
                        Button("Sign Out") { authStore.signOut() }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
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
            // #118: Non-blocking jailbreak warning — dismissible by the user.
            .alert("Security Warning", isPresented: $showIntegrityWarning) {
                Button("I Understand", role: .cancel) { showIntegrityWarning = false }
            } message: {
                Text("This device appears to be jailbroken. Your vault data, passkeys, and 2FA secrets may be at greater risk. Consider using a stock device for maximum security.")
            }
        }
    }
}

struct VaultRowView: View {
    let vault: Vault

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                CopyableIDView(fullID: vault.id, displayLength: 12)
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

// MARK: - Vault Detail

struct VaultDetailView: View {
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
    /// Local TTL snapshot that updates every 60 s via `refreshTTLPeriodically`.
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
                if let ttl = vault.ttlRemaining {
                    LabeledContent("TTL Remaining", value: formatDuration(ttl))
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
        }
        .navigationTitle("Vault")
        .navigationBarTitleDisplayMode(.inline)
        // `.task` auto-cancels when the view disappears, so this polling loop
        // (and the in-flight `getTTL` request it may be awaiting) stops cleanly
        // on navigating away instead of continuing to run in the background.
        .task { await refreshTTLPeriodically() }
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
    }

    private func load2FAStatus() async {
        twoFactorLoadError = nil
        do {
            twoFactorStatus = try await APIClient.shared.get2FAStatus(vaultID: vault.id)
        } catch {
            twoFactorLoadError = error.localizedDescription
            twoFactorStatus = nil
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
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
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
                error = storeError
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
        .overlay { if isWithdrawing { ProgressView() } }
    }

    private func withdraw() {
        guard let amount = amountStroops else { return }
        isWithdrawing = true; error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Confirm vault withdrawal")
                await vaultStore.withdraw(vault: vault, amount: amount)
                if let storeError = vaultStore.error {
                    error = storeError
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
                    error = storeError
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

    var body: some View {
        NavigationStack {
            if let response = setupResponse {
                TwoFactorVerifyView(
                    vaultID: vaultID,
                    method: response.method,
                    provisioningUri: response.provisioningUri,
                    secret: response.secret,
                    onVerified: { setupComplete = true }
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
            } catch {
                self.error = error.localizedDescription
            }
            isSettingUp = false
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
                        ScrollView(.horizontal, showsIndicators: false) {
                            Label(secret, systemImage: "key.fill")
                                .font(.system(.caption, design: .monospaced))
                                .lineLimit(1)
                        }
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
