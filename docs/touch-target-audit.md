# Touch Target Audit (Issue: Accessibility — minimum tap target size)

Audit of interactive controls in `Screens.kt` (Android) and `Views.swift` (iOS) against the
platform minimum touch target sizes: 48x48dp on Android, 44x44pt on iOS.

## Findings

| Control | File | Before | After |
|---|---|---|---|
| `StatusChip` (VaultCard status row) | `Screens.kt` | `SuggestionChip` default height ~32dp — below the 48dp minimum | Wrapped in a `Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))` to guarantee a 48dp tap target while keeping the compact visual size |
| Top bar `IconButton`s (Add vault, overflow menu, back) | `Screens.kt` | Material3 `IconButton` defaults to 48dp — compliant | No change needed; confirmed via audit |
| Deep-link screen "Done" `IconButton` | `Screens.kt` (lines ~961, ~1097, ~1422) | Material3 default — compliant | No change needed; confirmed via audit |
| Notification inline "Check In" action | `NotificationHelper.kt` | Rendered by the OS notification shade, not app layout | Out of app control; platform (Android System UI) is responsible for the tap target of `NotificationCompat.Action` |
| iOS toolbar buttons (`Image(systemName:)`, `Label`) | `Views.swift` | SwiftUI toolbar buttons default to a 44pt minimum via `.contentShape`/system button styling | Confirmed compliant; no change needed |

## Follow-up

- The `StatusChip` fix is the only code change required by this audit; it was the one dense-row
  control (VaultCard) called out explicitly in the issue.
- See `.github/workflows/android-ci.yml` (`accessibility-scan` job) for the new automated guard
  against regressions, using the Android Accessibility Test Framework (ATF) via Espresso's
  `AccessibilityChecks.enable()`.
- iOS: Xcode's Accessibility Inspector does not currently expose a scriptable CLI target suitable
  for CI. `xcrun simctl` + `axclient` automation is tracked as a follow-up; until then, the
  `docs/manual-qa-checklist.md` TalkBack/VoiceOver pass covers this manually on iOS.
