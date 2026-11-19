#!/usr/bin/env bash
# Staging API preflight for the Cross-Platform E2E workflow (issue #740).
#
# Usage: E2E_API_BASE_URL=https://host e2e_preflight.sh
#
# Exit codes:
#   0  host is valid and /health returned 200
#   1  E2E_API_BASE_URL is missing or not a valid http(s) URL with a host
#   2  the host did not resolve (curl exit 6)
#   3  the host resolved but /health did not return 200 (or the request failed)
#
# Writes api_url=<base url> to $GITHUB_OUTPUT when that variable is set.
set -uo pipefail

SETTING="E2E_API_BASE_URL (set the E2E_API_BASE_URL secret or the staging_api_url input)"
URL="${E2E_API_BASE_URL:-}"

if [[ -z "$URL" ]]; then
  echo "::error::$SETTING is empty. The E2E preflight cannot run without it." >&2
  exit 1
fi

# Accept only http(s)://host[:port][/path]. Anything else is reported by name so the
# fix is obvious from the job log.
if [[ ! "$URL" =~ ^https?://([A-Za-z0-9.-]+)(:[0-9]+)?(/[^[:space:]]*)?$ ]]; then
  echo "::error::$SETTING is not a valid http(s) URL with a host: '$URL'" >&2
  exit 1
fi
HOST="${BASH_REMATCH[1]}"

echo "Resolved staging API host: $HOST"
echo "Resolved staging API base URL: $URL"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "api_url=$URL" >> "$GITHUB_OUTPUT"
fi

echo "Checking $URL/health …"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$URL/health")
CURL_EXIT=$?
if [[ "$CURL_EXIT" -eq 6 ]]; then
  echo "::error::Staging API host '$HOST' could not be resolved (curl exit 6). Check $SETTING." >&2
  exit 2
fi
if [[ "$CURL_EXIT" -ne 0 || "$HTTP" != "200" ]]; then
  echo "::error::Staging API returned HTTP $HTTP (curl exit $CURL_EXIT) from $URL/health. Aborting E2E run." >&2
  exit 3
fi

echo "Staging API is healthy (HTTP 200)."
exit 0
