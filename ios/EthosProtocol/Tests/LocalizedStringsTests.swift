import XCTest
@testable import EthosProtocol

final class LocalizedStringsTests: XCTestCase {

    // MARK: - All Static Keys

    /// Every static property on `LocalizedStrings` paired with its expected key / base English value.
    private var allStaticStrings: [(name: String, value: String, expected: String)] {
        [
            ("checkInReminderTitle", LocalizedStrings.checkInReminderTitle, "Check-in Reminder"),
            ("checkInUrgentTitle", LocalizedStrings.checkInUrgentTitle, "Check-in Urgent"),
            ("queuedCheckInTitle", LocalizedStrings.queuedCheckInTitle, "Check-in queued"),
            ("vaultExpiredTitle", LocalizedStrings.vaultExpiredTitle, "Check-in Failed — Vault Expired"),
            (
                "vaultExpiredBody",
                LocalizedStrings.vaultExpiredBody,
                "A queued check-in was discarded because this vault already expired while you were offline. The vault may have released funds to the beneficiary."
            ),
            ("ttlWarningTitle", LocalizedStrings.ttlWarningTitle, "Vault Expiring Soon"),
            ("widgetTitle", LocalizedStrings.widgetTitle, "Ethos-Protocol"),
            ("myVault", LocalizedStrings.myVault, "My Vault"),
            ("noActiveVault", LocalizedStrings.noActiveVault, "No Active Vault"),
            ("unavailable", LocalizedStrings.unavailable, "Unavailable"),
            ("expiringsoon", LocalizedStrings.expiringsoon, "Expiring soon"),
            ("ttlLabel", LocalizedStrings.ttlLabel, "TTL"),
            ("balanceLabel", LocalizedStrings.balanceLabel, "Balance"),
            ("beneficiaryLabel", LocalizedStrings.beneficiaryLabel, "Beneficiary"),
            ("widgetErrorTitle", LocalizedStrings.widgetErrorTitle, "Sync Failed"),
            ("widgetErrorMessage", LocalizedStrings.widgetErrorMessage, "Tap to retry"),
            ("checkInAction", LocalizedStrings.checkInAction, "Check In"),
        ]
    }

    /// Format-string keys used by `LocalizedStrings` helper methods.
    private var allFormatKeys: [String] {
        [
            "1 check-in will be submitted when back online",
            "%d check-ins will be submitted when back online",
            "%dd %dh remaining",
            "%dh remaining",
        ]
    }

    // MARK: - Static String Resolution

    func test_allStaticStrings_areNonEmpty() {
        for entry in allStaticStrings {
            XCTAssertFalse(
                entry.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                "LocalizedStrings.\(entry.name) must resolve to a non-empty string"
            )
        }
    }

    func test_allStaticStrings_resolveToExpectedValues() {
        for entry in allStaticStrings {
            XCTAssertEqual(
                entry.value,
                entry.expected,
                "LocalizedStrings.\(entry.name) did not resolve to expected value"
            )
        }
    }

    // MARK: - Dynamic / Formatted String Resolution

    func test_checkInReminderBody_interpolatesVaultIDAndTimeRemaining() {
        let result = LocalizedStrings.checkInReminderBody(vaultID: "vault-123", timeRemaining: "2d 4h")
        XCTAssertFalse(result.isEmpty)
        XCTAssertTrue(result.contains("vault-123"), "Expected vault ID in reminder body")
        XCTAssertTrue(result.contains("2d 4h"), "Expected time remaining in reminder body")
        XCTAssertEqual(
            result,
            "Vault vault-123 expires in 2d 4h. Tap to check in and keep it active."
        )
    }

    func test_checkInUrgentBody_interpolatesVaultIDAndTimeRemaining() {
        let result = LocalizedStrings.checkInUrgentBody(vaultID: "vault-999", timeRemaining: "30m")
        XCTAssertFalse(result.isEmpty)
        XCTAssertTrue(result.contains("vault-999"), "Expected vault ID in urgent body")
        XCTAssertTrue(result.contains("30m"), "Expected time remaining in urgent body")
        XCTAssertEqual(
            result,
            "Vault vault-999 expires in 30m. Check in now to prevent loss of access."
        )
    }

