import XCTest
@testable import EthosProtocol

/// #825 — VaultActionLog records vault actions for the activity history.
/// Tests: ordering, ISO 8601 timestamp format, and the cap.
final class VaultActionLogTests: XCTestCase {

    private let testVaultId = "test-vault-action-log"
    private let testVaultId2 = "test-vault-action-log-2"

    override func setUp() {
        super.setUp()
        VaultActionLog.clearHistory(for: testVaultId)
        VaultActionLog.clearHistory(for: testVaultId2)
    }

    override func tearDown() {
        VaultActionLog.clearHistory(for: testVaultId)
        VaultActionLog.clearHistory(for: testVaultId2)
        super.tearDown()
    }

    // MARK: - Ordering

    func testHistoryReturnsMostRecentFirst() {
        VaultActionLog.record(.view, for: testVaultId)
        VaultActionLog.record(.deposit, for: testVaultId)
        VaultActionLog.record(.withdraw, for: testVaultId)

        let history = VaultActionLog.history(for: testVaultId)

        XCTAssertEqual(history.count, 3)
        // Most recent entry (withdraw) must come first.
        XCTAssertEqual(history[0].type, .withdraw)
        XCTAssertEqual(history[1].type, .deposit)
        XCTAssertEqual(history[2].type, .view)
    }

    func testHistoryIsDescendingByTimestamp() {
        VaultActionLog.record(.checkIn, for: testVaultId)
        VaultActionLog.record(.view, for: testVaultId)

        let history = VaultActionLog.history(for: testVaultId)

        XCTAssertEqual(history.count, 2)
        XCTAssertGreaterThanOrEqual(
            history[0].timestamp,
            history[1].timestamp,
            "Entries must be sorted most-recent first"
        )
    }

    func testHistoryOnlyReturnsEntriesForRequestedVault() {
        VaultActionLog.record(.deposit, for: testVaultId)
        VaultActionLog.record(.withdraw, for: testVaultId2)

        let history = VaultActionLog.history(for: testVaultId)

        XCTAssertEqual(history.count, 1)
        XCTAssertEqual(history[0].vaultId, testVaultId)
    }

    func testHistoryIsEmptyAfterClear() {
        VaultActionLog.record(.view, for: testVaultId)
        VaultActionLog.clearHistory(for: testVaultId)

        XCTAssertTrue(VaultActionLog.history(for: testVaultId).isEmpty)
    }

    // MARK: - ISO 8601 timestamp format

    func testExportCSVContainsISO8601Timestamp() {
        VaultActionLog.record(.deposit, for: testVaultId)

        let csv = VaultActionLog.exportCSV(for: testVaultId)
        let lines = csv.components(separatedBy: "\n")

        // Header + one data row.
        XCTAssertEqual(lines.count, 2)

        let dataLine = lines[1]
        let columns = dataLine.components(separatedBy: ",")
        XCTAssertEqual(columns.count, 3)

        let timestampString = columns[2]
        // ISO 8601 format: e.g. "2024-01-15T10:30:00Z"
        let formatter = ISO8601DateFormatter()
        let parsed = formatter.date(from: timestampString)
        XCTAssertNotNil(parsed, "Timestamp '\(timestampString)' must be valid ISO 8601")
    }

    func testExportCSVHeader() {
        let csv = VaultActionLog.exportCSV(for: testVaultId)
        let firstLine = csv.components(separatedBy: "\n").first
        XCTAssertEqual(firstLine, "vaultId,type,timestamp")
    }

    func testExportCSVContainsCorrectVaultIdAndType() {
        VaultActionLog.record(.checkIn, for: testVaultId)

        let csv = VaultActionLog.exportCSV(for: testVaultId)
        let lines = csv.components(separatedBy: "\n")
        let dataLine = lines[1]
        let columns = dataLine.components(separatedBy: ",")

        XCTAssertEqual(columns[0], testVaultId)
        XCTAssertEqual(columns[1], "checkIn")
    }

    // MARK: - Cap

    func testCapDropsOldestEntriesWhenExceeded() {
        // Record more than 2000 entries — the cap is 2000.
        let overCap = 2_010
        for _ in 0..<overCap {
            VaultActionLog.record(.view, for: testVaultId)
        }

        let history = VaultActionLog.history(for: testVaultId)
        XCTAssertEqual(history.count, 2_000, "History must be capped at 2000 entries")
    }

    func testCapPreservesNewestEntries() {
        // Fill to cap with .view actions, then add a .withdraw.
        for _ in 0..<2_000 {
            VaultActionLog.record(.view, for: testVaultId)
        }
        VaultActionLog.record(.withdraw, for: testVaultId)

        let history = VaultActionLog.history(for: testVaultId)
        XCTAssertEqual(history.count, 2_000)
        // The most recent entry (withdraw) must be preserved at the front.
        XCTAssertEqual(history[0].type, .withdraw)
    }
}
