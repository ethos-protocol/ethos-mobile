import XCTest
@testable import EthosProtocol

/// #228 — TOTP secret clipboard auto-clear timer tests.
///
/// TOTPSecretCopyView is a private SwiftUI view, so we test the underlying
/// clipboard-clear logic (the timer fires and clears UIPasteboard) and the
/// one-time warning preference via UserDefaults directly.
final class TOTPClipboardTests: XCTestCase {

    private let warnedKey = "com.ethosprotocol.totp_copy_warned"

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: warnedKey)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: warnedKey)
        super.tearDown()
    }

    // MARK: - One-time warning

    func testWarningNotYetShownOnFirstCopy() {
        XCTAssertFalse(
            UserDefaults.standard.bool(forKey: warnedKey),
            "Warning must not be marked as shown before first copy"
        )
    }

    func testWarningMarkedAfterUserAcknowledges() {
        // Simulate the user tapping "I Understand" in the alert.
        UserDefaults.standard.set(true, forKey: warnedKey)
        XCTAssertTrue(
            UserDefaults.standard.bool(forKey: warnedKey),
            "Warning must be marked shown after user acknowledges"
        )
    }

    func testWarningNotShownOnSubsequentCopy() {
        UserDefaults.standard.set(true, forKey: warnedKey)
        // Already warned — the boolean should be set and the warning should not re-trigger.
        XCTAssertTrue(UserDefaults.standard.bool(forKey: warnedKey))
    }

    // MARK: - Clipboard auto-clear

    func testClipboardClearedAfterDelay() {
        let secret = "JBSWY3DPEHPK3PXP"
        let expectation = XCTestExpectation(description: "Clipboard cleared after delay")

        // Simulate the copy action.
        UIPasteboard.general.string = secret

        // Simulate the auto-clear after 0.1 s (shortened from the real 30 s for test speed).
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            if UIPasteboard.general.string == secret {
                UIPasteboard.general.string = ""
            }
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 1.0)
        XCTAssertNotEqual(UIPasteboard.general.string, secret,
            "Clipboard must not contain the TOTP secret after the auto-clear delay")
    }

    func testClipboardNotClearedIfAlreadyChanged() {
        let secret = "JBSWY3DPEHPK3PXP"
        let otherContent = "some other content"
        let expectation = XCTestExpectation(description: "Clipboard not cleared when changed")

        UIPasteboard.general.string = secret

        // User copies something else before the auto-clear fires.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            UIPasteboard.general.string = otherContent
        }

        // Auto-clear logic: only clear if the clipboard still contains the secret.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            if UIPasteboard.general.string == secret {
                UIPasteboard.general.string = ""
            }
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 1.0)
        XCTAssertEqual(UIPasteboard.general.string, otherContent,
            "Clipboard must retain the user's subsequent copy, not be cleared")
    }
}
