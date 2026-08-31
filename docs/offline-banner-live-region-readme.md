# Offline Banner Transition Announcements — Implementation Notes

## Problem

The offline banner (Android `OfflineBanner` in `Screens.kt`, iOS `StatusBannerView` in
`Views.swift`) is a labeled view, so a screen reader announces it when it first appears in the
hierarchy. But `docs/manual-qa-checklist.md`'s TalkBack pass only ever exercised the *static*
banner (already offline when the screen loads) — the *transition* case (going offline while the
screen is already open, or coming back online) requires an explicit live-region / announcement
API call, which nothing in the codebase was making.

## What changed

- **Android** (`Screens.kt`, `VaultListScreen`): added a `LaunchedEffect(state.isOffline)` that
  compares against the previous value (via a `remember { mutableStateOf<Boolean?>(null) }`) and
  calls `View.announceForAccessibility(...)` only on an actual transition, skipping the initial
  composition. Announces `"Offline — showing cached data"` and `"Back online"`.
- **iOS** (`Views.swift`, `VaultListView.body`): added
  `.onChange(of: vaultStore.vaultsCacheAge == nil)` calling
  `UIAccessibility.post(notification: .announcement, argument:)` with the same two messages.
  SwiftUI's `onChange` already only fires on an actual value change, so no manual "is this the
  first render" tracking is needed on this side.
- **`docs/manual-qa-checklist.md`**: added an explicit checklist item under the TalkBack/VoiceOver
  pass to go offline→online→offline and confirm both transition announcements fire (not just the
  initial banner appearance), plus a note that the WebSocket connection-status indicator proposed
  in issue #254 doesn't exist in the codebase yet — when it's built, it should reuse this same
  announce-on-transition pattern for its connecting/connected/reconnecting states.

## Why a documented manual step instead of an automated test

`announceForAccessibility` / `UIAccessibility.post` fire real platform accessibility events that
aren't observable from a JVM-only Robolectric/Paparazzi test or a SwiftUI preview — verifying they
actually reach TalkBack/VoiceOver requires either a real accessibility-service listener in an
instrumented test (heavier than this fix warrants) or a manual pass. The checklist step above
covers it manually for now; wiring an `AccessibilityEvent` listener into
`AccessibilityScanTest.kt` (added in the touch-target-audit change) is a reasonable follow-up.
