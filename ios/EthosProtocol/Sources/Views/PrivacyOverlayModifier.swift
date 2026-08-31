import SwiftUI

/// A `ViewModifier` that overlays an opaque privacy screen whenever the app is
/// not in the `.active` scene phase (i.e. when the system app-switcher snapshot
/// is captured, or when the app is fully backgrounded). This prevents sensitive
/// vault data — balances, beneficiaries, TTL countdowns — from appearing in the
/// iOS app-switcher thumbnail or being captured by the system screenshot taken
/// on transition to background.
///
/// Apply via the `privacyOverlay()` convenience extension.
struct PrivacyOverlayModifier: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .overlay {
                if scenePhase != .active {
                    privacyScreen
                }
            }
    }

    /// Full-screen overlay shown while the app is backgrounded or inactive.
    /// Uses the system background colour as the base so it respects dark/light
    /// mode, then centres a lock icon as a neutral placeholder.
    @ViewBuilder
    private var privacyScreen: some View {
        ZStack {
            Color(uiColor: .systemBackground)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.secondary)

                Text("Ethos Protocol")
                    .font(.title2)
                    .fontWeight(.semibold)
                    .foregroundStyle(.secondary)
            }
        }
        // The overlay must not receive touches (the real content beneath it
        // must stay interactive when the system briefly marks the scene
        // .inactive, e.g. during a swipe-from-bottom gesture).
        .allowsHitTesting(false)
        // Skip accessibility so VoiceOver doesn't announce the cover screen.
        .accessibilityHidden(true)
    }
}

extension View {
    /// Overlays an opaque privacy screen while the scene is not `.active`,
    /// preventing sensitive content from appearing in the iOS app switcher.
    func privacyOverlay() -> some View {
        modifier(PrivacyOverlayModifier())
    }
}
