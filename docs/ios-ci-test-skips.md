# iOS CI Test Skips

The iOS workflow permits at most ten `XCTSkipIf(... environment["CI"] != nil)` calls. The limit is enforced by `.github/scripts/check_ios_ci_skips.py`; tests that can run with an injected store or in the hosted app target should not add to this list.

| Test | Why it remains skipped in CI |
| --- | --- |
| `HandleRefreshTests.test_handleRefresh_onlySchedulesTTLWarningForVaultsUnder24h` | Calling `UNUserNotificationCenter` from the hostless SPM test bundle can trap; notification scheduling is covered in the hosted target. |
| `AuthStoreSignOutTests.test_signOut_unregistersPersistedPushToken` | The assertion requires reading a just-written Keychain value, which is unreliable without the app host's Keychain access group. |
| `PushTokenRegistrationRetryTests.test_registerPushToken_succeedsAfterTransientFailures_savesTokenAndClearsPending` | Verifying persisted push-token state requires the app host's Keychain access group. |
| `PushTokenRegistrationRetryTests.test_registerPushToken_exhaustsRetries_persistsPendingToken` | Verifying persisted pending-token state requires the app host's Keychain access group. |
| `PushTokenRegistrationRetryTests.test_retryPendingPushTokenRegistrationIfNeeded_retriesAndClearsOnSuccess` | Verifying persisted push-token state requires the app host's Keychain access group. |
| `PushTokenRegistrationRetryTests.test_retryPendingPushTokenRegistrationIfNeeded_withNoPendingToken_doesNotCallRegister` | Confirming the absent persisted pending token requires the app host's Keychain access group. |
| `NotificationServiceHostedTests.test_scheduleTTLWarning_verifyPendingNotificationRequests` | Simulator CI cannot grant notification authorization, so scheduled requests do not appear in the pending-request query. |
| `NotificationServiceHostedTests.test_scheduleTTLWarning_withTriggerIntervalValidation` | Simulator CI cannot grant notification authorization, so scheduled requests do not appear in the pending-request query. |
| `NotificationServiceHostedTests.test_scheduleTTLWarning_bodyIncludesTruncatedVaultIdAndTTL` | Simulator CI cannot grant notification authorization, so scheduled requests do not appear in the pending-request query. |
| `NotificationServiceHostedTests.test_scheduleCheckInReminder_bodyIncludesTruncatedVaultIdAndTTL` | Simulator CI cannot grant notification authorization, so scheduled requests do not appear in the pending-request query. |

Keychain round-trip and notification scheduling tests that do not inspect authorization-dependent pending requests run in the hosted target. iCloud toggle, merge, and push behavior use injected in-memory stores and run in the SPM target without an iCloud entitlement.
