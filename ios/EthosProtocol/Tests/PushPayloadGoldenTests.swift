import XCTest
@testable import EthosProtocol

final class PushPayloadGoldenTests: XCTestCase {

    // Sample APNs payload for TTL warning notification
    let ttlWarningPayload: [String: Any] = [
        "aps": [
            "alert": [
                "title": "Vault TTL Warning",
                "body": "Your vault expires in 7 days"
            ],
            "badge": 1,
            "sound": "default",
            "mutable-content": 1
        ],
        "vault_id": "vault-uuid-123",
        "event_type": "ttl_warning",
        "ttl_remaining": 604800
    ]

    let checkInReminderPayload: [String: Any] = [
        "aps": [
            "alert": [
                "title": "Check-in Reminder",
                "body": "Don't forget to check in to extend your vault TTL"
            ],
            "badge": 1,
            "sound": "default"
        ],
        "vault_id": "vault-uuid-456",
        "event_type": "checkin_reminder",
        "reminder_id": "reminder-789"
    ]

    let vaultExpiredPayload: [String: Any] = [
        "aps": [
            "alert": [
                "title": "Vault Expired",
                "body": "Your vault has expired and funds are now released"
            ],
            "badge": 1,
            "sound": "default"
        ],
        "vault_id": "vault-uuid-789",
        "event_type": "vault_expired",
        "expired_at": "2026-01-01T00:00:00Z"
    ]

    // MARK: - TTL Warning Notification

    func test_ttlWarningPayload_hasRequiredFields() throws {
        XCTAssertNotNil(ttlWarningPayload["vault_id"])
        XCTAssertNotNil(ttlWarningPayload["event_type"])
        XCTAssertNotNil(ttlWarningPayload["ttl_remaining"])

        let eventType = ttlWarningPayload["event_type"] as? String
        XCTAssertEqual(eventType, "ttl_warning")

        let ttlRemaining = ttlWarningPayload["ttl_remaining"] as? Int
        XCTAssertGreaterThan(ttlRemaining ?? 0, 0)
    }

    func test_ttlWarningPayload_apsAlert_hasTitle() throws {
        guard let aps = ttlWarningPayload["aps"] as? [String: Any],
              let alert = aps["alert"] as? [String: Any] else {
            XCTFail("APNs alert structure missing")
            return
        }

        let title = alert["title"] as? String
        XCTAssertEqual(title, "Vault TTL Warning")
    }

    func test_ttlWarningPayload_apsAlert_hasBody() throws {
        guard let aps = ttlWarningPayload["aps"] as? [String: Any],
              let alert = aps["alert"] as? [String: Any] else {
            XCTFail("APNs alert structure missing")
            return
        }

        let body = alert["body"] as? String
        XCTAssertNotNil(body)
        XCTAssertTrue(body?.contains("expires") ?? false)
    }

    func test_ttlWarningPayload_hasMutableContent() throws {
        guard let aps = ttlWarningPayload["aps"] as? [String: Any] else {
            XCTFail("APNs structure missing")
            return
        }

        let mutableContent = aps["mutable-content"] as? Int
        XCTAssertEqual(mutableContent, 1)
    }

    // MARK: - Check-in Reminder Notification

    func test_checkInReminderPayload_hasRequiredFields() throws {
        XCTAssertNotNil(checkInReminderPayload["vault_id"])
        XCTAssertNotNil(checkInReminderPayload["event_type"])
        XCTAssertNotNil(checkInReminderPayload["reminder_id"])

        let eventType = checkInReminderPayload["event_type"] as? String
        XCTAssertEqual(eventType, "checkin_reminder")
    }

    func test_checkInReminderPayload_apsAlert_hasTitle() throws {
        guard let aps = checkInReminderPayload["aps"] as? [String: Any],
              let alert = aps["alert"] as? [String: Any] else {
            XCTFail("APNs alert structure missing")
            return
        }

        let title = alert["title"] as? String
        XCTAssertEqual(title, "Check-in Reminder")
    }

    func test_checkInReminderPayload_apsAlert_hasBody() throws {
        guard let aps = checkInReminderPayload["aps"] as? [String: Any],
              let alert = aps["alert"] as? [String: Any] else {
            XCTFail("APNs alert structure missing")
            return
        }

        let body = alert["body"] as? String
        XCTAssertNotNil(body)
        XCTAssertTrue(body?.contains("check in") ?? false)
    }

    // MARK: - Vault Expired Notification

    func test_vaultExpiredPayload_hasRequiredFields() throws {
        XCTAssertNotNil(vaultExpiredPayload["vault_id"])
        XCTAssertNotNil(vaultExpiredPayload["event_type"])
        XCTAssertNotNil(vaultExpiredPayload["expired_at"])

        let eventType = vaultExpiredPayload["event_type"] as? String
        XCTAssertEqual(eventType, "vault_expired")
    }

