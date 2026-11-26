#!/usr/bin/env bash
# verify_app_store_id.sh
#
# Verifies that the iOS App Store ID linked in .well-known fallback pages:
#   1. Is present and not a placeholder (e.g. id1234567890).
#   2. Is consistent across all fallback pages (.well-known/vault-invite-fallback.html and .well-known/beneficiary-accept-fallback.html).
#   3. Matches APPLE_APP_STORE_ID if the environment variable is provided.
#   4. Resolves against Apple's App Store / iTunes lookup API.
#
# Usage:
#   ./scripts/verify_app_store_id.sh
#
# Environment variables:
#   APPLE_APP_STORE_ID   – Expected numeric App Store ID (optional, e.g. from GitHub Actions vars)
#   SKIP_NETWORK_CHECK   – Set to "true" to skip network verification (e.g. offline unit testing)
#
# Exit codes:
#   0 – All checks passed
#   1 – One or more checks failed
#
# Cross-reference: #656

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VAULT_INVITE_FILE="$REPO_ROOT/.well-known/vault-invite-fallback.html"
BENEFICIARY_ACCEPT_FILE="$REPO_ROOT/.well-known/beneficiary-accept-fallback.html"
APPLE_APP_STORE_ID="${APPLE_APP_STORE_ID:-}"
SKIP_NETWORK_CHECK="${SKIP_NETWORK_CHECK:-false}"

FAIL=0

fail() {
    echo "ERROR: $*" >&2
    FAIL=1
}

# ── 1. Check required files exist ──────────────────────────────────────────────
for f in "$VAULT_INVITE_FILE" "$BENEFICIARY_ACCEPT_FILE"; do
    if [[ ! -f "$f" ]]; then
        fail "Required fallback file not found: $f"
    fi
done

if [[ "$FAIL" -ne 0 ]]; then
    exit 1
fi

# ── 2. Extract and validate App Store IDs from HTML files ──────────────────────
IDS_JSON=$(python3 - "$VAULT_INVITE_FILE" "$BENEFICIARY_ACCEPT_FILE" <<'PY'
import json, re, sys

files = sys.argv[1:]
results = {}
pattern = re.compile(r'https://apps\.apple\.com/app/(?:[^/]+/)?id(\d+)')

for path in files:
    with open(path, encoding="utf-8") as fh:
        content = fh.read()
    matches = pattern.findall(content)
    results[path] = matches

print(json.dumps(results))
PY
)

EXTRACT_STATUS=0
python3 - "$IDS_JSON" "$APPLE_APP_STORE_ID" <<'PY' || EXTRACT_STATUS=$?
import json, sys

data = json.loads(sys.argv[1])
expected_env_id = sys.argv[2].strip()

PLACEHOLDER_IDS = {"1234567890", "0000000000"}
extracted_ids = {}

has_error = False

for path, ids in data.items():
    if not ids:
        print(f"ERROR: No App Store link found in {path}", file=sys.stderr)
        has_error = True
        continue
    app_id = ids[0]
    extracted_ids[path] = app_id
    if app_id in PLACEHOLDER_IDS:
        print(f"ERROR: {path} contains placeholder App Store ID 'id{app_id}'", file=sys.stderr)
        has_error = True
    elif not app_id.isdigit():
        print(f"ERROR: {path} contains non-numeric App Store ID 'id{app_id}'", file=sys.stderr)
        has_error = True

# Check consistency across all fallback files
unique_ids = set(extracted_ids.values())
if len(unique_ids) > 1:
    print(f"ERROR: Inconsistent App Store IDs found across fallback pages: {extracted_ids}", file=sys.stderr)
    has_error = True

if expected_env_id:
    for path, app_id in extracted_ids.items():
        if app_id != expected_env_id:
            print(f"ERROR: {path} App Store ID ({app_id}) does not match APPLE_APP_STORE_ID ({expected_env_id})", file=sys.stderr)
            has_error = True

if has_error:
    sys.exit(1)

# Print canonical ID for downstream steps
if unique_ids:
    print(next(iter(unique_ids)))
PY

if [[ "$EXTRACT_STATUS" -ne 0 ]]; then
    fail "App Store ID validation failed in fallback pages"
    exit 1
fi

APP_ID=$(python3 - "$IDS_JSON" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
for ids in data.values():
    if ids:
        print(ids[0])
        break
PY
)

echo "OK: Valid App Store ID found in fallback files: id$APP_ID"

# ── 3. Verify App Store ID resolves against Apple API ──────────────────────────
if [[ "$SKIP_NETWORK_CHECK" == "true" ]]; then
    echo "SKIP: Network check skipped (SKIP_NETWORK_CHECK=true)"
else
    echo "Verifying App Store ID resolves (id$APP_ID)..."
    LOOKUP_URL="https://itunes.apple.com/lookup?id=$APP_ID"
    HTTP_CODE=0
    WORK_DIR="$(mktemp -d)"
    trap 'rm -rf "$WORK_DIR"' EXIT

    CURL_EXIT=0
    HTTP_CODE=$(curl --silent --output "$WORK_DIR/lookup.json" \
                     --write-out "%{http_code}" \
                     --max-time 15 \
                     "$LOOKUP_URL" 2>"$WORK_DIR/curl_err.txt") || CURL_EXIT=$?

    if [[ "$CURL_EXIT" -ne 0 && "$HTTP_CODE" == "000" ]]; then
        fail "Could not connect to Apple iTunes lookup API ($LOOKUP_URL) - curl exit $CURL_EXIT"
    elif [[ "$HTTP_CODE" != "200" ]]; then
        fail "iTunes lookup returned HTTP $HTTP_CODE for App Store ID $APP_ID"
    else
        RESOLVED=$(python3 - "$WORK_DIR/lookup.json" "$APP_ID" <<'PY'
import json, sys

try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        res = json.load(fh)
    count = res.get("resultCount", 0)
    if count > 0:
        item = res["results"][0]
        track_name = item.get("trackName", "Unknown")
        print(f"RESOLVED: {track_name}")
        sys.exit(0)
    else:
        print("NOT_FOUND")
        sys.exit(1)
except Exception as e:
    print(f"ERROR: {e}", file=sys.stderr)
    sys.exit(2)
PY
) || RESOLVED="FAILED"

        if [[ "$RESOLVED" == RESOLVED:* ]]; then
            echo "OK: App Store ID $APP_ID resolves: ${RESOLVED#RESOLVED: }"
        else
            # Also test direct App Store web page URL as fallback check
            STORE_URL="https://apps.apple.com/app/id$APP_ID"
            STORE_HTTP=$(curl --silent -o /dev/null --write-out "%{http_code}" --max-time 15 -L "$STORE_URL" 2>/dev/null || echo "000")
            if [[ "$STORE_HTTP" == "200" ]]; then
                echo "OK: App Store page $STORE_URL resolves (HTTP 200)"
            else
                fail "App Store ID $APP_ID does not resolve on App Store (lookup returned 0 results, web returned HTTP $STORE_HTTP)"
            fi
        fi
    fi
fi

if [[ "$FAIL" -ne 0 ]]; then
    echo "FAILED: App Store ID verification failed." >&2
    exit 1
fi

echo "PASSED: App Store ID verification passed."
