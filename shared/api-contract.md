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
| POST | `/auth/recover/link` | Link a new passkey to an existing account, once identity is proven via email/backup code ("lost your device" recovery) |

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
