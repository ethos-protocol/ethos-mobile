import SwiftUI

struct LockScreenView: View {
    @EnvironmentObject var authStore: AuthStore
    @State private var error: String?
    @State private var isUnlocking = false
    @State private var showPINInput = false
    @State private var pinInput = ""
    @State private var pinError: String?

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 24) {
                if showPINInput {
                    pinInputView
                } else {
                    biometricView
                }
            }
            .padding(32)
        }
        .onAppear(perform: attemptBiometricUnlock)
    }

    private var biometricView: some View {
        VStack(spacing: 24) {
            Image(systemName: "faceid")
                .font(.system(size: 64))
                .foregroundStyle(.blue)
            Text("Ethos-Protocol Locked").font(.title.bold())
            if let error {
                Text(error).foregroundStyle(.red).font(.caption).multilineTextAlignment(.center)
            }
            Button(action: attemptBiometricUnlock) {
                Label(isUnlocking ? "Unlocking…" : "Unlock", systemImage: "faceid")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isUnlocking)
            if PINAuthenticationService.shared.isPINSetup() {
                Button(action: { showPINInput = true }) {
                    Text("Use PIN Instead")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var pinInputView: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.fill")
                .font(.system(size: 64))
                .foregroundStyle(.blue)
            Text("Ethos-Protocol Locked").font(.title.bold())
            Text("Enter your 6-digit PIN").font(.subheadline).foregroundStyle(.secondary)
            SecureField("PIN", text: $pinInput)
                .textContentType(.oneTimeCode)
                .keyboardType(.numberPad)
                .font(.system(size: 20, weight: .medium, design: .monospaced))
                .multilineTextAlignment(.center)
                .frame(maxWidth: 150)
                .padding(12)
                .background(Color(.systemGray6))
                .cornerRadius(8)
                .disabled(isUnlocking)
            if let error = pinError {
                Text(error).foregroundStyle(.red).font(.caption).multilineTextAlignment(.center)
            }
            Button(action: verifyPIN) {
                Label(isUnlocking ? "Verifying…" : "Unlock", systemImage: "checkmark")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isUnlocking || pinInput.count != 6)
            Button(action: { showPINInput = false; pinInput = ""; pinError = nil }) {
                Text("Back to Biometric")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    private func attemptBiometricUnlock() {
        guard !isUnlocking else { return }
        isUnlocking = true
        error = nil
        Task {
            do {
                try await BiometricService.shared.authenticate(reason: "Unlock Ethos-Protocol")
                HapticFeedbackService.shared.success()
                authStore.isLocked = false
            } catch {
                HapticFeedbackService.shared.error()
                self.error = error.localizedDescription
                if PINAuthenticationService.shared.isPINSetup() {
                    showPINInput = true
                }
            }
            isUnlocking = false
        }
    }

    private func verifyPIN() {
        guard !isUnlocking else { return }
        isUnlocking = true
        pinError = nil
        do {
            try PINAuthenticationService.shared.verifyPIN(pinInput)
            authStore.isLocked = false
        } catch {
            pinError = error.localizedDescription
        }
        isUnlocking = false
    }
}
