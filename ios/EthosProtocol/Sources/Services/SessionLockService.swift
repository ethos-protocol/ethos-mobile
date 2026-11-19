import Foundation
import Combine

/// Tracks user activity and automatically locks the session after a configurable
/// period of inactivity. The lock is triggered when the app returns to the
/// foreground after being backgrounded, if the elapsed time since the last
/// recorded activity exceeds `timeoutInterval`.
///
/// Usage:
///   1. Instantiate as `@StateObject` in the app entry point.
///   2. Call `recordActivity()` on significant user interactions.
///   3. Call `handleBackground()` when `scenePhase == .background`.
///   4. Call `handleForeground()` when `scenePhase == .active`.
///   5. Call `unlock()` after the user re-authenticates (biometric / passcode).
final class SessionLockService: ObservableObject {

    /// How long (in seconds) of inactivity before the session is locked.
    /// Default is 5 minutes (300 s). Change before the first `handleForeground()`
    /// call to apply a different threshold at app-launch time.
    var timeoutInterval: TimeInterval = 300

    /// `true` when the session is locked and a re-authentication prompt should be shown.
    @Published var isLocked: Bool = false

    /// Monotonic clock reading (seconds since boot) of the most recent user
    /// activity (or unlock). `ProcessInfo.systemUptime` is not affected by manual
    /// clock changes or NTP corrections, unlike `Date()`, so the lock decision
    /// cannot fire early or be skipped because the wall clock moved.
    private var lastActivityTime: TimeInterval

    /// The monotonic clock used for every inactivity measurement.
    static var monotonicNow: TimeInterval { ProcessInfo.processInfo.systemUptime }

    // MARK: - Initializers

    /// Production initializer — `lastActivityTime` starts at the current monotonic time.
    init() {
        self.lastActivityTime = Self.monotonicNow
    }

    /// Testable initializer. Allows tests to seed a past `lastActivityTime`
    /// (a `monotonicNow`-based value) so that `handleForeground()` can be called
    /// immediately without actually waiting `timeoutInterval` seconds.
    internal init(timeoutInterval: TimeInterval, lastActivityTime: TimeInterval) {
        self.timeoutInterval = timeoutInterval
        self.lastActivityTime = lastActivityTime
    }

    // MARK: - Public API

    /// Records that the user performed an action right now, resetting the
    /// inactivity clock. Call this on meaningful interactions (e.g. tapping
    /// a vault row, submitting a form) to prevent premature lock-out during
    /// active use.
    func recordActivity() {
        lastActivityTime = Self.monotonicNow
    }

    /// Call when `scenePhase` transitions to `.background`. Records the current
    /// time so that the elapsed interval can be computed when the app returns
    /// to the foreground.
    func handleBackground() {
        recordActivity()
    }

    /// Call when `scenePhase` transitions to `.active`. Compares the current
    /// time against `lastActivityTime`; if the gap is at or above
    /// `timeoutInterval`, `isLocked` is set to `true`.
    func handleForeground() {
        let elapsed = Self.monotonicNow - lastActivityTime
        if elapsed >= timeoutInterval {
            isLocked = true
        }
    }

    /// Clears the lock and resets the inactivity clock. Call after the user
    /// successfully re-authenticates.
    func unlock() {
        isLocked = false
        recordActivity()
    }
}
