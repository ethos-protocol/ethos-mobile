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
| POST | `/auth/verify` | Verify passkey assertion, returns JWT |
| POST | `/auth/register` | Register new passkey credential |

### Vaults
| Method | Path | Description |
|--------|------|-------------|
| GET | `/vaults` | List owner's vaults |
| POST | `/vaults` | Create vault |
| GET | `/vaults/{id}` | Get vault detail |
| POST | `/vaults/{id}/checkin` | Check in (extend TTL) |
| POST | `/vaults/{id}/deposit` | Deposit funds |
| POST | `/vaults/{id}/withdraw` | Withdraw funds |
| POST | `/vaults/{id}/beneficiary` | Update vault beneficiary (owner-only) |
| GET | `/vaults/{id}/ttl` | Get TTL remaining |

### Notifications
| Method | Path | Description |
|--------|------|-------------|
| POST | `/notifications/register` | Register push token |
| DELETE | `/notifications/register` | Unregister push token |

## WebSocket
`wss://api.ethos-protocol.app/v1/ws?vault_id={id}` — real-time vault events

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
