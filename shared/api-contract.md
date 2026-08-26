# Ethos-Protocol Mobile API Contract

Base URL: `https://api.ethos-protocol.app/v1` (configurable via env)

## Authentication
All authenticated endpoints require `Authorization: Bearer <jwt>` header.
JWT is obtained via Passkey (WebAuthn) challenge/response flow.

## Anti-Replay Protection (task #121)

Every **mutating** request (POST / DELETE — check-in, deposit, withdraw, 2FA operations, push
registration) must include two additional headers to prevent replay attacks. A captured request
replayed by a network attacker must be rejected by the server.

### Headers

| Header | Format | Description |
|--------|--------|-------------|
| `X-Nonce` | 32-byte random value, hex-encoded (64 chars) | Per-request unique value. The server rejects any request whose nonce has been seen before (within the token's validity window). |
| `X-Timestamp` | Unix epoch seconds as a decimal string | UTC time the request was created. The server rejects requests where `|server_time − request_time| > 300` seconds (5-minute window). |

### Rules
- Both headers are **required** on all mutating endpoints (POST and DELETE).
- GET requests **do not** need these headers (they are read-only and idempotent).
- The nonce must be generated fresh for **every** request — reusing a nonce from a previous
  request will cause the server to reject it even if the timestamp is within window.
- Clients use `CryptoKit.SymmetricKey(size: .bits256)` (iOS) / `SecureRandom` (Android) to
  generate the nonce bytes — `Math.random()` or `UUID` is not acceptable.

### Example mutating request headers
```
POST /vaults/{id}/checkin HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json
X-Nonce: a3f1c2d4e5b6a7f8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
X-Timestamp: 1753548558
```

### Server-side validation (backend contract)
The server must:
1. Parse `X-Timestamp` and reject if `|server_utc − timestamp| > 300`.
2. Check the nonce has not been seen before (e.g., store in Redis with TTL = token validity + 300 s).
3. Reject (HTTP 400 or 409) if either check fails, with a JSON body `{"error": "replay_detected"}`.

## Endpoints

### Auth
| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/challenge` | Get WebAuthn challenge |
| POST | `/auth/verify` | Verify passkey assertion, returns `AuthToken` |
| POST | `/auth/register` | Register new passkey credential, returns `AuthToken` directly (#2) — no separate `/auth/verify` call is needed right after registering |
| POST | `/auth/refresh` | Proactively refresh the current session before it expires, returns a new `AuthToken` (#3) |
| POST | `/auth/recover/link` | Link a new passkey to an existing account, once identity is proven via email/backup code ("lost your device" recovery) |

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

#### List Pagination (`GET /vaults`)

Cursor-based. The response body stays a bare JSON array of `Vault` (unchanged,
backward-compatible with clients that don't send pagination params) — the cursor
for the next page is returned via a response header instead of wrapping the body.

Request query parameters (both optional):

| Param | Description |
|-------|-------------|
| `cursor` | Opaque cursor returned by the previous page's `X-Next-Cursor` response header. Omit to request the first page. |
| `limit` | Max vaults to return in this page. Server may cap this. Client default: 50. |

Response headers:

| Header | Description |
|--------|-------------|
| `X-Next-Cursor` | Opaque cursor for the next page. Absent (or empty) when this is the last page. |

Cursors are opaque — clients must not parse or construct them, only round-trip
the value returned by `X-Next-Cursor` back as the `cursor` query param.

### Notifications
| Method | Path | Description |
|--------|------|-------------|
| POST | `/notifications/register` | Register push token |
| DELETE | `/notifications/register` | Unregister push token |

## WebSocket
`wss://api.ethos-protocol.app/v1/ws?vault_id={id}` — real-time vault events for
a single vault. Requires the same `Authorization: Bearer <jwt>` header as REST
requests, sent on the handshake request.

Server → client messages are JSON text frames:

```json
{ "type": "vault_updated", "vault": { /* full Vault object, see below */ } }
```

`type` is open-ended (clients should ignore unrecognized values instead of
erroring, to allow adding new event types without breaking older clients).

Clients are expected to reconnect with backoff on an unexpected drop, and to
fall back to their existing polling mechanism (periodic `GET /vaults/{id}`) if
the socket can't be established after a few attempts — this stream is a
latency optimization, not the source of truth.

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
{
  "challenge": "base64url",
  "expires_at": "ISO8601",
  "existing_credential_ids": ["base64url"]
}
```
`existing_credential_ids` lists the credential IDs already registered to the account this
challenge is for (empty for a brand-new account). Clients pass these back as
`excludeCredentials`/`excludedCredentials` on the registration request so the platform
authenticator refuses to create a second passkey for an account that already has one on
that device.

### AuthToken
```json
{ "token": "string", "expires_at": "ISO8601" }
```
Returned by `/auth/verify`, `/auth/register` (#2), and `/auth/refresh` (#3). Clients schedule a
proactive refresh against `expires_at`, rather than waiting to be rejected with a 401.

### PasskeyRegisterRequest (#1)
```json
{ "credential_id": "base64url", "public_key": "base64url", "client_data_json": "base64url" }
```
`public_key` is the WebAuthn COSE_Key (RFC 9052) extracted from the attestation object's
`authData`, base64url-encoded — **not** the raw CBOR attestation object. Both iOS
(`PasskeyService.extractCOSEPublicKey`) and Android (`PasskeyService.kt`'s
`extractCosePublicKey`/`cosePublicKeyBytes`) extract and send byte-identical COSE_Key data.

### AuthRefreshRequest (#3)
No request body. Requires the current (possibly near-expiry, not-yet-expired) `Authorization:
Bearer <jwt>` header. Response: `AuthToken`. `401` if the current token is no longer valid — the
client falls back to its normal delete-and-reauth behavior in that case.

### RecoverAccessLinkRequest
```json
{
  "email": "string",
  "backup_code": "string",
  "credential_id": "base64url",
  "public_key": "base64url",
  "client_data_json": "base64url"
}
```
`email`/`backup_code` prove ownership of the existing account; the server verifies them
before attaching the new passkey (`credential_id`/`public_key`/`client_data_json`, from a
normal WebAuthn registration ceremony against a `/auth/challenge` obtained for this
account) rather than issuing a session directly. Clients call `POST /auth/verify`
afterwards to authenticate with the newly linked passkey.

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

---

## #227 — Server-Side 2FA Method Availability

The `GET /vaults/{id}/2fa/status` response now includes an `available_methods` array that
lists the 2FA methods the server accepts for this account. Clients must filter the method-
selection UI to only what the server reports — if `sms` is absent the SMS option is hidden.

### Updated TwoFactorStatus model
```json
{
  "vault_id": "string",
  "enabled": true,
  "method": "totp|sms|email|null",
  "verified": true,
  "phone": "string|null",
  "email": "string|null",
  "available_methods": ["totp", "sms", "email"]
}
```

`available_methods` defaults to all three methods when absent (older server) so existing
clients are backward-compatible. Clients must decode the field defensively.

---

## #226 — Trusted-Device Contract ("Remember This Device")

### Duration and revocation
- Trust window: **30 days** from the time of opt-in. The server records an expiry timestamp.
- The trust token is opaque and stored securely on the device (Keychain / EncryptedSharedPreferences).
- Trust can be revoked server-side at any time (e.g. from account settings, linked to #207 remote
  session revocation). A revoked token returns `401` with body `{"error": "device_trust_revoked"}`.
- The device trust token is **per-vault** — trusting device for vault A does not skip 2FA for vault B.

### New endpoint
| Method | Path | Description |
|--------|------|-------------|
| POST | `/vaults/{id}/2fa/trust-device` | Opt in to trusted-device for 30 days |

**Request body** (sent immediately after a successful `POST /vaults/{id}/2fa/verify`):
```json
{ "trust_device": true }
```

**Response:**
```json
{
  "device_trust_token": "string",
  "expires_at": "ISO8601"
}
```

### TrustDeviceRequest / TrustDeviceResponse
```json
// request
{ "trust_device": true }

// response
{
  "device_trust_token": "string",
  "expires_at": "ISO8601"
}
```

### Updated Verify2FARequest (opt-in checkbox)
The existing `POST /vaults/{id}/2fa/verify` body gains an optional field:
```json
{
  "otp": "string",
  "trust_device": false
}
```
When `trust_device` is `true` the server issues a `device_trust_token` in the response instead of
`null`, which the client stores and sends as `X-Device-Trust-Token` on subsequent requests.

### Updated Verify2FAResponse
```json
{
  "device_trust_token": "string|null",
  "expires_at": "ISO8601|null"
}
```

### Skipping 2FA with a trusted-device token
Send `X-Device-Trust-Token: <token>` on any request that would otherwise require fresh 2FA.
The server validates the token and skips the 2FA challenge if it is still valid and unrevoked.

---

## #224 — TOTP Backup/Recovery Codes

### New endpoints
| Method | Path | Description |
|--------|------|-------------|
| POST | `/vaults/{id}/2fa/backup-codes/generate` | Generate a fresh set of backup codes (invalidates any existing set) |
| GET  | `/vaults/{id}/2fa/backup-codes/status`   | Check whether backup codes have been generated (count only, never values) |

### BackupCodesResponse
```json
{
  "codes": ["AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH",
            "IIII-JJJJ", "KKKK-LLLL", "MMMM-NNNN", "OOOO-PPPP"],
  "generated_at": "ISO8601"
}
```

- Codes are shown **once** at generation time. The server stores only hashes; subsequent
  `GET /status` calls return a count but never the plaintext codes.
- Each code is **single-use** — using a code marks it consumed server-side (HTTP 409 if reused).
- `POST .../generate` invalidates any previously issued codes and issues a new set of 8.
- Backup codes are independent of the TOTP secret — regenerating codes does not change the
  secret, and rotating the secret does not invalidate existing codes.

### BackupCodesStatus
```json
{ "generated": true, "remaining_count": 6 }
```

---

## #225 — Switch 2FA Method Without Disabling First

### New endpoint
| Method | Path | Description |
|--------|------|-------------|
| POST | `/vaults/{id}/2fa/switch` | Atomically replace the active 2FA method with a new one |

The server sets up the new method in a **pending** state, issues the provisioning URI / sends the
OTP, and only tears down the old method once the client verifies the new one. The account is never
in a zero-2FA state during the switch.

### SwitchMethod request
```json
{
  "new_method": "totp|sms|email",
  "phone": "string|null",
  "email": "string|null"
}
```

### SwitchMethod response (same shape as Enable2FAResponse)
```json
{
  "vault_id": "string",
  "method": "totp|sms|email",
  "secret": "string|null",
  "provisioning_uri": "string|null"
}
```

The client then calls the existing `POST /vaults/{id}/2fa/verify` with the OTP for the **new**
method. On successful verification the server atomically:
1. Marks the new method as verified and active.
2. Disables the old method.

If verification is never completed the old method remains active (no gap in protection).
