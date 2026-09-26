import Foundation
import Combine

/// Tracks and displays the remaining time before biometric re-authentication is required.
/// Shows a countdown timer so users know how much time they have left.
final class BiometricTimeoutIndicatorService: ObservableObject {
    static let shared = BiometricTimeoutIndicatorService()

    /// The remaining seconds before re-lock is required. Updates every second.
    @Published private(set) var remainingSeconds: Int = 0

    /// Whether the timeout is currently active (user is authenticated and timer is running).
    @Published private(set) var isActive: Bool = false

    /// The total timeout duration in seconds (for progress calculation).
    @Published private(set) var totalTimeoutSeconds: Int = 0

    /// Progress value between 0 and 1 for UI indicators (1.0 = full time, 0.0 = time expired).
    var progressFraction: Double {
        guard totalTimeoutSeconds > 0 else { return 1.0 }
        return Double(remainingSeconds) / Double(totalTimeoutSeconds)
    }

    private var timer: Timer?
    private var unlockTime: Date?

    private init() {}

    /// Start the timeout countdown. Called when user successfully authenticates.
    func startTimeout() {
        unlockTime = Date()
        totalTimeoutSeconds = Int(ReLockTimeoutOption.current.seconds)
        isActive = true
        startTimer()
    }

    /// Stop the timeout countdown. Called when re-lock is triggered.
    func stopTimeout() {
        isActive = false
        remainingSeconds = 0
        totalTimeoutSeconds = 0
        stopTimer()
    }

    /// Reset the timeout (extend the session).
    func resetTimeout() {
        startTimeout()
    }

    private func startTimer() {
        stopTimer()

        // Update immediately
        updateRemaining()

        // Then update every second
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.updateRemaining()
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func updateRemaining() {
        guard let unlockTime = unlockTime, isActive else {
            isActive = false
            return
        }

        let timeout = ReLockTimeoutOption.current.seconds
        guard timeout.isFinite else {
            // "Never" re-lock option
            remainingSeconds = Int.max
            return
        }

        let elapsed = Date().timeIntervalSince(unlockTime)
        let remaining = max(0, Int(timeout) - Int(elapsed))

        remainingSeconds = remaining

        if remaining <= 0 {
            stopTimeout()
        }
    }

    deinit {
        stopTimer()
    }
}
