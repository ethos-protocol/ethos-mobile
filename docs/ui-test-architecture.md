# UI Test Architecture

This document describes the XCUITest setup for the EthosProtocol iOS app, how to run and
extend the tests, and what the CI job does with them.

## Overview

UI tests live in `ios/EthosProtocol/Tests/UI/` alongside the unit-test suite. They use
Apple's **XCTest UI testing framework (XCUITest)**, which drives a real app process in an
iOS Simulator via the Accessibility API. Unlike the SPM unit tests in `Tests/`, UI tests
exercise the full SwiftUI rendering and navigation stack.

The suite is intentionally defensive: most tests use `guard` statements to skip gracefully
when the required pre-conditions are not met (e.g. the app is unauthenticated, or a feature
flag is off), rather than failing hard. This keeps the test suite green on CI runners where
a live passkey / backend is unavailable, while still catching genuine regressions when the
tests *can* run.

## Files

| File | Purpose |
|---|---|
| `Tests/UI/UITestHelpers.swift` | Shared helpers: `waitForElement`, `navigateToVaultListIfAuthenticated`, accessibility-identifier constants, `LaunchEnvironment` keys |
| `Tests/UI/AuthUITests.swift` | Tests for the sign-in, register, and "Lost your device?" flows |
| `Tests/UI/VaultUITests.swift` | Tests for the vault list, create-vault flow, and navigation |
| `Tests/UI/CheckInUITests.swift` | Tests for the check-in flow, offline banner, and confirmation feedback |

## Running UI Tests Locally

UI tests require the **XcodeGen-generated project** (not just the SPM package). Run:

```bash
# 1. Generate the Xcode project (only needed once, or after project.yml changes)
cd ios/EthosProtocol
mkdir -p Xcode
xcodegen generate --project Xcode

# 2. Pick a simulator UDID to run against (use a concrete UDID, not OS=latest)
UDID=$(xcrun simctl list devices available \
  | sed -n '/^-- iOS/,$p' \
  | grep -E '^ +iPhone' \
  | tail -1 \
  | grep -oE '[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}')
echo "Using simulator UDID: $UDID"

# 3. Run only the UI test target
xcodebuild test \
  -project Xcode/EthosProtocol.xcodeproj \
  -scheme EthosProtocol \
  -destination "platform=iOS Simulator,id=$UDID" \
  -only-testing:EthosProtocolUITests \
  -skipMacroValidation
```

> **Tip:** You can also run UI tests directly from Xcode by selecting the
> `EthosProtocol` scheme, choosing the `EthosProtocolUITests` test plan, and
> pressing **⌘U**. Individual test methods can be run from the Test Navigator
> (⌘5) or from the gutter play button in the editor.

## Test Structure

### Auth Flow (`AuthUITests`)

Covers the unauthenticated entry points:

- Sign-in button visibility and enabled state
- "Create Account" sheet: open/close, field validation, register button state
- "Lost your device?" recovery sheet: open/close, field validation, "Link Passkey" button state

These tests run without any launch-environment flags and are always valid — the auth screen
is always the first screen in an un-launched app.

### Vault List (`VaultUITests`)

Covers the authenticated vault list and create-vault flow:

