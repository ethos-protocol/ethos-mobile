# Ethos-Protocol Mobile API Contract

Base URL: `https://api.ethos-protocol.app/v1` (configurable via env)

## Authentication
All authenticated endpoints require `Authorization: Bearer <jwt>` header.
JWT is obtained via Passkey (WebAuthn) challenge/response flow.

## Endpoints

### Auth
| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/challenge` | Get WebAuthn challenge |
| POST | `/auth/verify` | Verify passkey assertion, returns JWT |
| POST | `/auth/register` | Register new passkey credential |

### Vaults
| Method | Path | Description |
|--------|------|-------------|
| GET | `/vaults` | List owner's vaults (supports pagination — see §Pagination) |
| POST | `/vaults` | Create vault |
| GET | `/vaults/{id}` | Get vault detail |
| POST | `/vaults/{id}/checkin` | Check in (extend TTL) |
| POST | `/vaults/{id}/deposit` | Deposit funds |
| POST | `/vaults/{id}/withdraw` | Withdraw funds |
| POST | `/vaults/{id}/beneficiary` | Update vault beneficiary (owner-only) |
| GET | `/vaults/{id}/ttl` | Get TTL remaining |
| POST | `/vaults/{id}/accept` | Beneficiary accepts vault (token required — see §Beneficiary Acceptance) |

### Notifications
| Method | Path | Description |
|--------|------|-------------|
| POST | `/notifications/register` | Register push token |
| DELETE | `/notifications/register` | Unregister push token |

## WebSocket
`wss://api.ethos-protocol.app/v1/ws?vault_id={id}` — real-time vault events

Authentication: pass the JWT as a query parameter or `Authorization` header on the initial
handshake request. The server closes the connection with code 4401 if authentication fails.

### WebSocket Message Schema (#110)

All messages are JSON objects with a `type` discriminator field.

#### Server → Client messages

**`vault_updated`** — emitted whenever server-side vault state changes (TTL refresh, check-in,
deposit, withdrawal, beneficiary change, status transition).
```json
{
  "type": "vault_updated",
  "vault_id": "string",
  "vault": { /* full Vault object — see §Models */ }
}
```

**`vault_expired`** — emitted when a vault transitions to `expired` status.
```json
{
  "type": "vault_expired",
  "vault_id": "string",
  "expired_at": "ISO8601"
}
```

**`vault_released`** — emitted when funds are released to the beneficiary.
```json
{
  "type": "vault_released",
  "vault_id": "string",
  "released_at": "ISO8601",
  "amount": 0
}
```

**`ping`** — server keepalive, no action required from clients (clients may reply with `pong`).
```json
{ "type": "ping" }
```

**`error`** — server signals a recoverable error (e.g. invalid vault_id on connect).
```json
{
  "type": "error",
  "code": "string",
  "message": "string"
}
```

#### Client → Server messages

**`pong`** — reply to server `ping` (optional but recommended to maintain connection).
```json
{ "type": "pong" }
```

#### Connection lifecycle
- Reconnect with exponential backoff (base 1 s, max 60 s) on any non-4401 close.
- On `vault_updated`, merge the embedded `vault` object into the local vault list in-place
  (do not full-reload from REST).
- On `vault_expired` / `vault_released`, trigger a local notification if the app is backgrounded.

---

## Beneficiary Acceptance (#109)

**Decision: token is required.**

`POST /vaults/{id}/accept` requires a `token` in the request body. The token is embedded in the
acceptance deep-link URL as a query parameter:
`https://ethos-protocol.app/vaults/{id}/accept?token={token}`

**Rationale:** the token is a server-issued one-time secret that proves the request originated
from the specific acceptance link the owner shared, not from an arbitrary authenticated session.
Without it, any authenticated user could call the endpoint for any vault they know the ID of.

**Request body:**
```json
{
  "vault_id": "string",
  "token": "string"
}
```
Response: `204 No Content` on success; `401` if token is invalid/expired; `404` if vault not found.

