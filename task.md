#120 Require Biometric Confirmation Before Disabling 2FA
Repo Avatar
ethos-protocol/ethos-mobile
Labels: bug, cross-platform, security
Priority: High
Estimated Time: 1 hour

Description:
VaultDetailView.disable2FA() (iOS, Views.swift:246-255) and TwoFactorViewModel.disable2FA
(Android, ui/ViewModels.kt:115-122) both call the API directly with no BiometricService/
BiometricHelper gate — unlike check-in, which is explicitly biometric-gated on both platforms.
Disabling 2FA is at least as security-sensitive as a check-in (arguably more so, since it weakens
the account's own protections), yet it's the one destructive action left unguarded.

Tasks:

Add a BiometricService.authenticate/BiometricHelper.authenticate gate before calling disable2FA on both platforms
Add tests asserting the API call is never reached without a successful biometric confirmation
Audit other destructive/security-sensitive actions (e.g., once implemented, beneficiary management #15/#67) for the same gate

#119 Add Client-Side Rate Limiting on OTP Verification Attempts
Repo Avatar
ethos-protocol/ethos-mobile
Labels: enhancement, cross-platform, security
Priority: Medium
Estimated Time: 1 hour

Description:
TwoFactorVerifyView (iOS) and TwoFactorVerifyScreen (Android) both allow unlimited rapid
verify2FA submissions with no client-side cooldown or attempt cap — a 6-digit OTP has a
brute-forceable keyspace if the only rate limiting is server-side and unknown to the client.

Tasks:

Add a client-side attempt counter with escalating cooldown after repeated failures on both platforms
Surface remaining attempts/cooldown in the UI
Add tests for the cooldown escalation and reset-on-success behavior

#118 Add Device Integrity (Jailbreak/Root) Detection
Repo Avatar
ethos-protocol/ethos-mobile
Labels: enhancement, cross-platform, security
Priority: Medium
Estimated Time: 2 hours

Description:
Neither app performs any jailbreak/root or debugger-attached detection before handling vault
balances, private-key-backed passkeys, or 2FA secrets — common due diligence for an app
functioning as "secure digital inheritance" (per AuthView's own tagline).

Tasks:

Add basic jailbreak/root heuristics on both platforms
Show a non-blocking warning (not necessarily a hard block) when detected, consistent with the app's security posture
Add tests for the detection heuristics where feasible in a simulator/emulator

#117 Add Certificate Pinning for API Traffic
Repo Avatar
ethos-protocol/ethos-mobile
Labels: enhancement, cross-platform, security
Priority: Medium
Estimated Time: 2 hours

Description:
URLSession(configuration: .default) (iOS APIClient.swift:38) and Ktor's default Android
engine (ApiClient.kt:35) both rely solely on the system trust store with no certificate/public-key
pinning, leaving the app's API traffic (including bearer tokens and vault financial data) reliant
entirely on CA trust and OS-level TLS validation.

Tasks:

Add certificate or public-key pinning to both clients for the api.ethos-protocol.app host
Define and test a pin-rotation process to avoid a hard outage on certificate renewal
Add tests (or documented manual verification) for pin-mismatch rejection
