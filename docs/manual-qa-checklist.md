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
- [ ] **Home-screen widget**: enable TalkBack (Android) or VoiceOver (iOS) and interact with the
      vault status widget. Confirm vault name, TTL countdown, balance (medium/large sizes), and
      beneficiary (large size) are announced with their labels, and the expiring-soon warning is
      announced when present. Test all widget sizes (small, medium, large, and lock-screen variants).

## OTP field accessibility (TalkBack / VoiceOver)

Covers issue #230.

- [ ] iOS: In TwoFactorVerifyView, activate VoiceOver and focus the OTP code field.
      Confirm VoiceOver announces "OTP code field" and the entry progress
      (e.g. "3 of 6 digits entered") as digits are typed.
- [ ] Android: Enable TalkBack and focus the OTP code field in TwoFactorVerifyScreen.
      Confirm TalkBack reads "OTP code field, 3 of 6 digits entered" as digits are typed.
- [ ] Confirm the field is not split into multiple unlabelled boxes that TalkBack/VoiceOver
      would read without positional context.

## Performance regression pass

Covers issue #459. Budgets and instructions: [performance-guide.md](performance-guide.md).
Use a physical device and a release build — debug builds and simulators do not produce
comparable numbers.

- [ ] iOS: cold-launch to first interactive frame, three runs, report the median.
      Budget: under 3 000 ms. Use Product > Profile (Cmd+I) > System Trace, filtered by
      subsystem `com.ethosprotocol`.
- [ ] Android: cold launch three times, report the median. Budget: under 3 000 ms.
      Use `adb shell am start -W` with a cold-stopped app, or Android Studio Profiler.
- [ ] iOS: open the vault list with 100+ vaults and scroll to the end. Confirm no
      dropped frames in Instruments > Animation Hitches.
- [ ] Android: same list, same scroll path. Confirm no dropped frames in the Profiler's
      Frame View.
- [ ] Both: type in the vault search field. Confirm typing stays responsive — a
      `UserDefaults` write per keystroke is a known issue (see performance-guide.md §8).
- [ ] Both: background the app, wait for the session lock, return, and confirm the
      re-lock behaved per the configured timeout.
- [ ] Both: run a cold start on a captive-portal or offline network and confirm the app
      surfaces the offline state promptly rather than hanging. iOS has no explicit
      request timeout today (performance-guide.md §8).
- [ ] Both: confirm the widget still updates and stays within the interval in
      [widget-refresh-budget.md](widget-refresh-budget.md).
- [ ] Confirm the release build size has not grown beyond budget, and record the delta
      in the release notes.
