# Mutation Testing — Stellar Address Validation

**Tracking**: Testing & Quality issue "Mutation testing for `shared/stellar-validation-spec.md`
validators" (companion to #113).

`StellarAddressTest.kt` (Android) and `StellarAddressTests` in
`EthosProtocolTests.swift` (iOS) both had "broad" valid/invalid coverage, but
line/branch coverage alone doesn't prove every one of the six reject
conditions in `stellar-validation-spec.md` is actually exercised — a test
suite can hit every line while still never distinguishing *why* a given
input was rejected. Mutation testing closes that gap: a mutation tool edits
the implementation (flips a comparison, deletes a branch, changes a boundary)
and re-runs the suite; if the suite still passes, the mutant "survived" and
that behavior isn't actually under test.

## Tooling

| Platform | Tool | Notes |
|----------|------|-------|
| Android / Kotlin | [PIT](https://pitest.org) via the `info.solidsoft.pitest` Gradle plugin, in Kotlin-aware mode | See `android/app/mutation-testing.gradle.kts` for the scoped config (targets `com.ethosprotocol.models.StellarAddress` only, to keep runs fast) |
| iOS / Swift | [Mull](https://github.com/mull-project/mull) | See `ios/mull.yml` for the scoped config (same rationale — one file, one target) |

Both configs restrict mutation to `StellarAddress` specifically rather than
the whole codebase: running a mutation tool project-wide is slow and noisy,
and the goal here is verifying one precisely-specified algorithm against its
spec, not a general-purpose coverage gate.

## Running

```bash
# Android
cd android && ./gradlew pitest -PmutationTarget=com.ethosprotocol.models.StellarAddress

# iOS (requires Mull installed via the instructions in ios/mull.yml)
cd ios/EthosProtocol && mull-runner-19 --config ../mull.yml build/EthosProtocol.xctest
```

Neither command has been run in CI yet — wiring `pitest`/`mull` into a
workflow is a follow-up once the above configs are reviewed. This document
tracks the mutants found by a manual review pass against the spec's six
reject conditions, and the tests added in response, so the *next* run
(whenever the tool is executed) has a documented baseline to compare against
rather than starting from zero context.

## Baseline: mutants found by manual spec walkthrough

Walking each of the six steps in `stellar-validation-spec.md` against the
existing test fixtures (before this change) found two reject conditions with
no dedicated fixture — i.e. mutants a PIT/Mull run would be expected to
report as "survived":

| Step | Reject condition | Prior coverage | Status |
|------|-------------------|-----------------|--------|
| 1. Length | `len != 56` | Covered (too-short, too-long) | OK |
| 2. Prefix | `input[0] != 'G'` | Covered | OK |
| 3. Character set | char outside `[A-Z2-7]` | Covered mid-string only, not at the last index | **Gap — fixed** (added last-character fixture) |
| 4. Base32 decode | reject invalid char | Same code path as step 3 in both implementations (decode fails only when a character isn't in the alphabet, which step 3 already rejects) — not independently observable, no separate test needed | N/A |
| 5. Version byte | `decoded[0] != 0x30` | **Not covered** — every invalid fixture failed at an earlier step, so a mutant deleting the version-byte comparison entirely would have survived | **Gap — fixed** (added `GEAAA…BBDI` fixture: valid prefix/length/charset/checksum, wrong version byte) |
| 6. Checksum | CRC-16/XModem mismatch | Only tested via a corruption in the *last* character | **Gap — fixed** (added a mid-payload corruption fixture, index 27) |

Three tests were added to each platform's suite (see
`StellarAddressTest.kt` and `EthosProtocolTests.swift`) directly targeting
the gaps above. The new fixtures are also listed in
`stellar-validation-spec.md`'s shared fixture tables so both platforms stay
in sync.

## Regression tracking

Once `pitest`/`mull` are wired into CI, record each run's mutation score
here as `YYYY-MM-DD: android XX% / ios YY%` so a drop in score (new
production code added to `StellarAddress` without matching tests) is
visible in review instead of silently shipping.

- _(no automated run recorded yet — this file was seeded by the manual gap
  analysis above; first tool-driven baseline goes here)_
