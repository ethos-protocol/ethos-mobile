import XCTest

final class AuthUITests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    // MARK: - Sign In Flow

    func testAuthView_displaysSignInButton() throws {
        // Verify the sign-in button is visible on the auth screen
        let signInButton = app.buttons["Sign in with Passkey"]
        XCTAssertTrue(signInButton.exists, "Sign in button should be visible")
        XCTAssertTrue(signInButton.isEnabled, "Sign in button should be enabled")
    }

    func testAuthView_displaysRegisterLink() throws {
        // Verify the "Create account" button is visible
        let createButton = app.buttons["Create account"]
        XCTAssertTrue(createButton.exists, "Create account button should be visible")
    }

    func testAuthView_displaysTitle() throws {
        // Verify the app title and subtitle are displayed
        let titleElement = app.staticTexts["Ethos-Protocol"]
        XCTAssertTrue(titleElement.exists, "App title should be visible")

        let subtitleElement = app.staticTexts["Secure digital inheritance"]
        XCTAssertTrue(subtitleElement.exists, "App subtitle should be visible")
    }

    // MARK: - Register Flow

    func testRegisterView_canOpenAndClose() throws {
        // Open the register sheet
        let createButton = app.buttons["Create account"]
        createButton.tap()

        // Verify the register form appears
        let registerTitle = app.navigationBars["Create Account"]
        XCTAssertTrue(registerTitle.exists, "Register form should open")

        // Verify username field is present
        let usernameField = app.textFields["Username"]
        XCTAssertTrue(usernameField.exists, "Username field should be visible")

        // Close the sheet
        let cancelButton = app.buttons["Cancel"]
        cancelButton.tap()

        // Verify we're back to auth view
        XCTAssertTrue(app.buttons["Sign in with Passkey"].exists, "Should return to auth view")
    }

    func testRegisterView_registerButtonDisabledWhenEmpty() throws {
        // Open the register sheet
        let createButton = app.buttons["Create account"]
        createButton.tap()

        // Verify register button is disabled when username is empty
        let registerButton = app.buttons["Register"]
        XCTAssertFalse(registerButton.isEnabled, "Register button should be disabled when username is empty")
    }

    func testRegisterView_registerButtonEnabledWithUsername() throws {
        // Open the register sheet
        let createButton = app.buttons["Create account"]
        createButton.tap()

        // Enter a username
        let usernameField = app.textFields["Username"]
        usernameField.tap()
        usernameField.typeText("testuser")

        // Verify register button is now enabled
        let registerButton = app.buttons["Register"]
        XCTAssertTrue(registerButton.isEnabled, "Register button should be enabled with username")
    }

    // MARK: - Recover Access Flow (#5 lost-device passkey recovery)

    func testAuthView_displaysLostDeviceLink() throws {
        // Verify the "Lost your device?" recovery entry point is visible
        let lostDeviceButton = app.buttons["Lost your device?"]
        XCTAssertTrue(lostDeviceButton.exists, "Lost your device button should be visible")
    }

    func testRecoverAccessView_canOpenAndClose() throws {
        // Open the recovery sheet
        let lostDeviceButton = app.buttons["Lost your device?"]
        lostDeviceButton.tap()

        // Verify the recovery form appears
        let recoverTitle = app.navigationBars["Recover Access"]
        XCTAssertTrue(recoverTitle.exists, "Recover Access form should open")

        // Verify identity + new-passkey fields are present
        XCTAssertTrue(app.textFields["Email"].exists, "Email field should be visible")
        XCTAssertTrue(app.textFields["Backup code"].exists, "Backup code field should be visible")
        XCTAssertTrue(app.textFields["Username"].exists, "Username field should be visible")

        // Close the sheet
        let cancelButton = app.buttons["Cancel"]
        cancelButton.tap()

        // Verify we're back to auth view
        XCTAssertTrue(app.buttons["Sign in with Passkey"].exists, "Should return to auth view")
    }

    func testRecoverAccessView_linkButtonDisabledUntilAllFieldsFilled() throws {
        // Open the recovery sheet
        let lostDeviceButton = app.buttons["Lost your device?"]
        lostDeviceButton.tap()

        let linkButton = app.buttons["Link Passkey"]
        XCTAssertFalse(linkButton.isEnabled, "Link Passkey button should be disabled with empty fields")

        app.textFields["Email"].tap()
        app.textFields["Email"].typeText("user@example.com")
        app.textFields["Backup code"].tap()
        app.textFields["Backup code"].typeText("123456")
        app.textFields["Username"].tap()
        app.textFields["Username"].typeText("testuser")

        XCTAssertTrue(linkButton.isEnabled, "Link Passkey button should be enabled once all fields are filled")
    }
}
