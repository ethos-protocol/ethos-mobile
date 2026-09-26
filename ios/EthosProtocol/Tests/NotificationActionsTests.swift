import XCTest
import UserNotifications
@testable import EthosProtocol

final class NotificationActionsTests: XCTestCase {
    let notificationService = NotificationService.shared

    func testNotificationCategoryRegistration() {
        notificationService.registerNotificationCategories()

        let expectation = XCTestExpectation(description: "Notification categories retrieved")

        UNUserNotificationCenter.current().getNotificationCategories { categories in
            let checkInCategory = categories.first { $0.identifier == "CHECK_IN" }
            XCTAssertNotNil(checkInCategory, "CHECK_IN category should be registered")

            if let category = checkInCategory {
                let actionIdentifiers = category.actions.map { $0.identifier }
                XCTAssertTrue(actionIdentifiers.contains("CHECK_IN_ACTION"))
                XCTAssertTrue(actionIdentifiers.contains("SNOOZE_7_DAYS"))
                XCTAssertTrue(actionIdentifiers.contains("SNOOZE_14_DAYS"))
            }

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 2.0)
    }

    func testCheckInActionOptions() {
        notificationService.registerNotificationCategories()

        let expectation = XCTestExpectation(description: "Check-in action verified")

        UNUserNotificationCenter.current().getNotificationCategories { categories in
            let checkInCategory = categories.first { $0.identifier == "CHECK_IN" }
            let checkInAction = checkInCategory?.actions.first { $0.identifier == "CHECK_IN_ACTION" }

            XCTAssertNotNil(checkInAction)
            if let action = checkInAction {
                XCTAssertTrue(action.options.contains(.foreground))
                XCTAssertTrue(action.options.contains(.authenticationRequired))
            }

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 2.0)
    }

    func testSnoozeActionsExist() {
        notificationService.registerNotificationCategories()

        let expectation = XCTestExpectation(description: "Snooze actions verified")

        UNUserNotificationCenter.current().getNotificationCategories { categories in
            let checkInCategory = categories.first { $0.identifier == "CHECK_IN" }
            let actions = checkInCategory?.actions ?? []

            let snooze7Action = actions.first { $0.identifier == "SNOOZE_7_DAYS" }
            let snooze14Action = actions.first { $0.identifier == "SNOOZE_14_DAYS" }

            XCTAssertNotNil(snooze7Action, "SNOOZE_7_DAYS action should exist")
            XCTAssertNotNil(snooze14Action, "SNOOZE_14_DAYS action should exist")

            if let action = snooze7Action {
                XCTAssertEqual(action.title, "Snooze 7 days")
            }

            if let action = snooze14Action {
                XCTAssertEqual(action.title, "Snooze 14 days")
            }

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 2.0)
    }

    func testCheckInReminderNotification() {
        let vaultID = "GTEST123456789"
        let ttlRemaining: UInt64 = 43_200 // 12 hours

        notificationService.scheduleCheckInReminder(
            vaultID: vaultID,
            vaultName: vaultID,
            ttlRemaining: ttlRemaining,
            checkInInterval: 86_400
        )

        let expectation = XCTestExpectation(description: "Notification scheduled")

        UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
            let primaryRequest = requests.first { $0.identifier == "checkin-primary-\(vaultID)" }
            XCTAssertNotNil(primaryRequest, "Primary reminder should be scheduled")

            if let request = primaryRequest {
                XCTAssertEqual(request.content.categoryIdentifier, "CHECK_IN")
                XCTAssertTrue(request.content.body.contains(vaultID.prefix(12)))
            }

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 2.0)
    }

    func testTTLWarningNotification() {
        let vaultID = "GTEST987654321"
        let ttlRemaining: UInt64 = 3_600 // 1 hour

        notificationService.scheduleTTLWarning(vaultID: vaultID, ttlRemaining: ttlRemaining)

        let expectation = XCTestExpectation(description: "TTL warning scheduled")

        UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
            let warningRequest = requests.first { $0.identifier == "ttl-warning-\(vaultID)" }
            XCTAssertNotNil(warningRequest, "TTL warning should be scheduled")

            if let request = warningRequest {
                XCTAssertEqual(request.content.categoryIdentifier, "CHECK_IN")
                XCTAssertTrue(request.content.title.contains("Expiring Soon"))
            }

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 2.0)
    }

    override func tearDown() {
        super.tearDown()
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
    }
}
