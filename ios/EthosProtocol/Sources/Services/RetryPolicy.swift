import Foundation

/// Provides random numbers for jitter in exponential backoff calculations.
/// Injected into RetryPolicy to allow deterministic testing while production
/// code uses the default system random source.
protocol RandomSourceProvider {
    /// Returns a random value in [0, 1).
    func randomDouble() -> Double
}

/// System random source for production use.
struct SystemRandomSource: RandomSourceProvider {
    func randomDouble() -> Double {
        Double.random(in: 0.0..<1.0)
    }
}

// Retry configuration for idempotent network calls. APIClient applies this only
// to GET requests — POST/DELETE must never be retried automatically, since a
// retried mutation (check-in, withdrawal, 2FA disable, ...) could double-submit.
struct RetryPolicy {
    let maxAttempts: Int
    let baseDelay: TimeInterval
    /// Random source for jitter computation in delay calculations. Injected
    /// to allow deterministic testing; defaults to SystemRandomSource in production.
    let randomSource: RandomSourceProvider
    let sleep: (TimeInterval) async throws -> Void

    static let networkDefault = RetryPolicy(
        maxAttempts: 3,
        baseDelay: 0.5,
        randomSource: SystemRandomSource(),
        sleep: { seconds in
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    )
}

/// Retries `operation` with exponential backoff (`baseDelay * 2^attempt`) plus
/// randomized jitter (full exponential distribution with jitter in [0, baseDelay * 2^attempt])
/// up to `policy.maxAttempts` total attempts, but only for errors `isRetryable` accepts.
/// Any other error — or exhausting the attempt budget — is rethrown immediately.
/// Jitter is applied to reduce synchronized retry storms: two concurrent retries at the
/// same attempt count will compute different delays (unless they share the same random source,
/// as in tests).
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
            // Exponential backoff: baseDelay * 2^(attempt-1)
            let baseBackoff = policy.baseDelay * pow(2.0, Double(attempt - 1))
            // Apply jitter: multiply by a random factor in [0, 1)
            let jitter = policy.randomSource.randomDouble()
            let delay = baseBackoff * jitter
            try await policy.sleep(delay)
        }
    }
}
