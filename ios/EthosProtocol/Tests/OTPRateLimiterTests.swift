import XCTest
@testable import EthosProtocol

// MARK: - #119 OTPRateLimiter Tests

final class OTPRateLimiterTests: XCTestCase {

    // ── Cooldown schedule ─────────────────────────────────────────────────

    func test_cooldownSchedule_1Failure_noCooldown() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 1), 0)
    }

    func test_cooldownSchedule_2Failures_noCooldown() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 2), 0)
    }

    func test_cooldownSchedule_3Failures_30s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 3), 30)
    }

    func test_cooldownSchedule_4Failures_60s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 4), 60)
    }

    func test_cooldownSchedule_5Failures_120s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 5), 120)
    }

    func test_cooldownSchedule_10Failures_capped120s() {
        XCTAssertEqual(OTPRateLimiter.cooldownSeconds(for: 10), 120,
            "Cooldown must be capped at 120 s regardless of failure count")
    }

    // ── Failure recording ─────────────────────────────────────────────────

    @MainActor
    func test_recordFailure_incrementsCount() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()
        XCTAssertEqual(limiter.failureCount, 1)

        limiter.recordFailure()
        XCTAssertEqual(limiter.failureCount, 2)
    }

    @MainActor
    func test_recordFailure_below3_noBlock() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()  // 1
        XCTAssertFalse(limiter.isBlocked)

        limiter.recordFailure()  // 2
        XCTAssertFalse(limiter.isBlocked)
    }

    @MainActor
    func test_recordFailure_at3_startsCooldown() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()  // 1
        limiter.recordFailure()  // 2
        limiter.recordFailure()  // 3

        XCTAssertTrue(limiter.isBlocked,
            "After 3 failures the limiter should block further attempts")
        XCTAssertEqual(limiter.cooldownSecondsRemaining, 30)
    }

    @MainActor
    func test_recordFailure_at4_starts60sCooldown() async {
        let limiter = makeTestLimiter()

        for _ in 1...4 { limiter.recordFailure() }

        XCTAssertTrue(limiter.isBlocked)
        XCTAssertEqual(limiter.cooldownSecondsRemaining, 60)
    }

    @MainActor
    func test_recordFailure_at5_starts120sCooldown() async {
        let limiter = makeTestLimiter()

        for _ in 1...5 { limiter.recordFailure() }

        XCTAssertTrue(limiter.isBlocked)
        XCTAssertEqual(limiter.cooldownSecondsRemaining, 120)
    }

    // ── Reset on success ──────────────────────────────────────────────────

    @MainActor
    func test_reset_clearsFailureCount() async {
        let limiter = makeTestLimiter()

        limiter.recordFailure()
        limiter.recordFailure()
        limiter.reset()

        XCTAssertEqual(limiter.failureCount, 0,
            "reset() must clear the failure counter")
    }

    @MainActor
    func test_reset_clearsCooldown() async {
        let limiter = makeTestLimiter()

        for _ in 1...3 { limiter.recordFailure() }
        XCTAssertTrue(limiter.isBlocked, "Precondition: should be blocked after 3 failures")

        limiter.reset()

        XCTAssertEqual(limiter.cooldownSecondsRemaining, 0,
            "reset() must clear the cooldown immediately")
        XCTAssertFalse(limiter.isBlocked,
            "reset() must unblock the limiter")
    }

    @MainActor
    func test_reset_allowsImmediateAttemptAfterBlock() async {
        let limiter = makeTestLimiter()

        for _ in 1...5 { limiter.recordFailure() }
        limiter.reset()

        // After reset, a new failure should start from scratch (1 failure, no cooldown)
        limiter.recordFailure()
        XCTAssertEqual(limiter.failureCount, 1)
        XCTAssertFalse(limiter.isBlocked,
            "First failure after reset should not trigger a cooldown")
    }

    // ── Timer injection ───────────────────────────────────────────────────

    /// Verifies that the injected timer is actually called (i.e., the limiter
    /// requests a tick-down) when a cooldown starts.
    @MainActor
    func test_cooldownStarted_firesTimer() async {
        var timerFired = false
        let limiter = makeTestLimiter(onTimerCreated: { _, _ in timerFired = true })

        for _ in 1...3 { limiter.recordFailure() }

        XCTAssertTrue(timerFired,
            "Limiter must schedule a tick-down timer when cooldown starts")
    }

    // ── Escalating sequence ───────────────────────────────────────────────

    @MainActor
    func test_escalatingSequence_cooldownIncreasesWith4thFailure() async {
        let limiter = makeTestLimiter()

        for _ in 1...3 { limiter.recordFailure() }
        let firstCooldown = limiter.cooldownSecondsRemaining

        limiter.reset()
        for _ in 1...4 { limiter.recordFailure() }
        let secondCooldown = limiter.cooldownSecondsRemaining

        XCTAssertGreaterThan(secondCooldown, firstCooldown,
            "4th failure cooldown must be longer than 3rd failure cooldown")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /// Creates an `OTPRateLimiter` with a no-op timer (no real `Timer` is scheduled),
    /// keeping tests synchronous and free of side effects.
    @MainActor
    private func makeTestLimiter(
        onTimerCreated: ((_ interval: TimeInterval, _ handler: @escaping () -> Void) -> Void)? = nil
    ) -> OTPRateLimiter {
        let limiter = OTPRateLimiter()
        limiter.makeTimer = { interval, handler in
            onTimerCreated?(interval, handler)
            return TimerToken(cancel: {})
        }
        return limiter
    }
}
