import XCTest
@testable import EthosProtocol

// #831: covers the expiry-trend buckets and the predicted-deadline projection in
// VaultExpiryAnalytics, which used to re-anchor `ttlRemaining` to `Date()` instead of the
// observation time (#789) — so the deadline drifted forward on every render while offline.
final class VaultExpiryAnalyticsTests: XCTestCase {

    private let day: UInt64 = 86_400
    private var week: UInt64 { day * 7 }
    private var month: UInt64 { day * 30 }

    /// A fixed instant, so no assertion depends on the wall clock.
    private let referenceDate = Date(timeIntervalSince1970: 1_800_000_000)

    private func makeVault(
        id: String,
        checkInInterval: UInt64,
        lastCheckIn: Date? = nil,
        ttlRemaining: UInt64? = nil,
        ttlFetchedAt: Date? = nil
    ) -> Vault {
        var vault = Vault(
            id: id,
            owner: "GOWNER",
            beneficiary: "GBENEFICIARY",
            balance: 10_000_000,
            checkInInterval: checkInInterval,
            lastCheckIn: lastCheckIn ?? referenceDate,
            ttlRemaining: ttlRemaining,
            status: .active
        )
        vault.ttlFetchedAt = ttlFetchedAt
        return vault
    }

    // MARK: - Lifespan buckets

    func test_lifespanDistribution_assignsEachBoundaryToItsBucket() {
        let vaults = [
            makeVault(id: "zero", checkInInterval: 0),                 // ≤ 1 day
            makeVault(id: "oneDay", checkInInterval: day),             // ≤ 1 day
            makeVault(id: "oneDayPlus", checkInInterval: day + 1),     // ≤ 1 week
            makeVault(id: "oneWeek", checkInInterval: week),           // ≤ 1 week
            makeVault(id: "oneWeekPlus", checkInInterval: week + 1),   // ≤ 1 month
            makeVault(id: "oneMonth", checkInInterval: month),         // ≤ 1 month
            makeVault(id: "oneMonthPlus", checkInInterval: month + 1)  // > 1 month
        ]

        let buckets = VaultExpiryAnalytics.lifespanDistribution(for: vaults)

        XCTAssertEqual(buckets.map(\.label), ["≤ 1 day", "≤ 1 week", "≤ 1 month", "> 1 month"])
        XCTAssertEqual(buckets.map(\.count), [2, 2, 2, 1])
    }

    func test_lifespanDistribution_emptyListReportsEveryBucketAsZero() {
        let buckets = VaultExpiryAnalytics.lifespanDistribution(for: [])

        XCTAssertEqual(buckets.map(\.label), ["≤ 1 day", "≤ 1 week", "≤ 1 month", "> 1 month"],
                       "Every bucket is always present so the chart's axis cannot jump")
        XCTAssertEqual(buckets.map(\.count), [0, 0, 0, 0])
    }

    // MARK: - Average lifespan

    func test_averageLifespanSeconds_emptyListIsZero() {
        XCTAssertEqual(VaultExpiryAnalytics.averageLifespanSeconds(for: []), 0)
    }

    func test_averageLifespanSeconds_isTheMeanCheckInInterval() {
        let vaults = [
            makeVault(id: "a", checkInInterval: day),
            makeVault(id: "b", checkInInterval: day * 3)
        ]

        XCTAssertEqual(VaultExpiryAnalytics.averageLifespanSeconds(for: vaults),
                       Double(day * 2), accuracy: 0.001)
    }

    // MARK: - Predicted deadlines (#789 alignment)

    func test_upcomingExpirations_anchorsTTLToWhenItWasFetched() {
        let fetchedAt = Date(timeIntervalSince1970: 1_600_000_000)
        let vault = makeVault(id: "v1", checkInInterval: day, ttlRemaining: 43_200,
                              ttlFetchedAt: fetchedAt)

        let upcoming = VaultExpiryAnalytics.upcomingExpirations(for: [vault])

        XCTAssertEqual(upcoming.count, 1)
        XCTAssertEqual(upcoming[0].vaultID, "v1")
        XCTAssertEqual(upcoming[0].predictedDate, fetchedAt.addingTimeInterval(43_200),
                       "Deadline must be ttlFetchedAt + ttlRemaining, not now + ttlRemaining")
    }

