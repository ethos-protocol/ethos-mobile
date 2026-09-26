# RTL Layout Support and Testing

This document describes RTL (Right-to-Left) layout testing for both iOS and Android platforms to ensure the Ethos Protocol mobile app displays correctly in RTL locales (Arabic, Hebrew, etc.).

## Overview

Both platforms support automatic RTL mirroring for standard UI components:
- **Android Compose**: Automatically mirrors layouts, padding, and text alignment based on locale
- **iOS SwiftUI**: Automatically mirrors layouts based locale settings

This document covers manual testing procedures to verify RTL support and known limitations.

## Android RTL Testing

### Enable RTL Layout in Debug Build

#### Method 1: Using ADB (Recommended for testing)

```bash
# Enable force RTL layout (applies to app after restart)
adb shell settings put global debug.force_rtl_layout 1

# Disable force RTL layout
adb shell settings put global debug.force_rtl_layout 0

# Verify current setting
adb shell settings get global debug.force_rtl_layout
```

#### Method 2: Using Android Studio Device Settings

1. Open your device in Android Emulator or connected device
2. Go to Settings → Developer Options (if not visible, tap Build Number 7 times)
3. Search for or scroll to "Force RTL layout direction"
4. Toggle ON to enable RTL

### Screens to Test

After enabling RTL layout, verify the following screens display correctly:

1. **Auth Screen** (`AuthScreen`)
   - [ ] App title centered
   - [ ] Sign in button full width
   - [ ] Error messages aligned correctly

2. **Vault List Screen** (`VaultListScreen`)
   - [ ] Vault cards display with RTL mirroring
   - [ ] Status chips positioned correctly (right side in RTL)
   - [ ] Icons mirror appropriately
   - [ ] TopAppBar title and actions aligned correctly

3. **Vault Detail Screen** (`VaultDetailScreen`)
   - [ ] Check-in button layout
   - [ ] Deposit/Withdraw buttons layout

4. **Beneficiary Screens**
   - [ ] Beneficiary Acceptance screen reads correctly
   - [ ] Manage Beneficiary form fields aligned properly

5. **2FA Screens** (`TwoFactorAuthScreen`)
   - [ ] Form fields aligned correctly
   - [ ] Status indicators positioned properly

### Known Limitations

- Custom Compose layouts that don't use standard Row/Column primitives may need explicit RTL handling
- Hardcoded `padding(start=)` or `padding(end=)` should be avoided; use `padding(horizontal=)` instead for automatic mirroring

### RTL Support in Compose

For custom layouts, ensure RTL awareness:

```kotlin
// ✅ Good - automatically mirrors in RTL
Row(modifier = Modifier.padding(horizontal = 16.dp))

// ❌ Avoid - doesn't mirror in RTL
Row(modifier = Modifier.padding(start = 16.dp, end = 0.dp))

// ✅ Good - directional awareness
Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(horizontal = 16.dp)
)
```

## iOS RTL Testing

### Enable RTL Scheme in Xcode

1. In Xcode, open your scheme settings: Product → Scheme → Edit Scheme
2. In the Run configuration, go to the Options tab
3. Set "App Language" to a pseudo-language with RTL support:
   - Arabic (Pseudo) - `ar-XB` (pseudo-bidirectional)
   - Hebrew (Pseudo) - `he-XB`

Alternatively, in SwiftUI Preview, add a modifier:

```swift
.environment(\.locale, Locale(identifier: "ar"))
```

### Screens to Test

After enabling RTL in the scheme, verify:

1. **Auth Views** (`AuthView`)
   - [ ] Sign-in button full width and properly aligned
   - [ ] Text fields use RTL input method
   - [ ] Error messages display in correct direction

2. **Vault List View** (`VaultListView`)
   - [ ] Vault cards display with proper mirroring
   - [ ] Action buttons (Check-In, Deposit, Withdraw) positioned correctly
   - [ ] Expiring Soon indicator on the right side
   - [ ] Pull-to-refresh gesture works in RTL context

3. **Vault Detail View** (`VaultDetailView`)
   - [ ] All buttons and controls mirror correctly
   - [ ] Check-in confirmation dialog layout

4. **Beneficiary Views** (`BeneficiaryAcceptanceView`, `ManageBeneficiaryView`)
   - [ ] Text fields and labels aligned correctly
   - [ ] Confirmation steps readable and properly oriented

5. **2FA Views** (`TwoFactorAuthView`)
   - [ ] Input fields and labels properly aligned
   - [ ] Status indicators positioned correctly

### SwiftUI RTL Best Practices

For RTL support in SwiftUI, follow these practices:

```swift
// ✅ Good - uses standard HStack which auto-mirrors
HStack(spacing: 8) {
    Image(systemName: "checkmark")
    Text("Status: Complete")
}

// ✅ Good - uses leading/trailing which auto-mirror
HStack {
    Text("Title")
    Spacer()
    Image(systemName: "chevron.right")
}

// ❌ Avoid - hardcoded alignment that doesn't respect RTL
HStack {
    Spacer()
    Text("Title")
    Image(systemName: "chevron.right")
    Spacer()
}
```

## Automated Testing Strategy

Currently, automated RTL snapshot testing is not fully integrated. The following approaches are recommended:

### For Android (Future Enhancement)

Paparazzi snapshot testing could be extended with custom layout direction configuration once support is added to the library. Current workaround:

1. Manual verification using `adb shell settings put global debug.force_rtl_layout 1`
2. Run existing Paparazzi screenshot tests with RTL enabled manually

### For iOS (Future Enhancement)

Snapshot testing with RTL pseudo-language could be added using `SnapshotTesting` library:

```swift
// Example (not currently implemented):
assertSnapshot(matching: view, as: .image(traits: UITraitCollection(locale: Locale(identifier: "ar"))))
```

## Localization Files

### Android

- String resources: `android/app/src/main/res/values/strings.xml`
- Create locale-specific resources in `values-ar/`, `values-he/` directories for translations
- Layout direction automatically handled by Android framework

### iOS

- Localized strings: Use `Localizable.strings` files in `en.lproj/`, `ar.lproj/`, etc.
- SwiftUI automatically respects locale and mirrors layouts

## Testing Checklist

Use this checklist before marking RTL support as complete:

- [ ] Enable RTL on device/emulator
- [ ] Test all main screens render without clipping or overlap
- [ ] Verify all buttons and interactive elements are positioned correctly
- [ ] Check text direction and alignment (should be RTL for most text)
- [ ] Verify numeric values and special characters display correctly
- [ ] Test both light and dark themes in RTL mode
- [ ] Check all notification and alert dialogs in RTL mode
- [ ] Verify deep links work correctly with RTL enabled
- [ ] Test keyboard input with RTL locale

## Related Issues

- #314: Add RTL Layout Support and Testing
- #312: Externalize Hardcoded Strings (required for localization)
- #313: Add Localized Number/Currency Formatting (affects balance display in RTL)
