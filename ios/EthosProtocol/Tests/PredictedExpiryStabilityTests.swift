import XCTest
@testable import EthosProtocol

// #789: the predicted check-in deadline must be a fixed point in time, not re-anchored to
// "now" each time the view evaluates it.
final class PredictedExpiryStabilityTests: XCTestCase {

    func test_predictedExpiry_isStableAcrossEvaluations_withCachedTTL() {
        let fetchedAt = Date(timeIntervalSince1970: 1_800_000_000)
        let vault = Vault(id: "v1", owner: "a", beneficiary: "b", balance: 1,
                          checkInInterval: 86_400, lastCheckIn: fetchedAt,
                          ttlRemaining: 43_200, status: .active)
        var cached = vault
        cached.ttlFetchedAt = fetchedAt

        let first = cached.predictedExpiryDate
        Thread.sleep(forTimeInterval: 0.05)
        let second = cached.predictedExpiryDate

        XCTAssertEqual(first, second, "Offline re-renders must not move the deadline")
        XCTAssertEqual(first, fetchedAt.addingTimeInterval(43_200))
    }
}
