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

## App Switcher Privacy Overlay

Covers iOS #277 (`PrivacyOverlayModifier`) and Android #277 (`FLAG_SECURE`).

### iOS — Privacy Overlay

- [ ] Build and run the app on a real device (Simulator does not snapshot the app switcher).
- [ ] Navigate to a vault detail screen so sensitive data (balance, TTL, beneficiary) is visible.
- [ ] Swipe up to open the app switcher (or double-press Home on Touch ID devices).
- [ ] **Expected**: the Ethos Protocol app card shows a blank screen with the lock-shield icon
      and "Ethos Protocol" label — **no vault data, balances, or addresses should be visible**.
- [ ] Tap the app card to return to the foreground.
- [ ] **Expected**: the privacy overlay disappears immediately and the vault detail is visible again.
- [ ] Repeat with `scenePhase == .inactive` (e.g. pull down Control Centre while the app is in
      the foreground) — the overlay should also appear during transient inactivity.

### Android — FLAG_SECURE (Recent Apps + Screenshot)

- [ ] Build and install the debug APK on a real device or emulator.
- [ ] Navigate to the vault list or vault detail screen.
- [ ] Open the Recent Apps screen (square button or swipe gesture).
- [ ] **Expected**: the Ethos Protocol card shows a blank/greyed-out preview — no vault data
      should be visible in the thumbnail.
- [ ] Return to the app and attempt a screenshot (Power + Volume Down).
- [ ] **Expected**: the screenshot is blank/black, **not** a capture of the app content.
      The system typically shows a toast: "Can't take screenshot due to security policy."
- [ ] Verify the flag does not interfere with normal app use (touch, scrolling, navigation).

### Both platforms — Release build verification

- [ ] Confirm the overlay / FLAG_SECURE behaviour is present in a **Release** build, not just
      Debug — some vendors strip window flags differently in production vs. development.
