import XCTest
@testable import EthosProtocol

final class SessionLockServiceTests: XCTestCase {

    // MARK: - testNoLockBeforeTimeout

    /// When the app foregrounds before the timeout has elapsed, the session
    /// must NOT be locked.
    func testNoLockBeforeTimeout() {
        // Seed lastActivityTime to 60 s ago with a 300 s timeout — well within limit.
        let service = SessionLockService(
            timeoutInterval: 300,
            lastActivityTime: Date().addingTimeInterval(-60)
        )

        service.handleForeground()

        XCTAssertFalse(service.isLocked,
            "Session should NOT be locked when only 60 s have elapsed with a 300 s timeout")
    }

    // MARK: - testLockAfterTimeout

    /// When the app foregrounds after the timeout has been exceeded, the session
    /// MUST be locked.
    func testLockAfterTimeout() {
        // Seed lastActivityTime to 301 s ago with a 300 s timeout — just over the limit.
        let service = SessionLockService(
            timeoutInterval: 300,
            lastActivityTime: Date().addingTimeInterval(-301)
        )

        service.handleForeground()

        XCTAssertTrue(service.isLocked,
            "Session MUST be locked when 301 s have elapsed with a 300 s timeout")
    }

    /// Boundary: elapsed time exactly equal to the timeout should also lock.
    func testLockAtExactTimeout() {
        let service = SessionLockService(
            timeoutInterval: 300,
            lastActivityTime: Date().addingTimeInterval(-300)
        )

        service.handleForeground()

        XCTAssertTrue(service.isLocked,
            "Session MUST be locked when elapsed time equals the timeout exactly")
    }

    // MARK: - testUnlockResetsState

    /// After `unlock()` is called, `isLocked` must be `false` and the inactivity
    /// clock must be reset so that a subsequent immediate foreground event does
    /// not re-lock the session.
    func testUnlockResetsState() {
        // Start already locked (elapsed time way past timeout).
        let service = SessionLockService(
            timeoutInterval: 300,
            lastActivityTime: Date().addingTimeInterval(-1_000)
        )
        service.handleForeground()
        XCTAssertTrue(service.isLocked, "Pre-condition: service should be locked")

        service.unlock()

        XCTAssertFalse(service.isLocked, "isLocked should be false immediately after unlock()")

        // Simulate an almost-immediate foreground (0 s elapsed after unlock).
        service.handleForeground()
        XCTAssertFalse(service.isLocked,
            "Session should NOT re-lock immediately after unlock() resets the clock")
    }

    // MARK: - testRecordActivityPreventsLock

    /// `recordActivity()` resets the clock; a foreground check after recording
    /// activity should not lock even if the original lastActivityTime was stale.
    func testRecordActivityPreventsLock() {
        let service = SessionLockService(
            timeoutInterval: 300,
            lastActivityTime: Date().addingTimeInterval(-1_000)
        )

        // User interacts — clock is reset to now.
        service.recordActivity()
        service.handleForeground()

        XCTAssertFalse(service.isLocked,
            "Session should NOT lock after recordActivity() refreshes the clock")
    }

    // MARK: - testHandleBackgroundRefreshesClock

    /// `handleBackground()` should refresh `lastActivityTime` so that the gap
    /// measured on the next foreground is relative to when the app was backgrounded,
    /// not some earlier interaction.
    func testHandleBackgroundRefreshesClock() {
        // Start with an old lastActivityTime.
        let service = SessionLockService(
            timeoutInterval: 300,
            lastActivityTime: Date().addingTimeInterval(-1_000)
        )

        // App goes to background now — clock is reset.
        service.handleBackground()
        // App immediately returns to foreground (< 1 s elapsed since background).
        service.handleForeground()

        XCTAssertFalse(service.isLocked,
            "handleBackground() should reset the clock so a brief background trip doesn't lock")
    }
}
