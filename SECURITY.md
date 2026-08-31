# Security Policy

## Supported Versions

We actively maintain and issue security fixes for the versions listed below.

| Version | Supported |
|---------|-----------|
| latest `main` | ✅ Yes |
| older releases | ❌ No |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Ethos-Protocol handles vault financial data and passkey-based authentication. If you discover a
security vulnerability, we appreciate responsible disclosure and will work with you to resolve it
promptly.

### How to Report

1. **Email:** Send a detailed report to **security@ethos-protocol.app**
2. **Subject line:** `[SECURITY] <brief description>`
3. **Encryption:** PGP-encrypted reports are welcome (key available at
   `https://ethos-protocol.app/.well-known/security.txt`)

### What to Include

- A clear description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept (please limit to what is necessary to demonstrate the issue)
- Affected component(s): iOS app, Android app, API, backend, or infrastructure
- Any suggested mitigations, if known

### What to Expect

| Timeline | Action |
|----------|--------|
| **≤ 2 business days** | Acknowledgement of your report |
| **≤ 7 calendar days** | Initial triage and severity assessment |
| **≤ 30 calendar days** | Patch or mitigation available for critical/high findings |
| **≤ 90 calendar days** | Coordinated public disclosure (we will coordinate timing with you) |

We follow a **coordinated disclosure** model. We ask that you give us a reasonable window to
investigate and release a fix before any public disclosure. In return, we commit to keeping you
informed throughout the process and crediting your contribution (unless you prefer to remain
anonymous).

### Scope

**In scope:**
- Authentication and authorization (passkey/WebAuthn flows, JWT handling)
- API endpoints (`api.ethos-protocol.app`)
- Mobile clients (iOS and Android)
- Token storage (Keychain / EncryptedSharedPreferences)
- Replay-attack surface (nonce/timestamp handling)
- Data-in-transit (TLS, certificate pinning)
- Push notification handling

**Out of scope:**
- Third-party services (Firebase, Apple APNs) — report these to the respective vendor
- Denial-of-service without demonstrated security impact
- Social engineering
- Physical device attacks (e.g., extracting data from a lost/stolen unlocked device)
- Known issues already listed in public issue tracker

### Client-Side Diagnostic Logging

To help support triage "passkey sign-in doesn't work" reports, both mobile clients log a small
amount of diagnostic data when a passkey **registration** attempt fails:

- Authenticator attachment type (e.g. `platform`)
- WebAuthn attestation statement format (`fmt`, e.g. `packed`, `none`, `android-safetynet`)
- A short, non-sensitive failure reason (e.g. `registrationFailed`, `notInteractive`)

**Never logged:** public key material, signatures, challenge bytes, credential IDs, or any other
part of the raw attestation object/response. User-initiated cancellations are not logged. This log
is kept in memory on-device only (iOS: `PasskeyDiagnosticsLogger`, Android:
`PasskeyRegistrationDiagnostics`) and is not transmitted anywhere automatically.

### Safe Harbor

We will not pursue legal action against researchers who discover and report vulnerabilities in good
faith, provided they:

- Do not access, modify, or exfiltrate data beyond what is required to demonstrate the issue
- Do not disrupt production services
- Do not disclose the issue publicly before the coordinated disclosure window has elapsed

## Security Contacts

| Role | Contact |
|------|---------|
| Primary security contact | security@ethos-protocol.app |
| General inquiries | hello@ethos-protocol.app |

---

*This policy is inspired by [disclose.io](https://disclose.io/) best practices and
[RFC 9116](https://www.rfc-editor.org/rfc/rfc9116) (`security.txt`).*
