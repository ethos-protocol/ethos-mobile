import XCTest
@testable import EthosProtocol

/// #827 — BiometricTimeoutIndicatorService tracks the remaining time before
/// biometric re-authentication is required.
/// Tests: countdown behavior and the expiry transition.
final class BiometricTimeoutIndicatorServiceTests: XCTestCase {

    // BiometricTimeoutIndicatorService.init is private; tests use the shared singleton.
    private var service: BiometricTimeoutIndicatorService { BiometricTimeoutIndicatorService.shared }

    override func setUp() {
        super.setUp()
        // Reset to a known stopped state before each test.
        service.stopTimeout()
        ReLockTimeoutOption.current = .thirtySeconds
    }

    override func tearDown() {
        service.stopTimeout()
        super.tearDown()
    }

    // MARK: - Countdown

    func testStartTimeoutActivatesService() {
        service.startTimeout()

        XCTAssertTrue(service.isActive)
        XCTAssertGreaterThan(service.totalTimeoutSeconds, 0)
    }

    func testStopTimeoutDeactivatesService() {
        service.startTimeout()
        service.stopTimeout()

        XCTAssertFalse(service.isActive)
        XCTAssertEqual(service.remainingSeconds, 0)
        XCTAssertEqual(service.totalTimeoutSeconds, 0)
    }

    func testRemainingSecondsWithinBoundsAfterStart() {
        ReLockTimeoutOption.current = .thirtySeconds
        service.startTimeout()

        XCTAssertLessThanOrEqual(service.remainingSeconds, 30)
        XCTAssertGreaterThanOrEqual(service.remainingSeconds, 0)
    }

    func testProgressFractionIsOneAtStart() {
        ReLockTimeoutOption.current = .thirtySeconds
        service.startTimeout()

        // Immediately after start the fraction should be very close to 1.0.
        XCTAssertEqual(service.progressFraction, 1.0, accuracy: 0.1)
    }

    func testProgressFractionIsOneWhenTotalIsZero() {
        // Before startTimeout() totalTimeoutSeconds is 0 — guard returns 1.0.
        XCTAssertEqual(service.progressFraction, 1.0)
    }

    func testResetTimeoutRestartsClock() {
        service.startTimeout()
        let firstTotal = service.totalTimeoutSeconds

        service.resetTimeout()

        XCTAssertTrue(service.isActive)
        XCTAssertEqual(service.totalTimeoutSeconds, firstTotal)
    }

    // MARK: - Expiry transition

    func testNeverOptionSetsRemainingToIntMax() {
        ReLockTimeoutOption.current = .never
        service.startTimeout()

        XCTAssertEqual(service.remainingSeconds, Int.max)
        XCTAssertTrue(service.isActive)
    }

    func testNeverOptionProgressFractionIsOne() {
        ReLockTimeoutOption.current = .never
        service.startTimeout()

        // With .never, totalTimeoutSeconds is 0 (infinity guard path), so fraction returns 1.0.
        XCTAssertEqual(service.progressFraction, 1.0)
    }

    func testStopTimeoutFromInactiveStateIsNoop() {
        // Calling stop without start must not crash.
        service.stopTimeout()

        XCTAssertFalse(service.isActive)
        XCTAssertEqual(service.remainingSeconds, 0)
    }
}
