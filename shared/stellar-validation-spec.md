# Stellar Beneficiary-Address Validation Spec

**Tracking**: #113 (unifies iOS #22 and Android #71)  
**Status**: Canonical — both platforms implement against this document.

---

## Overview

A Stellar _public key_ (account ID) is the only beneficiary address format this
application accepts. Both the iOS and Android clients must validate the address
**before** sending it to the server so that a malformed address is caught locally
with a clear error message rather than resulting in a confusing server error or a
vault that can never be claimed.

This document defines the canonical validation rules. Both platforms' validator
implementations and their test fixtures derive from the same rules written here.

---

## Format — StrKey ed25519 public key

Stellar encodes public keys as _StrKey_, which is a modified base32 format defined
in [SEP-0023](https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0023.md)
and the [Stellar Protocol docs](https://developers.stellar.org/docs/learn/encyclopedia/stellar-data-structures/accounts#account-id).

### Encoding

```
Raw bytes (35 total):
  byte[0]      : version byte = 0x30  (decimal 48, = 6 << 3, signals "ed25519 public key")
  byte[1..32]  : 32-byte ed25519 public key payload
  byte[33..34] : 2-byte CRC-16/XModem checksum of byte[0..32], little-endian

Base32-encode the 35 raw bytes (RFC 4648, no padding) → 56 uppercase characters
```

### Observable properties of a valid address

| Property | Value |
|----------|-------|
| Length | Exactly **56** characters |
| Character set | Uppercase letters A–Z and digits 2–7 (RFC 4648 base32, no padding, no lowercase) |
| First character | Always **`G`** (encodes version byte 0x30 as the first base32 character) |
| Checksum | CRC-16/XModem of `byte[0..32]`, stored little-endian in `byte[33..34]` |

---

## Validation algorithm

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

A string passes validation if and only if it survives all six checks.

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

## Additional backend constraints

The backend enforces no additional constraints on the address beyond the StrKey
format above. The address is stored as-is and forwarded to the Stellar network.
Do **not** add extra length caps or network-specific rules beyond what is listed
here unless this document is updated first.

---

## Shared test fixtures

Both platforms' test suites MUST use the addresses below. This ensures the same
inputs produce the same result on iOS and Android.

### Valid addresses

| Address | Notes |
|---------|-------|
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF` | All-zero payload, valid CRC |
| `GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZX` | Non-trivial payload, valid CRC |
| `GD6WNKTD7WDTPTGTOVFLBKLPIHMYZPBKBWUQHVL3OQQZZIJDX4GKCY5` | Another valid key |

### Invalid addresses — must all be rejected

| Address / input | Reason for rejection |
|-----------------|---------------------|
| `GAAACAQDAQCQMBYIBEFAWDANBYHRAEISCMKBKFQXDAMRUGY4DUPB7JZA` | Valid format but **wrong checksum** (last char changed) |
| `MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Wrong prefix (`M`, not `G`) |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAW` | Too short (55 chars) |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Too long (57 chars) |
| `gaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaawhf` | Lowercase — not in base32 alphabet |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAA0AAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Contains `0` (not in base32 alphabet `[A-Z2-7]`) |
| `GAAAAAAAAAAAAAAAAAAAAAAAAAAA1AAAAAAAAAAAAAAAAAAAAAAAAAWHF` | Contains `1` (not in base32 alphabet) |
| `` (empty string) | Length check fails |
| `not-a-stellar-address` | Length check fails |

---

## Platform implementation notes

### iOS (`StellarAddress.swift`)

- Location: `ios/EthosProtocol/Sources/Models/StellarAddress.swift`
- Provides `StellarAddress.isValidPublicKey(_ value: String) -> Bool`
- Used in `CreateVaultView.isBeneficiaryValid` and `ManageBeneficiaryView.isAddressValid`
- Tests: `StellarAddressTests` in `Tests/EthosProtocolTests.swift`

### Android (`StellarAddress.kt`)

- Location: `android/app/src/main/java/com/ethosprotocol/models/StellarAddress.kt`
- Provides `StellarAddress.isValidPublicKey(value: String): Boolean`
- Used in `CreateVaultDialog.isBeneficiaryValid` inside `Screens.kt`
- Tests: `StellarAddressTest` in `android/app/src/test/java/com/ethosprotocol/StellarAddressTest.kt`

Both implementations are dependency-free (no external Stellar SDK) and implement
the same algorithm so the validation result is identical for any given input.
