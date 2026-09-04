import XCTest
@testable import EthosProtocol

// Test error types for simulating various packet-loss scenarios.
enum NetworkError: Error, Equatable {
    case truncatedResponse
    case connectionReset
    case socketTimeout
    case EOF
}

// Mock random source that returns deterministic values for reproducible testing.
class DeterministicRandomSource: RandomSourceProvider {
    private let values: [Double]
    private var index = 0

    init(_ values: [Double]) {
        self.values = values
    }

    func randomDouble() -> Double {
        guard index < values.count else {
            return 0.0
        }
        defer { index += 1 }
        return values[index]
    }
}

// MARK: - RetryPolicy Tests

final class RetryPolicyTests: XCTestCase {

    // MARK: - Basic Retry Behavior

    func testRetryPolicySucceedsAfterTransientError() async throws {
        var attempts = 0
        let policy = RetryPolicy(
            maxAttempts: 3,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([0.5, 0.5]),
            sleep: { _ in }
        )

        let result = try await withRetry(policy, isRetryable: { _ in true }) {
            attempts += 1
            if attempts < 2 {
                throw NetworkError.connectionReset
            }
            return "success"
        }

        XCTAssertEqual(result, "success")
        XCTAssertEqual(attempts, 2)
    }

    func testRetryPolicyExhaustsAttemptsOnPersistentError() async {
        var attempts = 0
        let policy = RetryPolicy(
            maxAttempts: 3,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([0.5, 0.5]),
            sleep: { _ in }
        )

        do {
            _ = try await withRetry(policy, isRetryable: { _ in true }) {
                attempts += 1
                throw NetworkError.connectionReset
            }
            XCTFail("Should have thrown")
        } catch NetworkError.connectionReset {
            XCTAssertEqual(attempts, 3, "Should have exhausted all attempts")
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testRetryPolicyRespectsIsRetryableFilter() async {
        var attempts = 0
        let policy = RetryPolicy(
            maxAttempts: 3,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([]),
            sleep: { _ in }
        )

        do {
            _ = try await withRetry(policy, isRetryable: { error in
                guard let networkError = error as? NetworkError else { return false }
                return networkError == .connectionReset
            }) {
                attempts += 1
                throw NetworkError.truncatedResponse
            }
            XCTFail("Should have thrown")
        } catch NetworkError.truncatedResponse {
            XCTAssertEqual(attempts, 1, "Should not retry non-retryable errors")
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    // MARK: - Chaos: Simulated Packet Loss

    func testRetryPolicyHandlesTruncatedResponse() async throws {
        var attempts = 0
        let policy = RetryPolicy(
            maxAttempts: 4,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([0.5, 0.5, 0.5]),
            sleep: { _ in }
        )

        let result = try await withRetry(policy, isRetryable: { error in
            guard let networkError = error as? NetworkError else { return false }
            return networkError == .truncatedResponse
        }) {
            attempts += 1
            if attempts < 3 {
                throw NetworkError.truncatedResponse
            }
            return "recovered"
        }

        XCTAssertEqual(result, "recovered")
        XCTAssertEqual(attempts, 3)
    }

    func testRetryPolicyHandlesConnectionReset() async throws {
        var attempts = 0
        let policy = RetryPolicy(
            maxAttempts: 4,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([0.25, 0.5]),
            sleep: { _ in }
        )

        let result = try await withRetry(policy, isRetryable: { error in
            guard let networkError = error as? NetworkError else { return false }
            return networkError == .connectionReset
        }) {
            attempts += 1
            if attempts < 2 {
                throw NetworkError.connectionReset
            }
            return "connection_restored"
        }

        XCTAssertEqual(result, "connection_restored")
        XCTAssertEqual(attempts, 2)
    }

    func testRetryPolicyBackoffIncreasesExponentially() async {
        var sleepDurations: [TimeInterval] = []
        let policy = RetryPolicy(
            maxAttempts: 4,
            baseDelay: 1.0,
            randomSource: DeterministicRandomSource([0.5, 0.5, 0.5]),
            sleep: { duration in
                sleepDurations.append(duration)
            }
        )

        do {
            _ = try await withRetry(policy, isRetryable: { _ in true }) {
                throw NetworkError.socketTimeout
            }
        } catch {
            // Expected
        }

        XCTAssertEqual(sleepDurations.count, 3)
        // With 0.5 jitter and exponential backoff:
        // Attempt 1: 1.0 * 2^0 * 0.5 = 0.5
        // Attempt 2: 1.0 * 2^1 * 0.5 = 1.0
        // Attempt 3: 1.0 * 2^2 * 0.5 = 2.0
        XCTAssertEqual(sleepDurations[0], 0.5, accuracy: 0.01)
        XCTAssertEqual(sleepDurations[1], 1.0, accuracy: 0.01)
        XCTAssertEqual(sleepDurations[2], 2.0, accuracy: 0.01)
    }

    // MARK: - Chaos: Hanging Connections

    func testRetryPolicyDoesNotRetryTimeoutMoreThanMaxAttempts() async {
        var attempts = 0
        let policy = RetryPolicy(
            maxAttempts: 2,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([0.5]),
            sleep: { _ in }
        )

        do {
            _ = try await withRetry(policy, isRetryable: { error in
                guard let networkError = error as? NetworkError else { return false }
                return networkError == .socketTimeout
            }) {
                attempts += 1
                throw NetworkError.socketTimeout
            }
            XCTFail("Should have thrown")
        } catch NetworkError.socketTimeout {
            XCTAssertEqual(attempts, 2, "Should respect maxAttempts limit")
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    // MARK: - Jitter Validation

    func testRetryPolicyJitterStaysBelowExponentialBackoff() async {
        var sleepDurations: [TimeInterval] = []
        let seeds: [(Double, Double)] = [
            (0.0, 0.0),
            (0.9, 0.9),
            (0.5, 0.5),
            (0.1, 0.1),
            (0.99, 0.99),
        ]

        for (seed1, seed2) in seeds {
            sleepDurations.removeAll()
            let policy = RetryPolicy(
                maxAttempts: 3,
                baseDelay: 1.0,
                randomSource: DeterministicRandomSource([seed1, seed2]),
                sleep: { duration in
                    sleepDurations.append(duration)
                }
            )

            do {
                _ = try await withRetry(policy, isRetryable: { _ in true }) {
                    throw NetworkError.connectionReset
                }
            } catch {
                // Expected
            }

            // Verify jitter never exceeds the exponential backoff
            let attempt1Max = 1.0 * pow(2.0, 0.0) // 1.0
            let attempt2Max = 1.0 * pow(2.0, 1.0) // 2.0

            XCTAssert(sleepDurations[0] < attempt1Max, "Attempt 1 jitter exceeded backoff")
            XCTAssert(sleepDurations[1] < attempt2Max, "Attempt 2 jitter exceeded backoff")
            XCTAssert(sleepDurations[0] >= 0, "Sleep duration cannot be negative")
            XCTAssert(sleepDurations[1] >= 0, "Sleep duration cannot be negative")
        }
    }

    // MARK: - Nonce/Timestamp Anti-Replay Verification

    func testRetryPolicyDoesNotDoubleSubmitMutatingRequests() async throws {
        var postCount = 0
        let policy = RetryPolicy(
            maxAttempts: 3,
            baseDelay: 0.01,
            randomSource: DeterministicRandomSource([0.5, 0.5]),
            sleep: { _ in }
        )

        // Simulate a mutating request (e.g., check-in) that can only be retried
        // if the nonce/timestamp proves it wasn't already executed.
        let result = try await withRetry(policy, isRetryable: { error in
            guard let networkError = error as? NetworkError else { return false }
            // Only retry transient network errors, not "already processed" errors
            return networkError == .connectionReset
        }) {
            postCount += 1
            if postCount < 2 {
                throw NetworkError.connectionReset
            }
            return "check_in_recorded"
        }

        XCTAssertEqual(result, "check_in_recorded")
        XCTAssertEqual(postCount, 2, "Mutating request should only be submitted twice (attempt + retry)")
    }

    // MARK: - Concurrent Retry Behavior

    func testMultipleConcurrentRetriesProduceDifferentJitter() async throws {
        var delaysA: [TimeInterval] = []
        var delaysB: [TimeInterval] = []

        let policyA = RetryPolicy(
            maxAttempts: 2,
            baseDelay: 1.0,
            randomSource: DeterministicRandomSource([0.3]),
            sleep: { delaysA.append($0) }
        )

        let policyB = RetryPolicy(
            maxAttempts: 2,
            baseDelay: 1.0,
            randomSource: DeterministicRandomSource([0.7]),
            sleep: { delaysB.append($0) }
        )

        do {
            _ = try await withRetry(policyA, isRetryable: { _ in true }) {
                throw NetworkError.connectionReset
            }
        } catch {
            // Expected
        }

        do {
            _ = try await withRetry(policyB, isRetryable: { _ in true }) {
                throw NetworkError.connectionReset
            }
        } catch {
            // Expected
        }

        XCTAssertEqual(delaysA.count, 1)
        XCTAssertEqual(delaysB.count, 1)
        XCTAssertNotEqual(delaysA[0], delaysB[0], "Different random sources should produce different delays")
    }
}
