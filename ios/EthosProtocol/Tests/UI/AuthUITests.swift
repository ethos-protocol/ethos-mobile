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
}
