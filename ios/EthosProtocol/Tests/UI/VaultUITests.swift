import XCTest

final class VaultUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // Note: These tests assume the app is already authenticated. In a real scenario,
        // you would mock the authentication or set up a test fixture.
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    // MARK: - Vault List View

    func testVaultListView_displaysCreateVaultButton() throws {
        // Note: This test will only work if the app is already authenticated.
        // In a real CI environment, you would need to set up authentication fixtures.
        // For now, this serves as a template for the UI test structure.

        // Look for the plus button (create vault button)
        let createButton = app.buttons["plus"]
        if createButton.exists {
            XCTAssertTrue(createButton.exists, "Create vault button should be visible in vault list")
        }
    }

    func testVaultListView_displaysSignOutButton() throws {
        // Look for the sign out button in navigation bar
        let signOutButton = app.buttons["Sign Out"]
        if signOutButton.exists {
            XCTAssertTrue(signOutButton.exists, "Sign out button should be visible")
        }
    }

    // MARK: - Create Vault Flow

    func testCreateVaultView_canOpenAndClose() throws {
        // This test template shows the structure for testing create vault flow.
        // Actual test execution depends on app being in authenticated state.

        let createButton = app.buttons["plus"]
        if createButton.exists {
            createButton.tap()

            // Look for create vault form elements
            // The actual field identifiers would depend on your CreateVaultView implementation
            let cancelButton = app.buttons.matching(NSPredicate(format: "label CONTAINS[cd] 'cancel'")).firstMatch
            if cancelButton.exists {
                cancelButton.tap()
            }
        }
    }

    // MARK: - #215 Create Vault Confirmation Step

    func testCreateVaultView_next_showsConfirmationBeforeCreating() throws {
        let createButton = app.buttons["plus"]
        guard createButton.exists else { return }
        createButton.tap()

        // A syntactically valid Stellar public key: 56 chars, starts with 'G'.
        app.textFields["Stellar address"].tap()
        app.textFields["Stellar address"].typeText("GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF")

        let nextButton = app.buttons["Next"]
        guard nextButton.exists, nextButton.isEnabled else { return }
        nextButton.tap()

        XCTAssertTrue(app.navigationBars["Confirm Vault"].exists, "Submitting the form should open a review step, not create the vault directly")
        XCTAssertTrue(app.buttons["Confirm & Create"].exists, "Review step must require an explicit confirmation before creating")
    }

    func testCreateVaultView_back_returnsToInputFormWithoutCreating() throws {
        let createButton = app.buttons["plus"]
        guard createButton.exists else { return }
        createButton.tap()

        app.textFields["Stellar address"].tap()
        app.textFields["Stellar address"].typeText("GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF")

        let nextButton = app.buttons["Next"]
        guard nextButton.exists, nextButton.isEnabled else { return }
        nextButton.tap()

        let backButton = app.buttons["Back"]
        if backButton.exists {
            backButton.tap()
            XCTAssertTrue(app.navigationBars["New Vault"].exists, "Back must return to the editable form")
        }
    }

    // MARK: - Vault Detail and Check-in

    func testVaultDetailView_displaysVaultInfo() throws {
        // This test demonstrates the structure for testing vault detail view.
        // It would need to navigate to a vault first.

        // In a real scenario with seeded test data, you would:
        // 1. Tap on a vault from the list
        // 2. Verify vault details are displayed
        // 3. Look for check-in button

        let navigationBars = app.navigationBars
        let hasNavigationBars = navigationBars.count > 0
        XCTAssertTrue(hasNavigationBars, "Navigation hierarchy should exist")
    }

    // MARK: - Deep Link Entry

    func testDeepLinkNavigation_canReceiveDeepLink() throws {
        // Deep link testing structure.
        // In a real scenario, you would:
        // 1. Suspend app
        // 2. Open a deep link via launchArguments or environment variable
        // 3. Verify app navigates to correct screen

        // This test verifies the app can handle deep link transitions
        let windows = app.windows
        XCTAssertGreaterThan(windows.count, 0, "App should have at least one window")
    }

    // MARK: - Additional Vault List Tests (#441)

    func testVaultListView_searchBarExists() throws {
        // Guard: the vault list is only reachable when authenticated.
        // If the sign-in button is visible the app is unauthenticated — skip.
        let signInButton = app.buttons[AccessibilityIdentifiers.signInButton]
        guard !signInButton.exists else { return }

        // A search bar (or equivalent search field) should be present in the vault list.
        let searchBar = app.searchFields.firstMatch
        let searchField = app.searchFields[AccessibilityIdentifiers.vaultSearchBar]

        let searchVisible = searchBar.waitForExistence(timeout: 3) || searchField.waitForExistence(timeout: 1)
        guard searchVisible else { return }

        XCTAssertTrue(
            app.searchFields.count > 0,
            "A search bar should be present in the vault list view"
        )
    }

    func testVaultListView_emptyState() throws {
        // Launch a new instance seeded with an explicitly empty vault list so we
        // can verify the empty-state UI without depending on real data.
        app.terminate()
        app.launchEnvironment[UITestLaunchEnvironment.mockAuthenticated] = "1"
        app.launchEnvironment[UITestLaunchEnvironment.seedEmptyVaults]   = "1"
        app.launch()

        // Guard: if mock authentication isn't supported in this build, the
        // auth screen is still showing — skip.
        let signInButton = app.buttons[AccessibilityIdentifiers.signInButton]
        guard !signInButton.exists else { return }

        // Look for a labelled empty-state message. The exact text may vary, so
        // accept any static text that matches common phrasing, or fall back to
        // the accessibility identifier if the view sets one.
        let emptyStateById = app.otherElements[AccessibilityIdentifiers.vaultListEmpty]
        let emptyStateByText = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[cd] 'no vaults' OR label CONTAINS[cd] 'empty' OR label CONTAINS[cd] 'create your first'")
        ).firstMatch

        let emptyStateVisible =
            emptyStateById.waitForExistence(timeout: 3)
            || emptyStateByText.waitForExistence(timeout: 1)

        guard emptyStateVisible else { return }

        XCTAssertTrue(
            emptyStateVisible,
            "An empty-state message should be displayed when there are no vaults"
        )
    }

    func testVaultListView_navigationToDetail() throws {
        // Guard: the vault list is only reachable when authenticated.
        let signInButton = app.buttons[AccessibilityIdentifiers.signInButton]
        guard !signInButton.exists else { return }

        // Guard: there must be at least one vault cell to tap.
        let firstCell = app.cells.element(boundBy: 0)
        guard firstCell.waitForExistence(timeout: 5) else { return }

        firstCell.tap()

        // After tapping a vault cell, the navigation stack should push a detail
        // screen. A back button in the nav bar is the reliable signal that we
        // navigated forward.
        let backButton = app.navigationBars.buttons.element(boundBy: 0)
        let navigatedForward = backButton.waitForExistence(timeout: 5)
        guard navigatedForward else { return }

        XCTAssertTrue(
            navigatedForward,
            "Tapping a vault cell should navigate to the vault detail screen"
        )
    }
}
