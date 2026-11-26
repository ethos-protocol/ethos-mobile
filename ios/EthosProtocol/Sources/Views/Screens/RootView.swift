import SwiftUI

struct RootView: View {
    @EnvironmentObject var authStore: AuthStore
    @StateObject private var timeoutIndicator = BiometricTimeoutIndicatorService.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if authStore.isAuthenticated {
                ZStack(alignment: .top) {
                    VaultListView()

                    // Biometric timeout indicator
                    if timeoutIndicator.isActive && timeoutIndicator.totalTimeoutSeconds < Int.max {
                        BiometricTimeoutIndicatorView(indicator: timeoutIndicator)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
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
            // #234: retry a push-token registration that failed even after
            // NotificationService's initial retries, rather than waiting
            // indefinitely for the OS to redeliver the device token.
            if newPhase == .active {
                NotificationService.shared.retryPendingPushTokenRegistrationIfNeeded()
            }
        }
        .sheet(isPresented: $authStore.showPINSetup) {
            PINSetupView()
        }
    }
}

struct PrivacyOverlayView: View {
    var body: some View {
        ZStack {
            Color(.systemBackground)
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(.blue)
        }
        .ignoresSafeArea()
        .transition(.opacity)
        .respectsReduceMotion()
    }
}
