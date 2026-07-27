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

### PasskeyRegisterRequest
```json
{
  "credential_id": "string (base64url)",
  "attestation_object": "string (base64url-encoded CBOR attestation object from WebAuthn response)",
  "client_data_json": "string (base64url)"
}
```

### PasskeyVerifyRequest
```json
{
  "credential_id": "string (base64url)",
  "client_data_json": "string (base64url)",
  "signature": "string (base64url)"
}
```

## Beneficiary Management

### POST `/vaults/{id}/beneficiary`
Update the designated beneficiary for a vault. Owner-only; requires a valid Bearer JWT.

**Request body** (`BeneficiaryUpdateRequest`):
```json
{ "beneficiary": "string" }
```
`beneficiary` must be a non-empty Stellar public key (G… address) that differs from the
current beneficiary. The backend validates address format and rejects the request with
`400` if invalid.

**Response** (`200 OK`): the full `Vault` object with the updated `beneficiary` field.

**Errors**:
| Status | Meaning |
|--------|---------|
| 400 | Invalid or missing `beneficiary` field |
| 401 | Missing or expired JWT |
| 403 | Caller is not the vault owner |
| 404 | Vault not found |

### POST `/vaults/{id}/accept`
Called by the beneficiary to accept or confirm their designation. No request body required.

**Response** (`200 OK`): empty body (`{}`).

**Errors**:
| Status | Meaning |
|--------|---------|
| 401 | Missing or expired JWT |
| 403 | Caller is not the designated beneficiary |
| 404 | Vault not found |

## Passkey Registration Field Clarification

The `POST /auth/register` endpoint expects the **raw WebAuthn attestation object** (the
`attestationObject` field from the platform's credential registration response, base64url-encoded)
under the key `attestation_object`. This is **not** the public key itself — the backend
extracts the COSE-encoded public key from the attestation object during verification.

Both clients MUST send the field as `attestation_object` (snake_case). The legacy `public_key`
field name is not accepted.
