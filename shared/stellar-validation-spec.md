# Stellar Beneficiary-Address Validation Spec

**Tracking**: #113 (unifies iOS #22 and Android #71)  
**Status**: Canonical — both platforms implement against this document.

---

## Overview

This application accepts two Stellar beneficiary address formats:
1. **Public keys** (account IDs, ed25519 "G..." addresses)
2. **Muxed accounts** (SEP-0023 "M..." addresses, which route to a base account with a 64-bit memo ID)

Both the iOS and Android clients must validate the address **before** sending it to
the server so that a malformed address is caught locally with a clear error message
rather than resulting in a confusing server error or a vault that can never be claimed.

This document defines the canonical validation rules. Both platforms' validator
implementations and their test fixtures derive from the same rules written here.

---

## Format — StrKey addresses (ed25519 public keys and muxed accounts)

Stellar encodes addresses as _StrKey_, which is a modified base32 format defined in
[SEP-0023](https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0023.md)
and the [Stellar Protocol docs](https://developers.stellar.org/docs/learn/encyclopedia/stellar-data-structures/accounts#account-id).

### Public Key Format (G-address)

A traditional Stellar account ID, encoded with version byte 0x30 (ed25519 public key).

**Encoding:**
```
Raw bytes (35 total):
  byte[0]      : version byte = 0x30  (decimal 48, = 6 << 3, signals "ed25519 public key")
  byte[1..32]  : 32-byte ed25519 public key payload
  byte[33..34] : 2-byte CRC-16/XModem checksum of byte[0..32], little-endian

Base32-encode the 35 raw bytes (RFC 4648, no padding) → 56 uppercase characters
```

**Observable properties:**
| Property | Value |
|----------|-------|
| Length | Exactly **56** characters |
| Character set | Uppercase letters A–Z and digits 2–7 (RFC 4648 base32, no padding, no lowercase) |
| First character | Always **`G`** (encodes version byte 0x30 as the first base32 character) |
| Checksum | CRC-16/XModem of `byte[0..32]`, stored little-endian in `byte[33..34]` |

### Muxed Account Format (M-address)

A Stellar account with an embedded 64-bit memo ID, per [SEP-0023](https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0023.md).
Used by exchanges and custodial wallets to route funds within a shared account.

**Encoding:**
```
Raw bytes (43 total):
  byte[0]      : version byte = 0x60  (decimal 96, = 12 << 3, signals "muxed ed25519 public key")
  byte[1..32]  : 32-byte ed25519 public key payload (base account)
  byte[33..40] : 8-byte memo ID, big-endian (most significant byte first)
  byte[41..42] : 2-byte CRC-16/XModem checksum of byte[0..40], little-endian

Base32-encode the 43 raw bytes (RFC 4648, no padding) → 69 uppercase characters
```

**Observable properties:**
| Property | Value |
|----------|-------|
| Length | Exactly **69** characters |
| Character set | Uppercase letters A–Z and digits 2–7 (RFC 4648 base32, no padding, no lowercase) |
| First character | Always **`M`** (encodes version byte 0x60 as the first base32 character) |
| Checksum | CRC-16/XModem of `byte[0..40]`, stored little-endian in `byte[41..42]` |

---

## Validation algorithm

Implementations MUST follow these steps in order to validate **either** a public key (G-address)
**or** a muxed account (M-address):

### Determine address type (step 0)

Check the first character and length to determine which validation path to follow:
- If `input[0] == 'G'` and `len(input) == 56`: Validate as a **public key** (steps 1–6 below)
- If `input[0] == 'M'` and `len(input) == 69`: Validate as a **muxed account** (steps 1–6 below)
- Otherwise: Reject the input

### Public key validation (G-address, 56 characters)

Implementations MUST follow these steps in order:

1. **Length check** — Reject the string if `len(input) ≠ 56`.
2. **Prefix check** — Reject the string if `input[0] ≠ 'G'`.
3. **Character-set check** — Reject the string if any character is not in `[A-Z2-7]`  
   (i.e., `0`, `1`, `8`, `9`, lowercase letters, and any other character are invalid).
4. **Base32 decode** — Decode the 56-character string into 35 bytes using the RFC 4648
   alphabet (`A=0 … Z=25, 2=26 … 7=31`). This step must not accept padding characters.
5. **Version byte check** — Reject if `decoded[0] ≠ 0x30`.
6. **Checksum verification** — Compute CRC-16/XModem of `decoded[0..32]` (33 bytes).
   Reject if the result does not equal `decoded[33] | (decoded[34] << 8)` (little-endian).

### Muxed account validation (M-address, 69 characters)

Implementations MUST follow these steps in order:

1. **Length check** — Reject the string if `len(input) ≠ 69`.
2. **Prefix check** — Reject the string if `input[0] ≠ 'M'`.
3. **Character-set check** — Reject the string if any character is not in `[A-Z2-7]`.
4. **Base32 decode** — Decode the 69-character string into 43 bytes using the RFC 4648 alphabet.
   This step must not accept padding characters.
5. **Version byte check** — Reject if `decoded[0] ≠ 0x60` (version byte for muxed ed25519 key).
6. **Checksum verification** — Compute CRC-16/XModem of `decoded[0..40]` (41 bytes).
   Reject if the result does not equal `decoded[41] | (decoded[42] << 8)` (little-endian).

A string passes validation if and only if it survives all six checks for its detected type.

### CRC-16/XModem algorithm

```
polynomial : 0x1021
initial value : 0x0000
input/output reflection : none
XOR out : 0x0000

pseudocode:
  crc = 0
  for each byte b in input:
    crc ^= (b << 8)
    for _ in 0..8:
      if crc & 0x8000 != 0:
        crc = (crc << 1) ^ 0x1021
      else:
        crc = crc << 1
      crc &= 0xFFFF
  return crc
```

---

## Input sanitization

**Important:** When accepting Stellar addresses from user input (especially clipboard paste),
trim leading/trailing whitespace and strip common invisible/zero-width characters **before**
running the validation algorithm. This happens at the UI input layer, not inside the validator
itself, to preserve the validator's strict contract.

### Characters to remove before validation

- Leading/trailing whitespace (space, tab, newline, carriage return)
- Common invisible characters (zero-width space U+200B, zero-width joiner U+200D, zero-width non-joiner U+200C)
- Right-to-left and left-to-right direction marks (U+200E, U+200F)

Example: If a user pastes `" GA7Q...UJVSGZ "` (with spaces) or `"GA7Q​...UJVSGZ"` (with zero-width space),
sanitize to `"GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ"` before passing to the validator.

### Validator contract

The `isValidPublicKey` / `isValidAddress` function validates only syntactically correct,
unsanitized input. The caller (UI layer) is responsible for all trimming and sanitization
before passing input to the validator.

---

## Additional backend constraints

The backend enforces no additional constraints on the address beyond the StrKey
format above. The address is stored as-is and forwarded to the Stellar network.
Do **not** add extra length caps or network-specific rules beyond what is listed
here unless this document is updated first.

---

## Optional Memo Field

Many Stellar-facing services (exchanges, custodial wallets) require a memo alongside
the account ID to correctly route funds. This application supports an **optional memo**
in addition to the beneficiary address.

### Memo Types

Stellar supports four memo types. Beneficiaries may specify one:

| Type | Range/Format | Stellar Constant | Notes |
|------|--------------|------------------|-------|
| **None** | (empty) | — | No memo (default) |
| **Text** | 0–28 bytes UTF-8 | `MEMO_TYPE_TEXT` | Human-readable text |
| **ID** | 0–18,446,744,073,709,551,615 (uint64) | `MEMO_TYPE_ID` | Numeric memo ID |
| **Hash** | Exactly 32 bytes (hex-encoded) | `MEMO_TYPE_HASH` | SHA-256 hash |

### Memo Validation

When a memo is provided:
- **Text memo**: Must be valid UTF-8, maximum 28 bytes when encoded as UTF-8
- **ID memo**: Decimal number, must be non-negative 64-bit unsigned integer
- **Hash memo**: Exactly 64 hexadecimal characters (0-9, a-f, A-F), representing 32 bytes

Memos are **optional**. If omitted, the beneficiary address alone is used for fund routing.

---

## Shared test fixtures

Both platforms' test suites MUST use the addresses below. This ensures the same
inputs produce the same result on iOS and Android.

### Valid addresses

#### Public keys (G-addresses)

| Address | Notes |
|---------|-------|
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF` | All-zero payload, valid CRC |
| `GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZX` | Non-trivial payload, valid CRC |
| `GD6WNKTD7WDTPTGTOVFLBKLPIHMYZPBKBWUQHVL3OQQZZIJDX4GKCY5` | Another valid key |

#### Muxed accounts (M-addresses)

| Address | Memo ID | Base Account | Notes |
|---------|---------|--------------|-------|
| `MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJUAAAAAAAAAAAACJUQ` | 0 | GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ | Memo ID = 0, valid CRC |
| `MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVAAAAAAAAAAAAAJLK` | 9223372036854775808 | GA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJVSGZ | Large memo ID (2^63), valid CRC |

### Invalid addresses — must all be rejected

| Address / input | Reason for rejection | Type |
|-----------------|---------------------|------|
| `GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZA` | Valid format but **wrong checksum** (last char changed) | G-address |
| `MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Too short for M-address (56 chars instead of 69) | M-address |
| `MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJUAAAAAAAAAAAACJUR` | Valid format but **wrong checksum** (last char changed) | M-address |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAW` | Too short (55 chars) | G-address |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Too long (57 chars) | G-address |
| `MA7QYNF7SOWQ3GLR2BGMZEHXAVIRZA4KVWLTJJFC7MGXUA74P7UJUAAAAAAAAAAAACJUQA` | Too long (70 chars) | M-address |
| `gaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaawhf` | Lowercase — not in base32 alphabet | G-address |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAA0AAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Contains `0` (not in base32 alphabet `[A-Z2-7]`) | G-address |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAA1AAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Contains `1` (not in base32 alphabet) | G-address |
| `` (empty string) | Length check fails | Either |
| `not-a-stellar-address` | Length check fails | Either |

---

## Platform implementation notes

Validators MUST accept **both** G-addresses (56 chars) and M-addresses (69 chars).
The validator function determines the address type by checking the first character and length,
then applies the appropriate validation algorithm.

### Shared test fixtures

All test fixtures are defined in a single source: `shared/stellar-address-fixtures.json`.
Both platform test files MUST use these canonical fixtures to prevent silent drift.

A CI check (`scripts/validate_stellar_fixtures.py`) runs on every PR to ensure:
- Both platforms' test files include all fixtures from the canonical list
- Neither platform has diverged with extra fixtures
- Fixture additions/changes happen in the JSON first, then in test files

### iOS (`StellarAddress.swift`)

- Location: `ios/EthosProtocol/Sources/Models/StellarAddress.swift`
- Provides `StellarAddress.isValidAddress(_ value: String) -> Bool`  
  (accepts both G and M addresses)
- Used in `CreateVaultView.isBeneficiaryValid` and `ManageBeneficiaryView.isAddressValid`
- Tests: `StellarAddressTests` in `Tests/EthosProtocolTests.swift`
- Fixtures used: All addresses from `shared/stellar-address-fixtures.json`

### Android (`StellarAddress.kt`)

- Location: `android/app/src/main/java/com/ethosprotocol/models/StellarAddress.kt`
- Provides `StellarAddress.isValidAddress(value: String): Boolean`  
  (accepts both G and M addresses)
- Used in `CreateVaultDialog.isBeneficiaryValid` inside `Screens.kt`
- Tests: `StellarAddressTest` in `android/app/src/test/java/com/ethosprotocol/StellarAddressTest.kt`
- Fixtures used: All addresses from `shared/stellar-address-fixtures.json`

Both implementations are dependency-free (no external Stellar SDK) and implement
the same algorithm so the validation result is identical for any given input.

### Adding a new test fixture

1. Edit `shared/stellar-address-fixtures.json` to add the new address
2. Add corresponding test cases to both `StellarAddressTest.kt` and `StellarAddressTests` (tests should match the canonical fixture list)
3. Run `python .github/scripts/validate_stellar_fixtures.py` to verify no divergence
4. Commit both the fixture JSON and updated test files together
