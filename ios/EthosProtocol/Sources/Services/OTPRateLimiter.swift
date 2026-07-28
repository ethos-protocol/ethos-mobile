import Foundation

// MARK: - #119 OTP Rate Limiter

/// Client-side rate limiter for OTP verification attempts.
///
/// A 6-digit TOTP has only 1,000,000 possible values. Without any client-side
/// throttle, an attacker whose server-side rate limiting is weak or misconfigured
/// could brute-force the keyspace quickly. This limiter adds an escalating cooldown
/// after repeated failures, surfacing the remaining wait time in the UI.
///
/// Cooldown schedule (cumulative failures → cooldown in seconds):
///   1–2 failures  → no cooldown (grace period)
///   3 failures    → 30 s
///   4 failures    → 60 s
///   5+ failures   → 120 s (capped)
///
/// A successful verification resets all counters.
@MainActor
final class OTPRateLimiter: ObservableObject {

    // MARK: - State

    @Published private(set) var failureCount: Int = 0
    @Published private(set) var cooldownSecondsRemaining: Int = 0

    var isBlocked: Bool { cooldownSecondsRemaining > 0 }

    // MARK: - Dependencies (injectable for testing)

    /// Provides the current time. Overridable in tests.
    var now: () -> Date = { Date() }

    /// Schedules a repeated action on the main run-loop. Overridable in tests.
    var makeTimer: (_ interval: TimeInterval, _ handler: @escaping () -> Void) -> TimerToken = {
        interval, handler in
        let timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { _ in handler() }
        return TimerToken(cancel: { timer.invalidate() })
    }

    private var timerToken: TimerToken?

    // MARK: - Public API

    /// Records a failed verification attempt and starts the cooldown if appropriate.
    func recordFailure() {
        failureCount += 1
        let cooldown = cooldownSeconds(for: failureCount)
        guard cooldown > 0 else { return }
        startCooldown(seconds: cooldown)
    }

    /// Resets all state — call this on a successful OTP verification.
    func reset() {
        failureCount = 0
        cooldownSecondsRemaining = 0
        timerToken?.cancel()
        timerToken = nil
    }

    // MARK: - Cooldown schedule

    static func cooldownSeconds(for failures: Int) -> Int {
        switch failures {
        case ..<3:   return 0
        case 3:      return 30
        case 4:      return 60
        default:     return 120
        }
    }

    // MARK: - Internals

    private func cooldownSeconds(for failures: Int) -> Int {
        Self.cooldownSeconds(for: failures)
    }

    private func startCooldown(seconds: Int) {
        timerToken?.cancel()
        cooldownSecondsRemaining = seconds
        timerToken = makeTimer(1.0) { [weak self] in
            guard let self else { return }
            Task { @MainActor in
                if self.cooldownSecondsRemaining > 0 {
                    self.cooldownSecondsRemaining -= 1
                } else {
                    self.timerToken?.cancel()
                    self.timerToken = nil
                }
            }
        }
    }
}

// MARK: - TimerToken

/// Cancellable handle returned by `makeTimer`, enabling test injection without
/// requiring a real `Timer`.
struct TimerToken {
    let cancel: () -> Void
}
