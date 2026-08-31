package com.ethosprotocol.testing

/**
 * Marks a flaky instrumented test that may fail intermittently due to emulator/device
 * resource contention, timing issues, or other non-deterministic factors.
 *
 * Flaky tests should eventually be fixed (root cause addressed) or quarantined via
 * @Ignore if they're blocking CI but not yet resolved. See .github/FLAKY_TESTS.md.
 *
 * Usage:
 * ```kotlin
 * @Flaky(maxAttempts = 3, reason = "Emulator resource contention under load")
 * @Test
 * fun testVaultListRefresh() { ... }
 * ```
 *
 * @param maxAttempts Maximum number of attempts before failing. Default: 1 (disabled).
 *        Set to 2+ to enable automatic retry on failure.
 * @param reason Human-readable description of why the test is flaky or what's being
 *        tracked. Included in CI logs to help future readers understand the issue.
 * @param issueNumber GitHub issue number tracking the root cause, if known.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Flaky(
    val maxAttempts: Int = 1,
    val reason: String = "Test is known to be flaky",
    val issueNumber: String = ""
)
