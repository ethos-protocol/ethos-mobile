#!/usr/bin/env bash
# Smoke-tests core flows (auth, list vaults, check-in) against a staging
# environment. Intended to run in CI before a release build is cut, catching
# a backend/client contract mismatch (see shared/api-contract.md) early.
#
# Required env vars:
#   STAGING_API_BASE_URL   e.g. https://staging-api.ethos-protocol.app
#   STAGING_SMOKE_TOKEN    long-lived JWT for a dedicated smoke-test account
#   STAGING_SMOKE_VAULT_ID vault ID owned by that account, safe to check-in
#                          against repeatedly (checkin only extends TTL)
set -euo pipefail

: "${STAGING_API_BASE_URL:?STAGING_API_BASE_URL is required}"
: "${STAGING_SMOKE_TOKEN:?STAGING_SMOKE_TOKEN is required}"
: "${STAGING_SMOKE_VAULT_ID:?STAGING_SMOKE_VAULT_ID is required}"

fail() { echo "SMOKE TEST FAILED: $1" >&2; exit 1; }

request() {
  # request METHOD PATH [extra curl args...]
  local method="$1" path="$2"; shift 2
  curl -sS -o /tmp/smoke_body.json -w "%{http_code}" \
    -X "$method" "${STAGING_API_BASE_URL}${path}" \
    -H "Authorization: Bearer ${STAGING_SMOKE_TOKEN}" \
    "$@"
}

echo "== 1/2: auth + list vaults (GET /vaults) =="
status=$(request GET /vaults)
[ "$status" = "200" ] || fail "GET /vaults returned $status: $(cat /tmp/smoke_body.json)"
grep -q '"vaults"' /tmp/smoke_body.json || fail "GET /vaults response missing 'vaults' field"
echo "OK ($status)"

echo "== 2/2: check-in (POST /vaults/{id}/checkin) =="
nonce=$(openssl rand -hex 32)
timestamp=$(date +%s)
status=$(request POST "/vaults/${STAGING_SMOKE_VAULT_ID}/checkin" \
  -H "Content-Type: application/json" \
  -H "X-Nonce: ${nonce}" \
  -H "X-Timestamp: ${timestamp}" \
  -d '{}')
[ "$status" = "200" ] || fail "POST /vaults/${STAGING_SMOKE_VAULT_ID}/checkin returned $status: $(cat /tmp/smoke_body.json)"
echo "OK ($status)"

echo "All smoke tests passed against ${STAGING_API_BASE_URL}"
