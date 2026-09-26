import UIKit
import Foundation

/// Provides haptic feedback for critical user actions.
/// Haptic feedback is toggleable via UserDefaults and can be disabled in settings.
final class HapticFeedbackService {
    static let shared = HapticFeedbackService()

    private init() {}

    private static let userDefaultsKey = "com.ethosprotocol.haptic_feedback_enabled"

    /// Whether haptic feedback is enabled. Defaults to true.
    static var isEnabled: Bool {
        get {
            let value = UserDefaults.standard.object(forKey: userDefaultsKey)
            return value == nil ? true : (value as? Bool) ?? true
        }
        set {
            UserDefaults.standard.set(newValue, forKey: userDefaultsKey)
        }
    }

    /// Light haptic feedback for interactions like button taps.
    func lightImpact() {
        guard HapticFeedbackService.isEnabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
    }

    /// Medium haptic feedback for standard interactions.
    func mediumImpact() {
        guard HapticFeedbackService.isEnabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()
    }

    /// Heavy haptic feedback for important interactions like successful check-in.
    func heavyImpact() {
        guard HapticFeedbackService.isEnabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.impactOccurred()
    }

    /// Notification-style haptic feedback for errors or warnings.
    func notificationOccurred(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        guard HapticFeedbackService.isEnabled else { return }
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(type)
    }

    /// Success feedback (common for check-in completion).
    func success() {
        notificationOccurred(.success)
    }

    /// Error feedback (for failed operations).
    func error() {
        notificationOccurred(.error)
    }

    /// Warning feedback.
    func warning() {
        notificationOccurred(.warning)
    }

    /// Selection feedback (for haptic interaction with selection changes).
    func selectionChanged() {
        guard HapticFeedbackService.isEnabled else { return }
        let generator = UISelectionFeedbackGenerator()
        generator.selectionChanged()
    }
}
