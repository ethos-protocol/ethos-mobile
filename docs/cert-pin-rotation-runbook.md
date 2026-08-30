# Certificate Pin Rotation Runbook

> **Audience:** Engineers on-call for the Ethos Protocol mobile platform.
> **Related CI jobs:** `cert-pin-expiry-monitor.yml` (daily, warns at 90 days, errors at 14 days).
> **Related issues:** #273 (monitoring), #117 (pinning implementation).

---

## Overview

Both the iOS and Android apps enforce TLS public-key pinning (SPKI SHA-256 hashes)
against `api.ethos-protocol.app`. Pinning prevents MITM attacks even when a CA is
compromised, but it introduces operational risk: if the server's certificate is rotated
without updating the app's pin set first, **every app version in the field loses
connectivity immediately**.

This runbook describes the zero-downtime rotation procedure and the CI signals that
trigger it.

---

## Timetable

| Milestone | Lead time before expiry | Action |
|---|---|---|
| Monitor warns | T − 90 days | Begin rotation: generate new cert, compute pin, open PR |
| App update shipped to stores | T − 60 days | Both iOS and Android updates live |
| Server cert rotated | T − 30 days | Replace live cert; keep old cert valid in pin set |
| Old pin removed | T − 14 days | Follow-up PR removes the expired pin |
| Old cert expires | T | No action needed — old pin was already removed |

> **Rule of thumb:** The app update must be live in both stores at least 30 days before
> the old certificate expires, to allow stragglers on older app versions to update before
> the old certificate disappears.

---

## Step-by-step Rotation Procedure

### 1. Generate the new server certificate

Work with your infrastructure team or certificate authority to generate the new
certificate for `api.ethos-protocol.app`. Do **not** deploy it to the server yet.

### 2. Compute the SPKI SHA-256 hash of the new certificate

```bash
# From a PEM-encoded certificate file:
openssl x509 -in new-cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

Or, if the certificate is already deployed to a staging environment:

```bash
openssl s_client -connect staging.ethos-protocol.app:443 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

The output is a 44-character Base64 string (43 characters + one `=` pad),
for example: `sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=`

### 3. Add the new pin alongside the existing pin — iOS

Open `ios/EthosProtocol/EthosProtocol/Info.plist` and
`ios/EthosProtocol/TTLWidget/Info.plist`. Add the **new** hash as a second entry
in the `TLS_PUBLIC_KEY_PINS` array (the **old** hash must stay until the server
cert is rotated in Step 6):

```xml
<key>TLS_PUBLIC_KEY_PINS</key>
<array>
    <string><!-- current (old) SPKI SHA-256 Base64 --></string>
    <string><!-- new SPKI SHA-256 Base64 --></string>
</array>
```

Both files must be updated — the app extension (`TTLWidget`) reads its own
`Bundle.main` independently and does not inherit the host app's Info.plist.

### 4. Add the new pin alongside the existing pin — Android

Set the `ETHOS_CERT_PINS` repository secret (Settings → Secrets → Actions) to
the **comma-separated list of both pins**:

```
AAAA...current_pin...AAAA=,BBBB...new_pin...BBBB=
```

Alternatively, edit `android/app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt`
and update `DEFAULT_PINS`:

```kotlin
internal val DEFAULT_PINS: Set<String> = setOf(
    "AAAA...current_pin...AAAA=",   // current cert — remove after server rotation
    "BBBB...new_pin...BBBB=",       // new cert — added ahead of rotation
)
```

### 5. Open and merge the pin-update PR

Create a PR titled **`chore(security): add backup certificate pin for upcoming rotation`**.

CI checks:
- `check_tls_pinning.py` will pass (Release builds have a non-empty pin array).
- `verify_cert_pins.py` will pass (the new pin is a valid 44-char Base64 digest).

The PR **must** be merged and the app update shipped to both stores before Step 6.

### 6. Verify the app update is live

Confirm in App Store Connect and Google Play Console that the update is live
and has reached a sufficient percentage of the install base (aim for > 99%
of active devices on the new version before rotating the server cert).

