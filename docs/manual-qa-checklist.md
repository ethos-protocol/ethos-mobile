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

## Dynamic-color contrast pass (Android)

Covers the fact that Material3 dynamic color (`Theme.kt`) derives its palette from the user's
wallpaper and doesn't guarantee WCAG AA contrast (4.5:1 for normal text) for every generated
palette, especially status-communicating colors.

- [ ] On a device running Android 12+, cycle through at least 4 visually distinct wallpapers
      (e.g. a light pastel, a saturated red/orange, a dark photo, a high-key white) and for each:
  - Check the "Expiring soon!" warning text/icon (`MaterialTheme.colorScheme.error`) against its
        background using a contrast-checker tool (e.g. the Android Studio Layout Inspector color
        picker + a WCAG contrast calculator) — must be >= 4.5:1.
  - Check the offline banner text (`onTertiaryContainer` on `tertiaryContainer`) the same way.
- [ ] If any sampled palette falls below 4.5:1, enable the high-contrast override
      (`EthosProtocolTheme(highContrast = true, ...)`) and confirm both colors above become
      compliant. Wiring this to a user-facing settings toggle is tracked as a follow-up.
- [ ] File a follow-up if a non-status color (not covered by the override) is found to be
      non-compliant on a sampled palette.