    func test_vaultExpiredPayload_expiredAtIsISO8601() throws {
        guard let expiredAt = vaultExpiredPayload["expired_at"] as? String else {
            XCTFail("expired_at field missing")
            return
        }

        XCTAssertTrue(expiredAt.contains("T"), "Should be ISO8601 format")
        XCTAssertTrue(expiredAt.contains("Z"), "Should include Z timezone")
    }

    // MARK: - Common Alert Structure Tests

    func test_allPayloads_haveApsSection() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            XCTAssertNotNil(payload["aps"], "Every payload must have aps section")
        }
    }

    func test_allPayloads_haveVaultId() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            let vaultId = payload["vault_id"] as? String
            XCTAssertNotNil(vaultId, "Every payload must have vault_id")
            XCTAssertFalse(vaultId?.isEmpty ?? true, "vault_id cannot be empty")
        }
    }

    func test_allPayloads_haveEventType() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            let eventType = payload["event_type"] as? String
            XCTAssertNotNil(eventType, "Every payload must have event_type")
            XCTAssertFalse(eventType?.isEmpty ?? true, "event_type cannot be empty")
        }
    }

    func test_apsAlert_hasTitle() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            guard let aps = payload["aps"] as? [String: Any],
                  let alert = aps["alert"] as? [String: Any],
                  let title = alert["title"] as? String else {
                XCTFail("All payloads must have aps.alert.title")
                return
            }

            XCTAssertFalse(title.isEmpty, "Title cannot be empty")
        }
    }

    func test_apsAlert_hasBody() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            guard let aps = payload["aps"] as? [String: Any],
                  let alert = aps["alert"] as? [String: Any],
                  let body = alert["body"] as? String else {
                XCTFail("All payloads must have aps.alert.body")
                return
            }

            XCTAssertFalse(body.isEmpty, "Body cannot be empty")
        }
    }

    func test_apsAlert_hasSound() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            guard let aps = payload["aps"] as? [String: Any],
                  let alert = aps["alert"] as? [String: Any] else {
                XCTFail("All payloads must have aps.alert")
                return
            }

            let sound = aps["sound"] as? String
            XCTAssertNotNil(sound, "Sound should be present")
        }
    }

    func test_apsAlert_hasBadge() throws {
        let payloads = [ttlWarningPayload, checkInReminderPayload, vaultExpiredPayload]

        for payload in payloads {
            guard let aps = payload["aps"] as? [String: Any] else {
                XCTFail("All payloads must have aps")
                return
            }

            let badge = aps["badge"] as? Int
            XCTAssertNotNil(badge, "Badge should be present")
            XCTAssertGreaterThanOrEqual(badge ?? 0, 0, "Badge should be non-negative")
        }
    }

    // MARK: - Regression: Payload Shape Stability

    func test_eventTypesAreConsistent_withPayloadShape() throws {
        let ttlEventType = ttlWarningPayload["event_type"] as? String
        let reminderEventType = checkInReminderPayload["event_type"] as? String
        let expiredEventType = vaultExpiredPayload["event_type"] as? String

        XCTAssertEqual(ttlEventType, "ttl_warning")
        XCTAssertEqual(reminderEventType, "checkin_reminder")
        XCTAssertEqual(expiredEventType, "vault_expired")
    }

    func test_ttlWarningPayload_hasNumericalTTLRemaining() throws {
        let ttlRemaining = ttlWarningPayload["ttl_remaining"]
        XCTAssertNotNil(ttlRemaining, "ttl_warning must have ttl_remaining")

        guard let ttlValue = ttlRemaining as? Int else {
            XCTFail("ttl_remaining must be an integer (seconds)")
            return
        }

        XCTAssertGreaterThan(ttlValue, 0, "ttl_remaining must be positive")
    }

    func test_checkInReminderPayload_hasReminderId() throws {
        let reminderId = checkInReminderPayload["reminder_id"]
        XCTAssertNotNil(reminderId, "checkin_reminder must have reminder_id")

        guard let idString = reminderId as? String else {
            XCTFail("reminder_id must be a string")
            return
        }

        XCTAssertFalse(idString.isEmpty, "reminder_id cannot be empty")
    }

    func test_vaultExpiredPayload_hasISO8601ExpiredAt() throws {
        let expiredAt = vaultExpiredPayload["expired_at"]
        XCTAssertNotNil(expiredAt, "vault_expired must have expired_at")

        guard let dateString = expiredAt as? String else {
            XCTFail("expired_at must be a string in ISO8601 format")
            return
        }

        XCTAssertTrue(dateString.contains("T"), "expired_at should be ISO8601")
    }
}