**Platform alignment:**
- iOS: already implemented in `APIClient.acceptBeneficiary(vaultID:token:)` — no change needed.
- Android: updated in this change (#109) — `ApiClient.acceptBeneficiary(vaultId, token)` now
  forwards the token. The token is extracted from the `/vaults/{id}/accept` HTTPS deep-link URL
  in `MainActivity.extractBeneficiaryAccept()` and threaded through navigation to
  `AcceptanceViewModel.accept(vaultId, token)`.

---

## Logging Redaction Policy (#111)

The following data **must never appear in logs, logcat, os_log, or any other diagnostic
output, in any build configuration (debug or release) on either platform:**

1. `Authorization: Bearer <jwt>` headers or raw JWT strings
2. 2FA secrets, OTPs, provisioning URIs, or TOTP seeds
3. Vault balances, deposit/withdrawal amounts
4. Beneficiary wallet addresses or owner wallet addresses
5. Acceptance tokens (the one-time token from `/accept?token=…`)
6. Full request/response bodies for any authenticated endpoint

**Permitted to log (debug builds only, never release):**
- HTTP method, path, and status code (no query strings that carry tokens)
- Non-sensitive model identifiers (vault ID, status enum value)
- Timing / latency metrics

**Platform enforcement:**

*Android (`ApiClient.kt`):*
Ktor's `Logging` plugin is configured with
`level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE`.
`LogLevel.INFO` only logs method + URL + status, not request/response bodies — bearer tokens,
2FA secrets, and vault balances are therefore never written to logcat in any build.
See `ApiClient.kt` lines 39–43 for the enforcing configuration.

*iOS (`APIClient.swift`):*
`URLSession` is used directly with no logging plugin. No request/response body logging
is present anywhere in `APIClient.swift`. Any future addition of a logging interceptor
**must** apply the same `DEBUG`-only guard and `INFO`-level-only (method+URL+status)
restriction described above for Android. See `APIClient.swift` — MARK: Logging Redaction Audit.

---

## Pagination (#112)

**Contract: cursor-based pagination for `GET /vaults`.**

Cursor pagination is chosen over offset/limit because vault lists can change between requests
(vaults can expire, be created, or change status), making offset-based pages unstable.

### Request
```
GET /vaults?limit={n}&after={cursor}
```
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `limit` | integer | no | 20 | Max vaults per page (1–100) |
| `after` | string | no | — | Opaque cursor from previous response's `next_cursor` |

### Response
```json
{
  "vaults": [ /* array of Vault objects */ ],
  "next_cursor": "string | null",
  "has_more": true
}
```
- `next_cursor` is `null` (and `has_more` is `false`) when the caller has received all vaults.
- Cursors are opaque server-issued strings; clients must not construct or modify them.
- A request without `after` always returns the first page.

### Platform implementation
- Both clients expose a `listVaults(limit:after:)` method that returns a `VaultPage` result
  containing the vault array plus the cursor for the next call.
- Callers accumulate pages by calling `listVaults(after: page.nextCursor)` until
  `page.hasMore == false`.
- The existing `listVaults()` (no-arg) overload fetches the first page with default limit and
  returns only the vault array for backward compatibility with callers that don't need pagination.

---

## Models

### Vault
```json
{
  "id": "string",
  "owner": "string",
  "beneficiary": "string",
  "balance": 0,
  "check_in_interval": 0,
  "last_check_in": "ISO8601",
  "ttl_remaining": 0,
  "status": "active|expired|released|paused"
}
```

### VaultPage (#112 — paginated list response)
```json
{
  "vaults": [ /* Vault objects */ ],
  "next_cursor": "string | null",
  "has_more": false
}
```

### AuthChallenge
```json
{ "challenge": "base64url", "expires_at": "ISO8601" }
```

### AuthToken
```json
{ "token": "string", "expires_at": "ISO8601" }
```

### BeneficiaryUpdateRequest
```json
{ "beneficiary": "string" }
```
Response: the updated `Vault` object (see above), reflecting the new `beneficiary` value.

### BeneficiaryAcceptRequest (#109)
```json
{ "vault_id": "string", "token": "string" }
```
Response: `204 No Content`.

### WebSocketMessage (#110)
See §WebSocket Message Schema above for the full discriminated-union schema.
