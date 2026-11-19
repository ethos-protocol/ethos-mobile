import XCTest
@testable import EthosProtocol

/// #773: background submit failures are counted per BGTaskScheduler error code.
final class BackgroundSubmitFailureTests: XCTestCase {

    func test_recordBackgroundSubmitFailure_countsEachErrorCodeSeparately() {
        let monitor = PerformanceMonitor.shared
        monitor.reset()

        monitor.recordBackgroundSubmitFailure(code: 1)
        monitor.recordBackgroundSubmitFailure(code: 1)
        monitor.recordBackgroundSubmitFailure(code: 3)

        XCTAssertEqual(monitor.backgroundSubmitFailureCounts(), [1: 2, 3: 1])
        monitor.reset()
    }
}
