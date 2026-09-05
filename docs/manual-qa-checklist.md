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

- [ ] Android: enable TalkBack and swipe through Auth, Vault list (including the offline banner
      and empty state), Vault detail, Beneficiary acceptance, and 2FA flows. Every interactive
      element must have a meaningful label; decorative images must be marked hidden.
- [ ] **Widget:** add the home-screen VaultStatusWidget to the TalkBack pass. Long-press the
      widget and verify TalkBack announces the vault name, TTL, balance, and beneficiary with
      meaningful labels (not just raw text).
- [ ] iOS: run the equivalent VoiceOver pass per #44.
- [ ] **Widget:** add the home-screen TTLWidget to the VoiceOver pass and verify labels.
## OTP field accessibility (TalkBack / VoiceOver)

Covers issue #230.

- [ ] iOS: In TwoFactorVerifyView, activate VoiceOver and focus the OTP code field.
      Confirm VoiceOver announces "OTP code field" and the entry progress
      (e.g. "3 of 6 digits entered") as digits are typed.
- [ ] Android: Enable TalkBack and focus the OTP code field in TwoFactorVerifyScreen.
      Confirm TalkBack reads "OTP code field, 3 of 6 digits entered" as digits are typed.
- [ ] Confirm the field is not split into multiple unlabelled boxes that TalkBack/VoiceOver
      would read without positional context.