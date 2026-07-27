package com.ethosprotocol.api

import kotlinx.coroutines.delay

// Retry configuration for idempotent network calls. ApiClient applies this only
// to GET requests — POST/DELETE must never be retried automatically, since a
// retried mutation (check-in, withdrawal, 2FA disable, ...) could double-submit.
data class RetryPolicy(
    val maxAttempts: Int,
    val baseDelayMillis: Long,
    val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    companion object {
        val networkDefault = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500)
    }
}

// Retries [operation] with exponential backoff (`baseDelayMillis * 2^attempt`) up to
// `policy.maxAttempts` total attempts, but only for errors [isRetryable] accepts.
// Any other error — or exhausting the attempt budget — is rethrown immediately.
suspend fun <T> withRetry(
    policy: RetryPolicy,
    isRetryable: (Throwable) -> Boolean,
    operation: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return operation()
        } catch (e: Throwable) {
            attempt++
            if (attempt >= policy.maxAttempts || !isRetryable(e)) throw e
            val delayMillis = policy.baseDelayMillis * (1L shl (attempt - 1))
            policy.sleep(delayMillis)
        }
    }
}