    func test_ttlWarningBody_interpolatesVaultIDAndTimeRemaining() {
        let result = LocalizedStrings.ttlWarningBody(vaultID: "vault-abc", timeRemaining: "12h")
        XCTAssertFalse(result.isEmpty)
        XCTAssertTrue(result.contains("vault-abc"), "Expected vault ID in TTL warning body")
        XCTAssertTrue(result.contains("12h"), "Expected time remaining in TTL warning body")
        XCTAssertEqual(
            result,
            "Vault vault-abc expires in 12h. Open the app to check in and keep it active."
        )
    }

    func test_queuedCheckInBody_singleCheckIn() {
        let result = LocalizedStrings.queuedCheckInBody(count: 1)
        XCTAssertFalse(result.isEmpty)
        XCTAssertEqual(result, "1 check-in will be submitted when back online")
    }

    func test_queuedCheckInBody_multipleCheckIns() {
        let result = LocalizedStrings.queuedCheckInBody(count: 3)
        XCTAssertFalse(result.isEmpty)
        XCTAssertEqual(result, "3 check-ins will be submitted when back online")
    }

    func test_queuedCheckInBody_zeroCheckIns() {
        let result = LocalizedStrings.queuedCheckInBody(count: 0)
        XCTAssertFalse(result.isEmpty)
        XCTAssertEqual(result, "0 check-ins will be submitted when back online")
    }

    func test_durationFormat_withDaysAndHours() {
        let result = LocalizedStrings.durationFormat(days: 5, hours: 12)
        XCTAssertFalse(result.isEmpty)
        XCTAssertEqual(result, "5d 12h remaining")
    }

    func test_durationFormat_withHoursOnly() {
        let result = LocalizedStrings.durationFormat(days: 0, hours: 8)
        XCTAssertFalse(result.isEmpty)
        XCTAssertEqual(result, "8h remaining")
    }

    func test_durationFormat_withZeroDaysAndZeroHours() {
        let result = LocalizedStrings.durationFormat(days: 0, hours: 0)
        XCTAssertFalse(result.isEmpty)
        XCTAssertEqual(result, "0h remaining")
    }

    // MARK: - Bundle Localization Coverage

    func test_allKeys_resolveInEveryLocalizationBundle() {
        let allKeys = allStaticStrings.map(\.expected) + allFormatKeys
        let missingSentinel = "__MISSING_LOCALIZATION_VALUE__"

        // Check every loaded bundle that provides Localizable.strings tables.
        let bundlesWithStrings = Bundle.allBundles.filter { bundle in
            bundle.path(forResource: "Localizable", ofType: "strings") != nil ||
                !bundle.localizations.filter { $0 != "Base" }.compactMap { localization -> String? in
                    guard let lprojPath = bundle.path(forResource: localization, ofType: "lproj"),
                          let lprojBundle = Bundle(path: lprojPath) else {
                        return nil
                    }
                    return lprojBundle.path(forResource: "Localizable", ofType: "strings")
                }.isEmpty
        }

        for bundle in bundlesWithStrings {
            for localization in bundle.localizations where localization != "Base" {
                guard let lprojPath = bundle.path(forResource: localization, ofType: "lproj"),
                      let lprojBundle = Bundle(path: lprojPath),
                      lprojBundle.path(forResource: "Localizable", ofType: "strings") != nil else {
                    continue
                }

                for key in allKeys {
                    let resolved = lprojBundle.localizedString(
                        forKey: key,
                        value: missingSentinel,
                        table: "Localizable"
                    )
                    XCTAssertNotEqual(
                        resolved,
                        missingSentinel,
                        "Key '\(key)' is missing in localization '\(localization)' of bundle '\(bundle.bundlePath)'"
                    )
                    XCTAssertFalse(
                        resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                        "Key '\(key)' resolved to empty string in localization '\(localization)' of bundle '\(bundle.bundlePath)'"
                    )
                }
            }
        }

        // Also verify every key resolves to a non-empty value in the main bundle.
        for key in allKeys {
            let resolved = NSLocalizedString(key, bundle: .main, comment: "")
            XCTAssertFalse(
                resolved.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                "Key '\(key)' resolved to empty string in main bundle"
            )
        }
    }
}
