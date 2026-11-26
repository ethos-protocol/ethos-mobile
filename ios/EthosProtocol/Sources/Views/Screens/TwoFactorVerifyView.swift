import SwiftUI

// MARK: - 2FA Views

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
    /// #226: "Remember this device for 30 days" opt-in.
    @State private var trustDevice = false
    /// #224: Shown only during initial TOTP setup.
    @State private var backupCodes: [String] = []
    @State private var showBackupCodes = false
    @State private var isGeneratingBackupCodes = false

    // #119: Escalating cooldown after repeated OTP failures. State is persisted
    // (#171), so dismissing and re-presenting this view resumes any cooldown in
    // progress instead of recreating the limiter with a zeroed failure count.
    @StateObject private var rateLimiter = OTPRateLimiter()

    private var isInitialSetup: Bool {
        provisioningUri != nil || secret != nil
    }

    var body: some View {
        if showBackupCodes {
            backupCodesView
        } else {
            verifyInputView
        }
    }

    // MARK: - OTP entry view

    private var verifyInputView: some View {
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

            // #226: Trust-device opt-in checkbox.
            Toggle(isOn: $trustDevice) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Remember this device")
                        .font(.subheadline)
                    Text("Skip 2FA on this device for 30 days")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal)

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

    // MARK: - #224 Backup codes display view

    private var backupCodesView: some View {
        VStack(spacing: 24) {
            Image(systemName: "key.2.on.ring.fill")
                .font(.system(size: 56))
                .foregroundStyle(.orange)
                .accessibilityHidden(true)
            Text("Save Your Backup Codes").font(.title.bold())
            Text("Store these codes somewhere safe. Each code can be used once if you lose your authenticator app. They will not be shown again.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .font(.callout)
            if isGeneratingBackupCodes {
                ProgressView("Generating codes…").respectsReduceMotion()
            } else {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(backupCodes, id: \.self) { code in
                        Text(code)
                            .font(.system(.body, design: .monospaced).bold())
                            .padding(8)
                            .frame(maxWidth: .infinity)
                            .background(Color(.secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .padding(.horizontal)
            }
            Button(action: { SensitiveClipboard.copy(backupCodes.joined(separator: "\n")) }) {
                Label("Copy All Codes", systemImage: "doc.on.doc").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(backupCodes.isEmpty)
            Button(action: { onVerified(); dismiss() }) {
                Text("I've Saved My Codes").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(backupCodes.isEmpty && !isGeneratingBackupCodes)
        }
        .padding(32)
        .navigationTitle("Backup Codes")
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
                // #226: Pass trust-device preference; handle optional token in response.
                let response = try await APIClient.shared.verify2FA(vaultID: vaultID, otp: otp, trustDevice: trustDevice)
                rateLimiter.reset()   // #119

                // #226: Persist trust token if server issued one.
                if trustDevice, let token = response.deviceTrustToken, let expiry = response.expiresAt {
                    KeychainService.shared.saveTrustToken(token, vaultID: vaultID, expiresAt: expiry)
                }

                // #224: Generate and display backup codes on initial TOTP setup.
                if method == .totp && isInitialSetup {
                    isGeneratingBackupCodes = true
                    showBackupCodes = true
                    do {
                        let codesResponse = try await APIClient.shared.generateBackupCodes(vaultID: vaultID)
                        backupCodes = codesResponse.codes
                    } catch {
                        // Non-fatal: user can regenerate later from vault settings.
                        backupCodes = []
                    }
                    isGeneratingBackupCodes = false
                } else {
                    onVerified()
                    dismiss()
                }
            } catch {
                self.error = error.localizedDescription
                rateLimiter.recordFailure()   // #119
            }
            isVerifying = false
        }
    }
}
