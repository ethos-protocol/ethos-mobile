import XCTest
@testable import EthosProtocol

/// Covers the local ticking countdown and its reconciliation with fresh server
/// values (#221), including the poll/push disagreement case (#223) — the
/// server-provided value must always win regardless of where the local tick
/// has drifted to.
final class TTLCountdownTests: XCTestCase {

    func test_remaining_ticksDownWithElapsedTime() {
        let start = Date()
        let countdown = TTLCountdown(serverValue: 100, fetchedAt: start)
        XCTAssertEqual(countdown.remaining(at: start.addingTimeInterval(40)), 60)
    }

    func test_remaining_atFetchTime_equalsServerValue() {
        let start = Date()
        let countdown = TTLCountdown(serverValue: 100, fetchedAt: start)
        XCTAssertEqual(countdown.remaining(at: start), 100)
    }

    func test_remaining_neverGoesBelowZero() {
        let start = Date()
        let countdown = TTLCountdown(serverValue: 100, fetchedAt: start)
        XCTAssertEqual(countdown.remaining(at: start.addingTimeInterval(500)), 0)
    }

    func test_reconcile_replacesBaselineWithServerValue() {
        let start = Date()
        var countdown = TTLCountdown(serverValue: 100, fetchedAt: start)

        // Local tick has drifted to 60s remaining...
        let midpoint = start.addingTimeInterval(40)
        XCTAssertEqual(countdown.remaining(at: midpoint), 60)

        // ...but a fresh server value disagrees (e.g. a check-in reset the TTL).
        countdown.reconcile(serverValue: 9_000, at: midpoint)

        XCTAssertEqual(countdown.remaining(at: midpoint), 9_000)
    }

    /// Simulates a poll and a `vault_updated` push disagreeing (#223): whichever
    /// one delivers a fresh server value last is applied as-is — the server
    /// value always wins over the other source, there is no "more correct" one
    /// to prefer, since both ultimately come from the same server-side state.
    func test_reconcile_onPollPushDisagreement_lastServerValueWins() {
        let start = Date()
        var countdown = TTLCountdown(serverValue: 100, fetchedAt: start)

        // A `vault_updated` push arrives first with one value...
        let pushAt = start.addingTimeInterval(10)
        countdown.reconcile(serverValue: 500, at: pushAt)
        XCTAssertEqual(countdown.remaining(at: pushAt), 500)

        // ...then a poll response lands moments later with a different value.
        let pollAt = pushAt.addingTimeInterval(2)
        countdown.reconcile(serverValue: 480, at: pollAt)

        XCTAssertEqual(countdown.serverValue, 480)
        XCTAssertEqual(countdown.remaining(at: pollAt), 480)
    }

    func test_reconcile_ticksDownAgainAfterReconciliation() {
        let start = Date()
        var countdown = TTLCountdown(serverValue: 100, fetchedAt: start)
        countdown.reconcile(serverValue: 200, at: start)
        XCTAssertEqual(countdown.remaining(at: start.addingTimeInterval(50)), 150)
    }
}
