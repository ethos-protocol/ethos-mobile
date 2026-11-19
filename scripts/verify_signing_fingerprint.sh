#!/usr/bin/env bash
# verify_signing_fingerprint.sh
#
# Checks that the release signing certificate fingerprint is consistent across:
#   1. shared/cert-pins.json            (single source of truth, colon-hex SHA-256)
#   2. .well-known/assetlinks.json      (Android App Links, must advertise it)
#   3. SignatureVerifier.kt             (EXPECTED_CERT_SHA256, Base64 SHA-256)
#
# It fails when the release fingerprint is the all-zero placeholder, because a
# placeholder means App Links and APK signature verification cannot work.
#
# Environment variables (set by .github/workflows/android-applinks-verify.yml):
#   CERT_PINS_FILE            path to cert-pins.json            (default: shared/cert-pins.json)
#   ASSETLINKS_FILE           path to assetlinks.json           (default: .well-known/assetlinks.json)
#   SIGNATURE_VERIFIER_FILE   path to SignatureVerifier.kt      (default: android/app/src/main/java/com/ethosprotocol/security/SignatureVerifier.kt)
#   PLAY_PACKAGE_NAME         Android package name              (default: com.ethosprotocol)
#   PLAY_SERVICE_ACCOUNT_JSON optional Play Developer API key. When set, the Play App
#                             Signing comparison is requested, but it is NOT implemented
#                             here yet, so the script warns instead of checking it.
#
# Exit codes:
#   0 - all checks passed (or were skipped with a stated reason)
#   1 - a check failed; details are printed to stderr

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_PINS_FILE="${CERT_PINS_FILE:-$REPO_ROOT/shared/cert-pins.json}"
ASSETLINKS_FILE="${ASSETLINKS_FILE:-$REPO_ROOT/.well-known/assetlinks.json}"
SIGNATURE_VERIFIER_FILE="${SIGNATURE_VERIFIER_FILE:-$REPO_ROOT/android/app/src/main/java/com/ethosprotocol/security/SignatureVerifier.kt}"
PLAY_PACKAGE_NAME="${PLAY_PACKAGE_NAME:-com.ethosprotocol}"

FAIL=0

fail() {
    echo "ERROR: $*" >&2
    FAIL=1
}

for f in "$CERT_PINS_FILE" "$ASSETLINKS_FILE" "$SIGNATURE_VERIFIER_FILE"; do
    if [[ ! -f "$f" ]]; then
        echo "ERROR: required file not found: $f" >&2
        exit 1
    fi
done

# ── 1. Release fingerprint from cert-pins.json ───────────────────────────────
PINNED="$(python3 - "$CERT_PINS_FILE" <<'PY'
import json, sys
with open(sys.argv[1]) as fh:
    value = (json.load(fh).get("release_signing_sha256") or "").strip().upper()
print(value)
PY
)"

if [[ -z "$PINNED" ]]; then
    fail "release_signing_sha256 is missing or empty in $CERT_PINS_FILE"
elif [[ ! "$PINNED" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]]; then
    fail "release_signing_sha256 is not 32 colon-separated hex bytes: $PINNED"
elif [[ "$PINNED" =~ ^(00:){31}00$ ]]; then
    fail "release_signing_sha256 is the all-zero placeholder; set the real release certificate fingerprint in $CERT_PINS_FILE"
else
    echo "OK: release fingerprint in cert-pins.json is a real value"
fi

# ── 2. assetlinks.json advertises the pinned fingerprint ─────────────────────
if [[ -n "$PINNED" && "$PINNED" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]]; then
    if python3 - "$PLAY_PACKAGE_NAME" "$PINNED" "$ASSETLINKS_FILE" <<'PY'
import json, sys
pkg, pinned, path = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path) as fh:
    data = json.load(fh)
found = {
    fp.upper()
    for entry in data
    if entry.get("target", {}).get("package_name") == pkg
    for fp in entry.get("target", {}).get("sha256_cert_fingerprints", [])
}
sys.exit(0 if pinned in found else 1)
PY
    then
        echo "OK: assetlinks.json advertises the pinned release fingerprint"
    else
        fail "assetlinks.json does not advertise the pinned release fingerprint for $PLAY_PACKAGE_NAME"
    fi
fi

# ── 3. SignatureVerifier.EXPECTED_CERT_SHA256 matches the pin (Base64) ───────
VERIFIER_VALUE="$(python3 - "$SIGNATURE_VERIFIER_FILE" <<'PY'
import re, sys
with open(sys.argv[1]) as fh:
    text = fh.read()
m = re.search(r'EXPECTED_CERT_SHA256\s*:\s*String\s*=\s*"([^"]*)"', text)
print(m.group(1) if m else "__MISSING__")
PY
)"

if [[ "$VERIFIER_VALUE" == "__MISSING__" ]]; then
    fail "EXPECTED_CERT_SHA256 constant not found in $SIGNATURE_VERIFIER_FILE"
elif [[ -z "$VERIFIER_VALUE" ]]; then
    echo "WARN: EXPECTED_CERT_SHA256 is blank; APK signature check is disabled (debug/CI default)"
elif [[ -n "$PINNED" && "$PINNED" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]]; then
    EXPECTED_B64="$(python3 - "$PINNED" <<'PY'
import base64, sys
raw = bytes.fromhex(sys.argv[1].replace(":", ""))
print(base64.b64encode(raw).decode())
PY
)"
    if [[ "$VERIFIER_VALUE" == "$EXPECTED_B64" ]]; then
        echo "OK: SignatureVerifier.EXPECTED_CERT_SHA256 matches the pinned fingerprint"
    else
        fail "SignatureVerifier.EXPECTED_CERT_SHA256 does not match cert-pins.json release_signing_sha256 (expected Base64 $EXPECTED_B64)"
    fi
fi

# ── 4. Play App Signing comparison (not implemented yet) ─────────────────────
if [[ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]]; then
    echo "WARN: PLAY_SERVICE_ACCOUNT_JSON is set, but the Play App Signing comparison is not implemented; it was not checked"
else
    echo "SKIP: PLAY_SERVICE_ACCOUNT_JSON not set; Play App Signing comparison skipped"
fi

if [[ "$FAIL" -ne 0 ]]; then
    echo "FAILED: signing fingerprint checks failed; Android App Links and APK verification are affected." >&2
    exit 1
fi

echo "PASSED: signing fingerprint checks passed."
