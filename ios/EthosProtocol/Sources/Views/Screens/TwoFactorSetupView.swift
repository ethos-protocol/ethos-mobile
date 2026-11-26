import SwiftUI

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
    /// #227: Available methods from TwoFactorStatus; defaults to all until known.
    var availableMethods: [TwoFactorMethod] = TwoFactorMethod.allCases

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
                        // #227: Only show methods the server reports as available.
                        Picker("Method", selection: $selectedMethod) {
                            ForEach(availableMethods, id: \.self) { method in
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
                .overlay { if isSettingUp { ProgressView().respectsReduceMotion() } }
                .onAppear {
                    // #227: Default to first available method if totp is unavailable.
                    if !availableMethods.contains(selectedMethod), let first = availableMethods.first {
                        selectedMethod = first
                    }
                }
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
