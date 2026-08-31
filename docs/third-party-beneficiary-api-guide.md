# Third-Party Beneficiary API Integration Guide

This guide is for external tooling that needs to read vault state or trigger a beneficiary-facing flow without being a first-party Ethos-Protocol app client. It is intentionally practical: it explains how to authenticate, what endpoints matter, which errors are normal, and how to satisfy the replay-protection headers that are required on mutating requests.

The authoritative contract remains [shared/api-contract.md](../shared/api-contract.md). This document translates that contract into a workflow-oriented checklist for third-party integrations.

## 1. Authentication and session setup

Every authenticated request should include:

```http
Authorization: Bearer <jwt>
```

A beneficiary tool usually gets a token by running the passkey login flow:

1. `POST /auth/challenge`
2. Use the challenge in the platform WebAuthn flow to sign with the user's passkey
3. `POST /auth/verify` to exchange the signed challenge for an auth token
4. Store the returned JWT and include it on subsequent requests

The same token is reused for vault reads and other privileged actions. If the app shows a `401` response, re-run the challenge flow or prompt the user to sign back in.

## 2. Read-only checks a beneficiary tool commonly needs

These requests are safe to retry because they are idempotent.

### Read a vault

```http
GET /vaults/{id}
```

Returns the current vault record, including state, owner/beneficiary information, and TTL details as defined by the shared contract.

### List vaults

```http
GET /vaults?limit=50
```

This endpoint supports cursor pagination. The server sends the next cursor in the `X-Next-Cursor` response header rather than wrapping the body in a pagination object. Save that value and send it back as the `cursor` query parameter on the next request.

### Check TTL

```http
GET /vaults/{id}/ttl
```

This is useful when building a “how much time remains?” UI or a reminder flow.

## 3. Standard error handling

Handle these responses explicitly:

- `401 Unauthorized`: the JWT is missing, expired, or invalid. Re-authenticate.
- `404 Not Found`: the vault ID is wrong or the resource no longer exists.
- `400` or `409` with `{"error": "replay_detected"}`: a replay or stale request was rejected. Generate a fresh `X-Nonce` and current `X-Timestamp` before retrying.
- `429` or other transient `5xx`: retry with backoff if the request is safe to retry; never replay the same nonce on a retry of a mutating request.

Avoid logging bearer tokens, raw JWTs, or any vault balances in debug output. The project policy is stricter than normal API logging: the mobile apps deliberately suppress this data in logs and diagnostic output.

## 4. Anti-replay headers on mutating requests

Mutating endpoints such as `POST /vaults/{id}/checkin`, `POST /vaults/{id}/withdraw`, `POST /vaults/{id}/accept`, and `DELETE /notifications/register` require anti-replay headers.

### Required headers

- `X-Nonce`: 32 random bytes, hex-encoded to 64 characters
- `X-Timestamp`: Unix time in seconds

### Rules

- Generate a fresh nonce for every mutating request.
- Never reuse a prior nonce even if the timestamp is close.
- Keep the request timestamp within 5 minutes of the server clock.
- Do not send these headers on safe `GET` requests.

Example:

```http
POST /vaults/{id}/checkin HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json
X-Nonce: a3f1c2d4e5b6a7f8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
X-Timestamp: 1753548558
```

### Implementation note

The shared contract explicitly requires a cryptographically secure nonce source. The iOS client uses a `CryptoKit.SymmetricKey(size: .bits256)`-derived nonce and the Android client uses `SecureRandom`; `Math.random()` or a UUID is not acceptable.

## 5. Beneficiary acceptance flow

The beneficiary acceptance endpoint is a protected one-time action. It requires a token embedded in the deep-link URL:

```text
https://ethos-protocol.app/vaults/{id}/accept?token={token}
```

The request body contains both the vault ID and token:

```json
{
  "vault_id": "string",
  "token": "string"
}
```

Use the token only once, and treat a `401` on this endpoint as a sign that the link is expired, invalid, or already used.

## 6. Recommended client behavior

For a robust third-party integration:

- Store the JWT in a secure store, not in plain text logs or app state snapshots
- Treat `401` and `replay_detected` as actionable state transitions, not generic failures
- Retry only safe reads with backoff
- Make nonce generation part of the request builder so it cannot be accidentally reused
- Read the contract in [shared/api-contract.md](../shared/api-contract.md) before changing client behavior that touches pagination or anti-replay validation

## 7. Quick reference

| Concern | Requirement |
|---|---|
| Auth | `Authorization: Bearer <jwt>` |
| Mutating requests | `X-Nonce` + `X-Timestamp` |
| Nonce source | Cryptographically secure random bytes |
| Pagination | `X-Next-Cursor` header + `cursor` query parameter |
| Logging | Never log bearer tokens or vault amounts |

This guide is intentionally narrowed to the integration patterns most likely to trip up an external beneficiary client. For exact field names, validation rules, and edge cases, consult the shared API specification directly.
