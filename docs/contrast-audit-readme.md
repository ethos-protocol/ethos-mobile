# Dynamic-Color Contrast Audit — Implementation Notes

## What changed

- `android/app/src/main/java/com/ethosprotocol/ui/theme/Theme.kt`: added a `highContrast`
  parameter to `EthosProtocolTheme`. When `true`, it overrides only the two status-communicating
  color roles that dynamic color can't guarantee WCAG AA contrast for:
  - `error` / `onError` — used by the "Expiring soon!" warning on `VaultCard`.
  - `tertiaryContainer` / `onTertiaryContainer` — used by `OfflineBanner`.
  The override values were chosen to hit at least a 4.5:1 (WCAG AA) contrast ratio, several
  comfortably into AAA (7:1+), in both light and dark variants.
- `docs/manual-qa-checklist.md`: added a "Dynamic-color contrast pass" section describing how to
  manually sample several wallpaper-derived palettes and verify the warning/banner colors, with a
  fallback step to enable the new override if a sampled palette fails.

## Why a manual audit instead of automated contrast checks

Dynamic color is generated at runtime from the device wallpaper (`dynamicLightColorScheme` /
`dynamicDarkColorScheme`), which isn't available in a deterministic form to a JVM-only test
(Paparazzi/Robolectric don't have real wallpaper-derived Material You palettes). The audit is
therefore manual, sampling representative wallpapers on-device, with the checklist recording the
process rather than asserting a specific set of colors.

## Follow-up

- Wire `highContrast` to a persisted user preference and a Settings screen toggle (no such screen
  exists yet in the Android app).
- Consider a lint/CI check that compares `error`/`onError` and `tertiaryContainer`/
  `onTertiaryContainer` for the two static (non-dynamic) API < 31 fallback schemes
  (`darkColorScheme()` / `lightColorScheme()`), which — unlike dynamic color — are deterministic
  and could be asserted in a unit test.
