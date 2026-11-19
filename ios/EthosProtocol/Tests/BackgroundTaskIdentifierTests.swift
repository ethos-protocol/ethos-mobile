import XCTest
import Foundation
@testable import EthosProtocol

// #772: every identifier passed to BGTaskScheduler must be listed under
// BGTaskSchedulerPermittedIdentifiers in Info.plist, or iOS refuses to
// register/submit the task and it never runs.

final class BackgroundTaskIdentifierTests: XCTestCase {

    /// Identifiers the app registers or submits, sourced from the code itself
    /// so a new task cannot be added without this list being updated.
    private var registeredIdentifiers: [String] {
        [
            BackgroundRefreshService.taskIdentifier,
            CheckInSyncTask.taskIdentifier,
        ]
    }

    private func permittedIdentifiersFromPlist() throws -> [String] {
        // Read the app's Info.plist from the source tree, relative to this file.
        let testFile = URL(fileURLWithPath: #filePath)
        let plistURL = testFile
            .deletingLastPathComponent()      // Tests/
            .deletingLastPathComponent()      // EthosProtocol/
            .appendingPathComponent("EthosProtocol/Info.plist")
        let data = try Data(contentsOf: plistURL)
        let plist = try PropertyListSerialization.propertyList(from: data, format: nil)
        let dict = try XCTUnwrap(plist as? [String: Any])
        return try XCTUnwrap(dict["BGTaskSchedulerPermittedIdentifiers"] as? [String])
    }

    func test_everyRegisteredIdentifier_isPermittedInInfoPlist() throws {
        let permitted = Set(try permittedIdentifiersFromPlist())
        for identifier in registeredIdentifiers {
            XCTAssertTrue(
                permitted.contains(identifier),
                "\(identifier) is used with BGTaskScheduler but missing from BGTaskSchedulerPermittedIdentifiers"
            )
        }
    }

    func test_checkInSyncIdentifier_isPermittedInInfoPlist() throws {
        let permitted = try permittedIdentifiersFromPlist()
        XCTAssertTrue(permitted.contains("app.ethos-protocol.checkin-sync"))
    }
}
