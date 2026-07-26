import Foundation

// Retry configuration for idempotent network calls. APIClient applies this only
// to GET requests — POST/DELETE must never be retried automatically, since a
// retried mutation (check-in, withdrawal, 2FA disable, ...) could double-submit.
struct RetryPolicy {
    let maxAttempts: Int
    let baseDelay: TimeInterval
    let sleep: (TimeInterval) async throws -> Void

    static let networkDefault = RetryPolicy(
        maxAttempts: 3,
        baseDelay: 0.5,
        sleep: { seconds in
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    )
}

/// Retries `operation` with exponential backoff (`baseDelay * 2^attempt`) up to
/// `policy.maxAttempts` total attempts, but only for errors `isRetryable` accepts.
/// Any other error — or exhausting the attempt budget — is rethrown immediately.
func withRetry<T>(
    _ policy: RetryPolicy,
    isRetryable: (Error) -> Bool,
    operation: () async throws -> T
) async throws -> T {
    var attempt = 0
    while true {
        do {
            return try await operation()
        } catch {
            attempt += 1
            guard attempt < policy.maxAttempts, isRetryable(error) else { throw error }
            let delay = policy.baseDelay * pow(2.0, Double(attempt - 1))
            try await policy.sleep(delay)
        }
    }
}
