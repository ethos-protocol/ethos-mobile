import SwiftUI

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

                Button("Lost your device?") { showRecovery = true }
                    .foregroundStyle(.secondary)
                    .font(.footnote)
            }
            .padding(32)
            .overlay { if authStore.isLoading { ProgressView().respectsReduceMotion() } }
            .sheet(isPresented: $showRegister) { RegisterView() }
            .sheet(isPresented: $showRecovery) { RecoverAccessView() }
        }
        .trackScreen("Auth")
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
        !email.isEmpty && !backupCode.isEmpty && !username.isEmpty && !authStore.isLoading
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Email", text: $email)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                    TextField("Backup code", text: $backupCode)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Verify your identity")
                } footer: {
                    Text("Enter the email and backup code from when you created your account. We'll use them to link a new passkey on this device.")
                }
                Section("New Passkey") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
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
