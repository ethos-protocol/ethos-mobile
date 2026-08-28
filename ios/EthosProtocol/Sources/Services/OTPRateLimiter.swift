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
///
/// ## Persistence (#171)
///
/// The failure count and the cooldown *deadline* (an absolute timestamp) are
/// persisted in `UserDefaults`, keyed by [scope], and restored on `init`. The
/// limiter is held as a `@StateObject` in `TwoFactorVerifyView`, whose identity
/// is recreated on ordinary navigation — dismissing and re-presenting the
/// verification screen, no app kill required. Without persistence that reset the
/// escalating cooldown to zero between guesses, which made the throttle
/// bypassable. State now survives view recreation and process restarts; it is a
/// throttle, not secret material, so `UserDefaults` (not the Keychain) is the
/// right store, and it remains a defense-in-depth complement to server-side
/// rate limiting rather than a substitute for it.
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
    var makeTimer: (_ interval: TimeInterval, _ handler: @escaping () -> Void) -> TimerToken =
        OTPRateLimiter.scheduleRunLoopTimer

    private var timerToken: TimerToken?

    // MARK: - Persistence

    private let store: UserDefaults
    private let failureCountKey: String
    private let deadlineKey: String

    /// - Parameters:
    ///   - scope: Namespace for the persisted keys — one throttle per flow (or
    ///     per vault, by passing the vault ID).
    ///   - store: Backing store, overridable so tests stay isolated from
    ///     `UserDefaults.standard`.
    ///   - now: See [now]; passed here as well so restored state can be
    ///     evaluated against a test clock.
    ///   - makeTimer: See [makeTimer]; passed here as well so restoring an
    ///     in-progress cooldown doesn't schedule a real `Timer` in tests.
    init(
        scope: String = "2fa-verify",
        store: UserDefaults = .standard,
        now: (() -> Date)? = nil,
        makeTimer: ((_ interval: TimeInterval, _ handler: @escaping () -> Void) -> TimerToken)? = nil
    ) {
        self.store = store
        self.failureCountKey = "otp_rate_limiter.\(scope).failure_count"
        self.deadlineKey = "otp_rate_limiter.\(scope).cooldown_deadline"
        if let now { self.now = now }
        if let makeTimer { self.makeTimer = makeTimer }
        restorePersistedState()
    }

    // MARK: - Public API

    /// Records a failed verification attempt and starts the cooldown if appropriate.
    func recordFailure() {
        failureCount += 1
        store.set(failureCount, forKey: failureCountKey)

        let cooldown = cooldownSeconds(for: failureCount)
        guard cooldown > 0 else { return }
        store.set(now().addingTimeInterval(TimeInterval(cooldown)).timeIntervalSince1970,
                  forKey: deadlineKey)
        startCooldown(seconds: cooldown)
    }

    /// Resets all state — call this on a successful OTP verification.
    /// Clears the persisted state too, so a legitimate user who eventually
    /// enters the correct code is not throttled by leftover failures.
    func reset() {
        failureCount = 0
        cooldownSecondsRemaining = 0
        timerToken?.cancel()
        timerToken = nil
        store.removeObject(forKey: failureCountKey)
        store.removeObject(forKey: deadlineKey)
    }

    // MARK: - Cooldown schedule

    nonisolated static func cooldownSeconds(for failures: Int) -> Int {
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

    /// Rebuilds in-memory state from the store, so a limiter created by a
    /// recreated view resumes any cooldown still in progress rather than
    /// starting from zero failures.
    private func restorePersistedState() {
        failureCount = store.integer(forKey: failureCountKey)

        guard let deadline = store.object(forKey: deadlineKey) as? TimeInterval else { return }
        let remaining = Int((deadline - now().timeIntervalSince1970).rounded(.up))
        if remaining > 0 {
            startCooldown(seconds: remaining)
        } else {
            store.removeObject(forKey: deadlineKey)
        }
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
                    // The deadline has passed; drop it so a later instance doesn't
                    // re-evaluate it. The failure count is kept so the schedule keeps
                    // escalating until a successful verification calls reset().
                    self.store.removeObject(forKey: self.deadlineKey)
                }
            }
        }
    }

    /// Default [makeTimer] implementation — a repeating main-run-loop `Timer`.
    nonisolated private static func scheduleRunLoopTimer(
        interval: TimeInterval,
        handler: @escaping () -> Void
    ) -> TimerToken {
        let timer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { _ in handler() }
        return TimerToken(cancel: { timer.invalidate() })
    }
}

// MARK: - TimerToken

/// Cancellable handle returned by `makeTimer`, enabling test injection without
/// requiring a real `Timer`.
struct TimerToken {
    let cancel: () -> Void
}