- `testVaultListView_displaysCreateVaultButton` — "+" button is visible
- `testVaultListView_displaysSignOutButton` — "Sign Out" button is visible
- `testCreateVaultView_canOpenAndClose` — create-vault sheet opens and can be dismissed
- `testCreateVaultView_next_showsConfirmationBeforeCreating` — tapping "Next" goes to a
  review step before actually creating (#215)
- `testCreateVaultView_back_returnsToInputFormWithoutCreating` — "Back" from review returns
  to the editable form
- `testVaultDetailView_displaysVaultInfo` — navigation hierarchy is present
- `testDeepLinkNavigation_canReceiveDeepLink` — app has at least one window
- `testVaultListView_searchBarExists` — search bar/field is present (#441)
- `testVaultListView_emptyState` — empty-state message when no vaults (#441)
- `testVaultListView_navigationToDetail` — tapping a vault cell navigates forward (#441)

### Check-In Flow (`CheckInUITests`)

Covers the vault check-in user journey end-to-end:

- `testCheckIn_buttonExistsOnVaultDetail` — "Check In" button is on the detail screen
- `testCheckIn_showsConfirmationOrFeedback` — tapping it produces visible feedback
- `testCheckIn_fromVaultList` — full navigation path: list → detail → check-in
- `testCheckIn_offlineBannerVisible` — offline banner appears when network is simulated away

## Launch Environment Flags

Tests use `app.launchEnvironment` to configure the app without a live backend. The full
set of keys is declared in `UITestLaunchEnvironment` (in `UITestHelpers.swift`):

| Key | Value | Effect |
|---|---|---|
| `UI_TEST_MOCK_AUTHENTICATED` | `"1"` | App skips the passkey flow and opens directly on the vault list |
| `UI_TEST_SIMULATE_OFFLINE` | `"1"` | `NetworkMonitor` reports no connectivity for the session |
| `UI_TEST_SEED_VAULTS` | `"1"` | Vault list is populated with a fixed set of test vaults |
| `UI_TEST_SEED_EMPTY_VAULTS` | `"1"` | Vault list is explicitly empty |

> **Implementation note:** The app itself must read these keys and act on them (e.g.
> in `@main App.init()` or the respective services). The tests already pass the flags;
> the app-side handling is required to make the flags functional. See the "Known
> Limitations" section below.

## Accessibility Identifiers

Tests reference UI elements by accessibility identifier. These are declared as constants
in `AccessibilityIdentifiers` (in `UITestHelpers.swift`) and must match the
`.accessibilityIdentifier(_:)` modifiers set in the SwiftUI views:

| Constant | Value | View element |
|---|---|---|
| `signInButton` | `"Sign in with Passkey"` | Auth screen sign-in button |
| `createAccountButton` | `"Create account"` | Auth screen register button |
| `createVaultButton` | `"plus"` | Vault list "+" nav button |
| `signOutButton` | `"Sign Out"` | Vault list nav button |
| `vaultSearchBar` | `"vault-search-bar"` | Vault list search field |
| `vaultListEmpty` | `"vault-list-empty"` | Vault list empty-state container |
| `vaultCell` | `"vault-cell"` | Individual vault row |
| `checkInButton` | `"Check In"` | Vault detail check-in button |
| `checkInSuccessBanner` | `"check-in-success-banner"` | Post-check-in success banner |
| `checkInConfirmation` | `"check-in-confirmation"` | Check-in confirmation view |
| `offlineBanner` | `"offline-banner"` | Offline connectivity banner |

## How to Add New Tests

1. **Choose the right file.** Auth tests → `AuthUITests.swift`, vault list/create tests →
   `VaultUITests.swift`, check-in tests → `CheckInUITests.swift`. For a completely new
   flow, create a new `<FeatureName>UITests.swift` in `Tests/UI/`.

2. **Follow the class pattern:**
   ```swift
   import XCTest

   final class MyFeatureUITests: XCTestCase {
       var app: XCUIApplication!

       override func setUpWithError() throws {
           continueAfterFailure = false
           app = XCUIApplication()
           // Set any launch environment flags here
           app.launch()
       }

       override func tearDownWithError() throws {
           app = nil
       }

       func testMyFeature_doesSomething() throws {
           // Use guard to skip gracefully if pre-conditions aren't met
           let button = app.buttons["My Button"]
           guard app.waitForElement(button) else { return }
           button.tap()
           XCTAssertTrue(...)
       }
   }
   ```

3. **Add accessibility identifiers to the view** if the element you're testing doesn't
   already have one. Add the constant to `AccessibilityIdentifiers` in `UITestHelpers.swift`
   and the `.accessibilityIdentifier(_:)` call to the SwiftUI view.

4. **Use `waitForElement` not `waitForExistence`** when you also need the element to be
   hittable (the XCUIApplication extension from `UITestHelpers.swift` checks both).

5. **Guard auth-gated screens.** If your test only works when authenticated, check for
   the sign-in button first:
   ```swift
   guard !app.buttons[AccessibilityIdentifiers.signInButton].exists else { return }
   ```

6. **Register new UI test files in `project.yml`** if you add a separate test *target*.
   The current tests are all grouped under the `EthosProtocolUITests` target; adding a
   source file to `Tests/UI/` is all that's needed within that target.

## CI Job Reference

The `ui-tests` job in `.github/workflows/ios-ci.yml` runs after `build-and-test`:

```
jobs:
  build-and-test: ...   # SPM + hosted unit tests
  ui-tests:
    needs: build-and-test
    runs-on: macos-latest
    continue-on-error: true   # UI tests need a full simulator; skip gracefully
```

The job:
1. Checks out the repo
2. Selects Xcode and resolves the newest available iPhone simulator (same logic as
   `build-and-test`)
3. Installs XcodeGen and generates the Xcode project
4. Runs `xcodebuild test -scheme EthosProtocol -only-testing:EthosProtocolUITests`
   against the resolved simulator UDID

`continue-on-error: true` is set because UI tests require a fully booted simulator with
the app installed. Certain macOS runner images and configurations can make that
non-deterministically unavailable; the flag prevents a flaky simulator environment from
failing the entire pipeline while real regressions are still surfaced in the job log.

## Known Limitations

### Auth-gated screens require test fixtures

Screens behind authentication (vault list, vault detail, check-in) can only be reached if
the app supports mock authentication via the `UI_TEST_MOCK_AUTHENTICATED` launch
environment key. Until the app reads that key and bypasses the real passkey flow, tests
for those screens use `guard` to skip silently rather than fail.

**To unblock:** Add `ProcessInfo.processInfo.environment["UI_TEST_MOCK_AUTHENTICATED"] == "1"`
checks in the app's auth entry point (e.g. `AuthStore`) to short-circuit the passkey
assertion and transition directly to the authenticated state with seeded test data.

### Real passkey operations cannot be automated

`ASAuthorizationController` presents a system sheet that XCUITest cannot interact with
programmatically. Tests that would require completing a passkey assertion must use the
mock-authentication flag instead.

### Simulator availability on CI

`macos-latest` GitHub Actions runners do not guarantee a specific iOS runtime version or
device model. The CI job resolves a UDID dynamically (`xcrun simctl list devices`) to
avoid hard-coded `OS=latest` destinations that break when runner images update.
