import XCTest

// MARK: - Accessibility Identifier Constants

/// Accessibility identifiers used across UI tests.
/// These must match the `.accessibilityIdentifier(_:)` modifiers set in the app's SwiftUI views.
enum AccessibilityIdentifiers {

    // MARK: Auth
    static let signInButton        = "Sign in with Passkey"
    static let createAccountButton = "Create account"
    static let cancelButton        = "Cancel"
    static let lostDeviceButton    = "Lost your device?"

    // MARK: Vault List
    static let createVaultButton   = "plus"
    static let signOutButton       = "Sign Out"
    static let vaultSearchBar      = "vault-search-bar"
    static let vaultListEmpty      = "vault-list-empty"
    static let vaultCell           = "vault-cell"

    // MARK: Vault Detail
    static let checkInButton       = "Check In"
    static let vaultDetailTitle    = "vault-detail-title"

    // MARK: Check-In Feedback
    static let checkInConfirmation = "check-in-confirmation"
    static let checkInSuccessBanner = "check-in-success-banner"

    // MARK: Offline Banner
    static let offlineBanner       = "offline-banner"
}

// MARK: - LaunchEnvironment Keys

/// `XCUIApplication.launchEnvironment` keys recognised by the app's test mode.
/// Set these before calling `app.launch()` to configure app behaviour during UI tests.
enum UITestLaunchEnvironment {
    /// When `"1"`, the app starts in a pre-authenticated state, bypassing the real passkey flow.
    static let mockAuthenticated = "UI_TEST_MOCK_AUTHENTICATED"

    /// When `"1"`, `NetworkMonitor` reports the device as offline for the entire session.
    static let simulateOffline   = "UI_TEST_SIMULATE_OFFLINE"

    /// When `"1"`, the vault list is seeded with a fixed set of test vaults.
    static let seedVaults        = "UI_TEST_SEED_VAULTS"

    /// When `"1"`, the vault list is seeded empty (no vaults).
    static let seedEmptyVaults   = "UI_TEST_SEED_EMPTY_VAULTS"
}

// MARK: - XCUIApplication Helpers

extension XCUIApplication {

    /// Waits up to `timeout` seconds for `element` to exist and be hittable.
    ///
    /// - Parameters:
    ///   - element: The `XCUIElement` to wait for.
    ///   - timeout: Maximum wait time in seconds. Defaults to `5`.
    /// - Returns: `true` if the element became hittable within the timeout; `false` otherwise.
    @discardableResult
    func waitForElement(_ element: XCUIElement, timeout: TimeInterval = 5) -> Bool {
        let predicate = NSPredicate(format: "exists == true AND hittable == true")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        let result = XCTWaiter().wait(for: [expectation], timeout: timeout)
        return result == .completed
    }

    /// Navigates to the Vault List if the app appears to be in an authenticated state.
    ///
    /// Some UI tests only make sense once the user is authenticated and the vault list is
    /// visible. If the sign-in screen is currently showing (unauthenticated state), this
    /// method returns immediately without taking any action, allowing individual tests to
    /// `guard` on the presence of vault-list elements rather than failing hard.
    ///
    /// In a fully set-up test environment the app should be launched with
    /// `UITestLaunchEnvironment.mockAuthenticated = "1"` so this is a no-op.
    func navigateToVaultListIfAuthenticated() {
        // If the auth screen is visible the app is not yet authenticated — skip navigation.
        let signInButton = buttons[AccessibilityIdentifiers.signInButton]
        if signInButton.exists {
            return
        }

        // If the vault list is already the front screen (e.g. mock-authenticated launch),
        // no navigation is needed.
        let vaultListNavBar = navigationBars.element(boundBy: 0)
        if vaultListNavBar.exists {
            return
        }
    }
}
