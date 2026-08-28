# Manual QA Checklist

Checks that aren't covered by automated tests and should be run by hand before release.

## Largest font-scale accessibility pass

Covers Android issue #android-a11y-font-scale (mirrors iOS #45).

- [ ] iOS: set Settings > Accessibility > Display & Text Size > Larger Text to the maximum
      (Accessibility Sizes), then walk through the vault list, vault detail, and 2FA flows.
- [ ] Android: set Settings > Accessibility > Display size and text > Font size to the largest
      step (or `adb shell settings put system font_scale 2.0`), then walk through the same flows:
  - Vault list (`VaultListScreen`) — id + `StatusChip` row on `VaultCard`, "Expiring soon!" row
  - 2FA setup and verify screens (`TwoFactorSetupScreen`, `TwoFactorVerifyScreen`) — OTP field
- [ ] Confirm no truncated-ID/chip rows clip or overlap, and no interactive control becomes
      unreachable or unreadable at 200% scale.

## TalkBack / VoiceOver pass

Covers Android issue #android-a11y-content-descriptions (mirrors iOS #44).

- [ ] Android: enable TalkBack and swipe through Auth, Vault list (including the offline banner
      and expiring-soon warning), Beneficiary acceptance, and 2FA screens. Confirm state-carrying
      icons (offline, warning, lock/security context) are announced, and decorative icons are
      silently skipped.
- [ ] iOS: run the equivalent VoiceOver pass per #44.


## Deep Link Handling Across App Lifecycle States

Covers issues #262 (lifecycle state testing) and #260 (analytics source tracking).

Manual QA is needed to verify behavior in all three app lifecycle states for each deep link type,
since some states are difficult to automate on both platforms. Automated tests are in:
- Android: `VaultDeepLinkLifecycleTest.kt` (instrumented), `VaultDeepLinkParserTest.kt` (unit)
- iOS: equivalent tests in `VaultDeepLinkTests.swift`

### Test Coverage Matrix

For **each** deep link type and **each** app lifecycle state, complete the corresponding check:

| Deep Link Type | Foreground | Background | Terminated |
|---|---|---|---|
| Check-in (`ethosprotocol://vault/{id}/check-in`) | [ ] | [ ] | [ ] |
| Withdraw (`ethosprotocol://vault/{id}/withdraw`) | [ ] | [ ] | [ ] |
| View Details (`ethosprotocol://vault/{id}/view-details`) | [ ] | [ ] | [ ] |
| Manage Beneficiary (`ethosprotocol://vault/{id}/manage-beneficiary`) | [ ] | [ ] | [ ] |
| Beneficiary Accept (`https://ethos-protocol.app/vaults/{id}/accept?token={token}`) | [ ] | [ ] | [ ] |

### Test Instructions

#### Foreground State
User is logged in and app is visibly running when deep link is tapped.

- [ ] **Android**: Generate a deep link (or copy from GitHub issue description), paste into a test SMS/email, tap it.
- [ ] **iOS**: Same — tap deep link via Messages or Mail app.
- [ ] **Both platforms**: Confirm app routes to correct screen (e.g., check-in screen shows vault and check-in dialog).

#### Background State
App was running but is backgrounded when deep link arrives (e.g., user backgrounded it, then tapped link in email).

- [ ] **Android**: Open app and log in. Background app (Home button or swipe up). Open email/SMS with deep link, tap it.
      Confirm app comes to foreground and routes to correct screen.
- [ ] **iOS**: Same flow. Use App Switcher to background before tapping deep link.
- [ ] **Both platforms**: State should survive the background → foreground transition via `SavedStateHandle`/
      `@State` persistence.

#### Terminated State
App process was killed before deep link arrives (e.g., system low-memory kill, or user force-closed app).

- [ ] **Android**: Open app, log in. Force-close: Settings > Apps > Ethos-Protocol > Force Stop.
      Open email/SMS with deep link, tap it. App should relaunch and route to correct screen,
      with state restored from `SavedStateHandle`.
- [ ] **iOS**: Open app, log in. Force-close: App Switcher > swipe up. Tap deep link from email/SMS.
      App should relaunch and route correctly with state restored from `@AppStorage` or
      `@SceneStorage`.
- [ ] **Both platforms**: Confirm no state loss or incorrect routing (e.g., shouldn't require re-auth
      if cached token is still valid).

### Analytics / Source Attribution

All deep links should log an event with action + source parameters for analytics (#260).

- [ ] **Android/iOS**: Open app console / logcat and search for `vault_deep_link_opened`.
      Confirm events carry both `action` (e.g., `check-in`) and `source` (e.g., `email`, `push`).
      Event should NOT log vault ID or sensitive data.
- [ ] Event appears once per successful parse (not repeatedly on re-navigation).
- [ ] Different channels (push notification, email link, share link) are correctly attributed
      if source parameter is set at parse time.

### Rate Limiting on Rapid Taps

App should throttle repeated deep-link-triggered API calls (#263).

- [ ] **Android**: Generate a check-in deep link. Tap it 5 times rapidly (within 1 second).
      Confirm only first tap triggers API call; subsequent calls are rate-limited (2s minimum
      between calls per vault ID).
- [ ] **iOS**: Same rapid-tap test.
- [ ] Check app logs or network inspector to confirm API call count matches expected rate limit
      (1 call, not 5).

### Web Fallback Page

When deep link is opened on a device without the app installed (#261).

- [ ] Navigate to a deep-link URL in a browser on a device without Ethos-Protocol installed:
      - Example: `https://ethos-protocol.app/vaults/test-vault-1/accept?token=xyz`
- [ ] [ ] Confirm a web landing page appears (not 404) explaining what the link is for.
- [ ] [ ] Confirm page includes App Store / Google Play download links.
- [ ] [ ] Confirm page does NOT log or display the vault ID or token (privacy).
- [ ] [ ] On mobile browser, tapping "Install" should redirect to app store (iOS) or Google Play (Android).

