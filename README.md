# Mobile App Architecture

## Overview

Ethos-Protocol mobile apps (iOS + Android) provide a native interface for managing vaults, checking in, and receiving expiry reminders. Both apps share the same REST API contract and feature set.

## Structure

```
mobile/
├── shared/
│   └── api-contract.md          # Shared API spec (iOS + Android)
├── ios/EthosProtocol/
│   └── Sources/
│       ├── App/                 # Entry point, app lifecycle
│       ├── Models/              # Vault, AuthToken, etc.
│       ├── Services/
│       │   ├── APIClient.swift      # Ktor-style async HTTP client
│       │   ├── PasskeyService.swift # ASAuthorization / WebAuthn
│       │   ├── KeychainService.swift# Secure token storage
│       │   ├── NotificationService.swift # APNs + local reminders
│       │   └── OfflineSupport.swift # NetworkMonitor + disk cache
│       ├── ViewModels/          # AuthStore, VaultStore (ObservableObject)
│       └── Views/               # SwiftUI screens
└── android/app/src/main/java/com/ethosprotocol/
    ├── api/
    │   ├── ApiClient.kt         # Ktor HTTP client
    │   └── Infrastructure.kt    # NetworkMonitor, OfflineCache, TokenProvider
    ├── models/                  # Kotlinx.serialization data classes
    ├── services/
    │   ├── PasskeyService.kt    # CredentialManager / WebAuthn
    │   ├── PushService.kt       # Firebase Messaging
    │   └── NotificationHelper.kt# Local notification display
    ├── ui/
    │   ├── ViewModels.kt        # AuthViewModel, VaultViewModel (Hilt)
    │   ├── MainActivity.kt      # NavHost entry point
    │   ├── screens/Screens.kt   # Compose screens
    │   └── theme/Theme.kt       # Material3 dynamic color
    └── di/AppModule.kt          # Hilt DI bindings
```

## Key Design Decisions

### Passkey Authentication (WebAuthn)
- **iOS**: `ASAuthorizationPlatformPublicKeyCredentialProvider` (iOS 16+)
- **Android**: `CredentialManager` API (Android 9+, API 28+)
- Flow: `getChallenge()` → device biometric prompt → `verifyPasskey()` → JWT stored in Keychain/SharedPreferences
- Relying party: `ethos-protocol.app` (requires `.well-known/assetlinks.json` + Apple App Site Association)

### Push Notifications
- **iOS**: APNs via `UNUserNotificationCenter`. Device token registered to backend on first launch.
  - Local reminders scheduled 24h before vault expiry via `UNTimeIntervalNotificationTrigger`
  - Actionable notification category `CHECK_IN` with inline "Check In" action
- **Android**: Firebase Cloud Messaging (FCM). Token refreshed via `onNewToken`.
  - Notification channel `ttl_reminders` (IMPORTANCE_HIGH)
  - Deep-link intent to `MainActivity` with `vault_id` extra

### Offline Support
- `NetworkMonitor` checks live connectivity before every request
- `OfflineCache` stores last successful GET responses keyed by URL (SHA-256 filename)
- On network unavailable: cached data served transparently; mutations show "offline" error
- iOS: `CryptoKit.SHA256` for cache keys; Android: `MessageDigest("SHA-256")`

### State Management
- **iOS**: `@StateObject` / `ObservableObject` stores (`AuthStore`, `VaultStore`) injected via SwiftUI environment
- **Android**: Hilt-injected `ViewModel`s with `StateFlow` + `collectAsStateWithLifecycle`

## Setup

### iOS
1. Install [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) — the `.xcodeproj` is generated, not checked in
2. From `ios/EthosProtocol`, run `mkdir -p Xcode && xcodegen generate --project Xcode` to produce `Xcode/EthosProtocol.xcodeproj` (an `EthosProtocol` app target + `TTLWidget` widget extension, per `project.yml`) — the `Xcode/` directory must exist before `xcodegen generate` runs, or the copy step fails
3. Open `ios/EthosProtocol/Xcode/EthosProtocol.xcodeproj` in Xcode 15+
4. Set your Apple Developer Team in signing settings for both the `EthosProtocol` and `TTLWidget` targets (`project.yml` leaves `DEVELOPMENT_TEAM` blank on purpose — bundle IDs `com.ethosprotocol` / `com.ethosprotocol.TTLWidget` are already set)
5. `API_BASE_URL` is already set in `EthosProtocol/Info.plist` and `TTLWidget/Info.plist`; edit both (they're separate bundles, read independently at runtime) if you need to point at a different environment
6. Configure Apple App Site Association at `https://ethos-protocol.app/.well-known/apple-app-site-association`, listing this app's App ID under both `applinks` (Universal Links) and `webcredentials` (platform passkeys)
7. In the Apple Developer portal, enable Push Notifications, Associated Domains, iCloud (Key-Value storage), and Keychain Sharing capabilities for the `com.ethosprotocol` App ID, and Keychain Sharing for `com.ethosprotocol.TTLWidget` — matching `EthosProtocol/EthosProtocol.entitlements` / `TTLWidget/TTLWidget.entitlements`. Set up an APNs key in App Store Connect for push.
8. Re-run `mkdir -p Xcode && xcodegen generate --project Xcode` any time `project.yml` changes; the generated `Xcode/` directory is disposable and shouldn't be committed

### Android
1. Open `android` in Android Studio Hedgehog+
2. Add `google-services.json` from Firebase Console
3. Configure `assetlinks.json` at `https://ethos-protocol.app/.well-known/assetlinks.json`
4. Set `API_BASE_URL` in `build.gradle.kts` `buildConfigField`

## Testing

### iOS
```bash
cd ios/EthosProtocol
swift test
```
Covers: model decoding, Keychain round-trip, offline cache, Base64URL encoding.
Tests run against the SPM package (`Package.swift`) directly and don't require the
XcodeGen-generated project; CI runs this the same way, via `xcodebuild test` against
an iOS Simulator destination (`swift test` alone defaults to macOS, which can't build
the app's iOS-only framework imports).

### Android
```bash
cd android
./gradlew test                  # Unit tests (JVM)
./gradlew connectedAndroidTest  # Instrumented tests (device/emulator)
```
Covers: ViewModel state transitions, model logic, Compose UI smoke tests.