    func test_upcomingExpirations_isStableAcrossRepeatedEvaluations() {
        let fetchedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let vault = makeVault(id: "v1", checkInInterval: day, ttlRemaining: 3_600,
                              ttlFetchedAt: fetchedAt)

        let first = VaultExpiryAnalytics.upcomingExpirations(for: [vault]).map(\.predictedDate)
        Thread.sleep(forTimeInterval: 0.05)
        let second = VaultExpiryAnalytics.upcomingExpirations(for: [vault]).map(\.predictedDate)

        XCTAssertEqual(first, second, "Re-rendering must not move the predicted deadline")
    }

    func test_upcomingExpirations_matchesVaultPredictedExpiryDateForEveryVault() {
        let fetchedAt = Date(timeIntervalSince1970: 1_650_000_000)
        let withFetchedAt = makeVault(id: "a", checkInInterval: day, ttlRemaining: day,
                                      ttlFetchedAt: fetchedAt)
        let withoutFetchedAt = makeVault(id: "b", checkInInterval: day, ttlRemaining: 3_600)
        let withoutTTL = makeVault(id: "c", checkInInterval: week, lastCheckIn: fetchedAt)

        let vaults = [withFetchedAt, withoutFetchedAt, withoutTTL]
        let predictedByID = Dictionary(uniqueKeysWithValues: VaultExpiryAnalytics
            .upcomingExpirations(for: vaults)
            .map { ($0.vaultID, $0.predictedDate) })

        for vault in vaults {
            XCTAssertEqual(predictedByID[vault.id], vault.predictedExpiryDate,
                           "Analytics must not drift from Vault.predictedExpiryDate")
        }
    }

    func test_upcomingExpirations_fallsBackToLastCheckInPlusIntervalWithoutTTL() {
        let vault = makeVault(id: "v1", checkInInterval: week, lastCheckIn: referenceDate)

        let upcoming = VaultExpiryAnalytics.upcomingExpirations(for: [vault])

        XCTAssertEqual(upcoming[0].predictedDate,
                       referenceDate.addingTimeInterval(TimeInterval(week)))
    }

    func test_upcomingExpirations_sortsSoonestFirstAndAppliesLimit() {
        let anchor = Date(timeIntervalSince1970: 1_800_000_000)
        // Deliberately out of order.
        let vaults = [
            makeVault(id: "later", checkInInterval: day, ttlRemaining: 86_400, ttlFetchedAt: anchor),
            makeVault(id: "soonest", checkInInterval: day, ttlRemaining: 60, ttlFetchedAt: anchor),
            makeVault(id: "middle", checkInInterval: day, ttlRemaining: 3_600, ttlFetchedAt: anchor),
            makeVault(id: "far", checkInInterval: day, ttlRemaining: 604_800, ttlFetchedAt: anchor)
        ]

        XCTAssertEqual(VaultExpiryAnalytics.upcomingExpirations(for: vaults).map(\.vaultID),
                       ["soonest", "middle", "later", "far"])
        XCTAssertEqual(VaultExpiryAnalytics.upcomingExpirations(for: vaults, limit: 2).map(\.vaultID),
                       ["soonest", "middle"])
    }

    func test_upcomingExpirations_breaksTiesByVaultIDForStableOrdering() {
        let anchor = Date(timeIntervalSince1970: 1_800_000_000)
        let vaults = [
            makeVault(id: "b", checkInInterval: day, ttlRemaining: 3_600, ttlFetchedAt: anchor),
            makeVault(id: "a", checkInInterval: day, ttlRemaining: 3_600, ttlFetchedAt: anchor)
        ]

        XCTAssertEqual(VaultExpiryAnalytics.upcomingExpirations(for: vaults).map(\.vaultID),
                       ["a", "b"])
    }

    func test_upcomingExpirations_emptyListAndZeroLimitAreEmpty() {
        XCTAssertTrue(VaultExpiryAnalytics.upcomingExpirations(for: []).isEmpty)
        XCTAssertTrue(VaultExpiryAnalytics
            .upcomingExpirations(for: [makeVault(id: "v1", checkInInterval: day)], limit: 0)
            .isEmpty)
    }
}
