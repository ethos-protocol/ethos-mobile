## Summary

<!-- What does this PR do? One sentence is enough for small changes. -->

## Changes

<!-- Bullet-point list of what changed and why. -->

## Testing

<!-- How was this tested? (unit tests, manual smoke test, simulator, device, …) -->

## Security checklist

> Full checklist and rationale: [docs/security-guidelines.md](../docs/security-guidelines.md)
> (§7). Please answer these before requesting review.

- [ ] `pre-commit run --all-files` passes and the diff contains no secrets, signing
      material, or `gradle.properties` with pins.
- [ ] No new dependency, or its transitive tree was reviewed and is clean.
- [ ] Nothing sensitive is logged, and any new sensitive header was added to
      `LogRedactor` on **both** platforms.
- [ ] Any new secret is stored in the Keychain / `EncryptedSharedPreferences` and is
      deleted on sign-out.
- [ ] New untrusted input is allowlist-validated at the boundary with a length bound.
- [ ] **If this touches auth, keys, or sessions:** the auth/keys checklist section in
      the guidelines applies.
- [ ] **If this touches networking or CI:** the networking/CI checklist sections apply.

<!-- Answer "yes", "no — not applicable", or explain. Don't just delete a line. -->

## Parity checklist

> This project maintains a feature-parity table in [PARITY.md](../PARITY.md) that
> tracks which features are implemented on iOS vs Android. **Please answer the
> questions below before requesting review.**

- [ ] This PR **does not** add, change, or remove any user-facing feature on either
  platform — no PARITY.md update needed.

  **— OR —**

- [ ] This PR adds/changes/removes a user-facing feature. I have updated PARITY.md:
  - Updated the status symbol(s) for the affected row(s).
  - Added or updated "Notes" if the implementation is partial or has caveats.
  - Removed or updated any rows in the "Known gaps" table that this PR closes.

<!-- If you changed only one platform, call out the gap explicitly so it doesn't
     get lost. Example:
     > Implemented Withdraw on Android. iOS already has this (✅). Updated PARITY.md. -->

## Related issues


## Description
<!-- What does this PR do and why? -->

## Related Issue
Closes #

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Documentation
- [ ] Refactor
- [ ] Other:

## How Was This Tested?
<!-- Describe tests run, devices/OS used -->

## Screenshots (if UI changes)

## Checklist
- [ ] Follows the code style in CONTRIBUTING.md
- [ ] Lint and tests pass locally
- [ ] Tests added/updated
- [ ] Documentation updated

<!-- Closes #... -->
