import SwiftUI

/// A view modifier that conditionally applies animations and transitions based on the user's
/// accessibility reduce-motion setting. When reduce motion is enabled, uses instant transitions
/// instead of animated ones.
struct ReduceMotionModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .transaction { transaction in
                if reduceMotion {
                    transaction.animation = nil
                }
            }
    }
}

extension View {
    /// Respects the user's accessibility reduce-motion setting by disabling animations
    /// when reduce motion is enabled. Apply this to views with animated transitions or
    /// progress indicators.
    func respectsReduceMotion() -> some View {
        modifier(ReduceMotionModifier())
    }

    /// Conditionally applies a transition based on reduce-motion setting. Uses the
    /// provided transition when reduce motion is disabled, or instant (no transition)
    /// when reduce motion is enabled.
    func transitionIfMotionAllowed(_ transition: AnyTransition) -> some View {
        @Environment(\.accessibilityReduceMotion) var reduceMotion

        if reduceMotion {
            return AnyView(self.transition(.identity))
        } else {
            return AnyView(self.transition(transition))
        }
    }
}
