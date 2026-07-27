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
| GET | `/vaults` | List owner's vaults (paginated — see below) |
| POST | `/vaults` | Create vault |
| GET | `/vaults/{id}` | Get vault detail |
| POST | `/vaults/{id}/checkin` | Check in (extend TTL) |
| POST | `/vaults/{id}/deposit` | Deposit funds |
| POST | `/vaults/{id}/withdraw` | Withdraw funds |
| POST | `/vaults/{id}/beneficiary` | Update vault beneficiary (owner-only) |
| GET | `/vaults/{id}/ttl` | Get TTL remaining |

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
