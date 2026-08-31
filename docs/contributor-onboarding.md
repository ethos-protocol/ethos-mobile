# Contributor Onboarding

Minimal path to a running **debug** build on each platform. This intentionally
skips release-signing and certificate-pinning setup — see the links at the end
for those when you're ready to ship a release build.

## iOS

1. Install XcodeGen: `brew install xcodegen`
2. From `ios/EthosProtocol`, run:
   ```bash
   mkdir -p Xcode && xcodegen generate --project Xcode
   ```
3. Open `ios/EthosProtocol/Xcode/EthosProtocol.xcodeproj` in Xcode 15+
4. Set your Apple Developer Team in signing settings for the `EthosProtocol`
   and `TTLWidget` targets (any personal free account works for local runs)
5. Build and run

That's it for a debug build — `API_BASE_URL` and `TLS_PUBLIC_KEY_PINS` in both
`Info.plist`s already ship with working local-dev defaults, and Debug builds
are exempt from the CI pin check and from pinning enforcement at runtime.

**Skip for local dev (release-only):** Apple App Site Association setup,
enabling Push/Associated Domains/iCloud/Keychain Sharing capabilities in the
Developer portal, and configuring real `TLS_PUBLIC_KEY_PINS` values. These are
README.md Setup → iOS steps 5-8.

## Android

1. Open `android` in Android Studio Hedgehog+
2. Let Gradle sync — no `google-services.json` or cert pins needed to build
   and run a debug variant
3. Run the app

**Skip for local dev (release-only):** adding `google-services.json`,
configuring `assetlinks.json`, and setting `ETHOS_CERT_PINS`/`ethos.certPins`.
These are README.md Setup → Android steps 2, 3, and 5 — release builds fall
back to placeholder pins and CI enforces real ones only once release signing
is configured.

## Next steps

Once your debug build runs, see [README.md](../README.md#setup) for the full
setup steps (release signing, certificate pinning, push notifications,
universal links) and [README.md](../README.md#testing) for running the test
suites.