Use the 30-day lead time from Step 5 to ship the update.

### 7. Rotate the server certificate

Work with your infrastructure team to deploy the new certificate to
`api.ethos-protocol.app`. The app will continue to work because both the old
and new pins are trusted (both are in the pin set after Step 3/4).

Verify with:

```bash
openssl s_client -connect api.ethos-protocol.app:443 2>/dev/null \
  | openssl x509 -noout -dates
```

Confirm `notAfter` reflects the new certificate's expiry date.

### 8. Remove the old pin — iOS and Android

Open a follow-up PR:

**iOS** (`EthosProtocol/Info.plist` and `TTLWidget/Info.plist`):
```xml
<key>TLS_PUBLIC_KEY_PINS</key>
<array>
    <!-- removed old pin -->
    <string><!-- new SPKI SHA-256 Base64 --></string>
</array>
```

**Android** (`CertificatePinning.kt`):
```kotlin
internal val DEFAULT_PINS: Set<String> = setOf(
    // removed old pin
    "BBBB...new_pin...BBBB=",   // the new cert
)
```

Update `ETHOS_CERT_PINS` to contain only the new pin.

Merge and release this update before the old certificate expires.

---

## Emergency Rotation (Certificate Compromised)

If the current certificate must be revoked immediately (key compromise, CA breach):

1. **Do not rotate the server cert yet.**
2. Generate the new cert and compute its pin.
3. Push a hotfix PR that adds the new pin alongside the old one (Steps 3–4 above).
4. Expedite review and get emergency approval for both stores (24–48 hour turnaround is possible).
5. Once the app update is live, rotate the server cert (Step 7).
6. Remove the old pin in a follow-up (Step 8).

> In an active compromise, coordinate with your security team. It may be necessary
> to temporarily return HTTP 503 or redirect users to an in-app update prompt
> while the new app version propagates.

---

## Verifying the Monitor

To test the expiry monitor locally:

```bash
# Check current cert expiry against configured pins
python3 .github/scripts/check_cert_expiry.py \
  --host api.ethos-protocol.app \
  --port 443 \
  --warn-days 90 \
  --ios-plist ios/EthosProtocol/EthosProtocol/Info.plist \
  --ios-plist ios/EthosProtocol/TTLWidget/Info.plist \
  --android-source android/app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt
```

The script outputs a JSON report to `cert-expiry-report.json` and emits GitHub Actions
`::warning::` / `::error::` annotations when running in CI.

---

## Troubleshooting

### App loses connectivity immediately after cert rotation
The pin set in the shipped app does not include the new certificate's hash.
**Immediate mitigation:** revert the server cert to the previous one.
**Fix:** follow this runbook from Step 2, this time with the new cert deployed to staging first.

### CI warns "No pinned certificate hashes found"
- iOS: `TLS_PUBLIC_KEY_PINS` is missing or empty in one or both Info.plist files.
- Android: `ETHOS_CERT_PINS` secret is unset **and** `CertificatePinning.kt` has no non-placeholder `DEFAULT_PINS`.

### CI warns about an upcoming expiry but the cert was already rotated
The pin set in the codebase still contains the old (replaced) hash. Open a PR to remove it.

---

## Key Files

| File | Purpose |
|---|---|
| `ios/EthosProtocol/EthosProtocol/Info.plist` | iOS app pin set |
| `ios/EthosProtocol/TTLWidget/Info.plist` | TTLWidget extension pin set |
| `android/app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt` | Android pin source |
| `ios/EthosProtocol/Sources/Services/CertificatePinning.swift` | iOS pinning implementation |
| `.github/workflows/cert-pin-expiry-monitor.yml` | Daily CI job |
| `.github/scripts/check_cert_expiry.py` | Expiry monitoring script |
| `.github/scripts/check_tls_pinning.py` | Release-gate: pin must be non-empty |
| `.github/scripts/verify_cert_pins.py` | Release-gate: pin must not be placeholder |
