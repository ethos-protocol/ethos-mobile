import XCTest
@testable import EthosProtocol

// MARK: - Mock WebSocket Task

/// A fake WebSocketTasking that lets tests drive the receive loop directly
/// (simulate an incoming message or a dropped connection) instead of hitting a
/// real socket. Mirrors MockBackgroundRefreshTask's role in
/// BackgroundRefreshServiceTests.
final class MockWebSocketTask: WebSocketTasking {
    private(set) var resumeCallCount = 0
    private(set) var cancelCallCount = 0
    private var receiveHandler: ((Result<URLSessionWebSocketTask.Message, Error>) -> Void)?
    // #257: close code exposed so tests can inspect it and simulateClose can set it.
    private(set) var closeCode: URLSessionWebSocketTask.CloseCode = .invalid

    func resume() { resumeCallCount += 1 }

    func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        cancelCallCount += 1
    }

    func receive(completionHandler: @escaping (Result<URLSessionWebSocketTask.Message, Error>) -> Void) {
        receiveHandler = completionHandler
    }

    func simulateFailure() {
        receiveHandler?(.failure(URLError(.networkConnectionLost)))
    }

    func simulateMessage(_ message: URLSessionWebSocketTask.Message) {
        receiveHandler?(.success(message))
    }

    /// #257: Simulate the server closing the socket with the given close code.
    /// VaultEventSocket inspects the `closeCode` on the underlying URLSessionWebSocketTask;
    /// this helper delivers a failure (matching what URLSession actually does) and sets the
    /// close code so the socket can read it back.
    func simulateClose(code: URLSessionWebSocketTask.CloseCode) {
        closeCode = code
        receiveHandler?(.failure(URLError(.networkConnectionLost)))
    }
}

/// Polls `condition` until it's true or `timeout` elapses — VaultEventSocket's
/// receive/reconnect handling hops across a couple of Task boundaries (real
/// production behavior, not test-only), so assertions need to wait for those
/// hops to settle rather than checking state synchronously right after driving
/// a mock callback.
@MainActor
private func waitUntil(timeout: TimeInterval = 2.0, _ condition: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() && Date() < deadline {
        try? await Task.sleep(nanoseconds: 1_000_000) // 1ms
    }
    return condition()
}

// MARK: - Deterministic Random Source for Testing

/// Deterministic random source for testing: returns a fixed sequence of values.
private final class DeterministicRandomSource: RandomSourceProvider {
    private var sequence: [Double]
    private var index = 0

    init(_ values: [Double]) {
        self.sequence = values
    }

    func randomDouble() -> Double {
        defer { index += 1 }
        guard index < sequence.count else { return 0.0 }
        return sequence[index]
    }
}

// MARK: - #20 ReconnectBackoff Tests

final class ReconnectBackoffTests: XCTestCase {

    func test_delay_doublesWithEachAttempt_withoutJitter() {
        var randomSource = DeterministicRandomSource([1.0, 1.0, 1.0, 1.0]) // No jitter
        let backoff = ReconnectBackoff(
            baseDelay: 1.0,
            maxDelay: 100.0,
            randomSource: randomSource,
            sleep: { _ in }
        )
        XCTAssertEqual(backoff.delay(forAttempt: 1), 1.0)
        XCTAssertEqual(backoff.delay(forAttempt: 2), 2.0)
        XCTAssertEqual(backoff.delay(forAttempt: 3), 4.0)
        XCTAssertEqual(backoff.delay(forAttempt: 4), 8.0)
    }

    func test_delay_capsAtMaxDelay_withoutJitter() {
        var randomSource = DeterministicRandomSource([1.0, 1.0, 1.0])
        let backoff = ReconnectBackoff(
            baseDelay: 1.0,
            maxDelay: 5.0,
            randomSource: randomSource,
            sleep: { _ in }
        )
        XCTAssertEqual(backoff.delay(forAttempt: 10), 5.0)
    }

    /// Test: Two backoff.delay(forAttempt:) calls for the same attempt, given
    /// different random sources, produce different delay values.
    func test_delay_producesVariedDelaysWithDifferentRandomSources() {
        var randomSource1 = DeterministicRandomSource([0.25])
        let backoff1 = ReconnectBackoff(
            baseDelay: 1.0,
            maxDelay: 100.0,
            randomSource: randomSource1,
            sleep: { _ in }
        )

        var randomSource2 = DeterministicRandomSource([0.75])
        let backoff2 = ReconnectBackoff(
            baseDelay: 1.0,
            maxDelay: 100.0,
            randomSource: randomSource2,
            sleep: { _ in }
        )

        let delay1 = backoff1.delay(forAttempt: 3)
        let delay2 = backoff2.delay(forAttempt: 3)

        // Both should compute from (1.0 * 2^2 = 4.0) * jitter
        // delay1: 4.0 * 0.25 = 1.0
        // delay2: 4.0 * 0.75 = 3.0
        XCTAssertEqual(delay1, 1.0)
        XCTAssertEqual(delay2, 3.0)
        XCTAssertNotEqual(delay1, delay2)
    }

    /// Test: The jittered delay for any attempt stays within documented bounds
    /// (never exceeds the pre-jitter exponential value, never negative).
    func test_delay_staysWithinBounds_acrossAttemptRange() {
        let baseDelay = 1.0
        let maxDelay = 30.0

        // Test across a range of attempt numbers with various jitter values
        for attempt in 1...10 {
            let preJitterDelay = min(maxDelay, baseDelay * pow(2.0, Double(attempt - 1)))

            // Test with jitter values at the extremes: 0.0, 0.5, 0.999
            for jitterFactor in [0.0, 0.5, 0.999] {
                var randomSource = DeterministicRandomSource([jitterFactor])
                let backoff = ReconnectBackoff(
                    baseDelay: baseDelay,
                    maxDelay: maxDelay,
                    randomSource: randomSource,
                    sleep: { _ in }
                )

                let delay = backoff.delay(forAttempt: attempt)

                XCTAssertGreaterThanOrEqual(delay, 0.0, "Delay should never be negative for attempt \(attempt) with jitter \(jitterFactor)")
                XCTAssertLessThanOrEqual(delay, preJitterDelay, "Jittered delay should not exceed pre-jitter value for attempt \(attempt) with jitter \(jitterFactor)")
            }
        }
    }
}

// MARK: - #20 VaultEventSocket URL Construction Tests

final class VaultEventSocketURLTests: XCTestCase {

    func test_webSocketURL_convertsHTTPSToWSS() {
        let url = VaultEventSocket.webSocketURL(baseURL: URL(string: "https://api.ethos-protocol.app/v1")!, vaultID: "vault-1")
        XCTAssertEqual(url?.scheme, "wss")
        XCTAssertEqual(url?.path, "/v1/ws")
        XCTAssertEqual(url?.query, "vault_id=vault-1")
    }

    func test_webSocketURL_convertsHTTPToWS() {
        let url = VaultEventSocket.webSocketURL(baseURL: URL(string: "http://localhost:8080/v1")!, vaultID: "vault-2")
        XCTAssertEqual(url?.scheme, "ws")
    }
}

// MARK: - #20 VaultEventSocket Reconnect/Backoff Tests

@MainActor
final class VaultEventSocketTests: XCTestCase {

    func test_connect_resumesTaskAndTransitionsToConnected() {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        socket.connect(vaultID: "vault-1")

        XCTAssertEqual(mockTask.resumeCallCount, 1)
        XCTAssertEqual(socket.state, .connected)
    }

    func test_connectionDrop_reconnectsWithNewTask_andResetsAttemptCountOnSuccess() async {
        var tasks: [MockWebSocketTask] = []
        var randomSource = DeterministicRandomSource([1.0])
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 5,
            backoff: ReconnectBackoff(baseDelay: 1, maxDelay: 30, randomSource: randomSource, sleep: { _ in }),
            makeTask: { _ in
                let task = MockWebSocketTask()
                tasks.append(task)
                return task
            }
        )

        socket.connect(vaultID: "vault-1")
        XCTAssertEqual(tasks.count, 1)

        tasks[0].simulateFailure()

        let reconnected = await waitUntil { tasks.count == 2 && socket.state == .connected }
        XCTAssertTrue(reconnected, "socket should open a new task and reach .connected after a drop")
        // resume() alone is optimistic and doesn't confirm connectivity, so the
        // attempt counter persists until a message is actually received — see
        // openSocket()'s comment. Without this, a socket that fails immediately
        // on every reconnect would never reach maxReconnectAttempts.
        XCTAssertEqual(socket.reconnectAttempt, 1)

        tasks[1].simulateMessage(.string(#"{"type": "unknown"}"#))
        let reset = await waitUntil { socket.reconnectAttempt == 0 }
        XCTAssertTrue(reset, "receiving any message confirms the connection and resets the backoff counter")
    }

    func test_repeatedFailures_exceedingMaxAttempts_fallsBackToPolling() async {
        var tasks: [MockWebSocketTask] = []
        var randomSource = DeterministicRandomSource([1.0, 1.0, 1.0])
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 3,
            backoff: ReconnectBackoff(baseDelay: 1, maxDelay: 30, randomSource: randomSource, sleep: { _ in }),
            makeTask: { _ in
                let task = MockWebSocketTask()
                tasks.append(task)
                return task
            }
        )

        socket.connect(vaultID: "vault-1")

        // Fail the connection 3 times in a row (maxReconnectAttempts) without ever
        // letting a reconnect succeed, by immediately failing each new task too.
        for _ in 0..<3 {
            let countBefore = tasks.count
            tasks.last?.simulateFailure()
            _ = await waitUntil { tasks.count > countBefore || socket.state == .fallbackToPolling }
        }

        let fellBack = await waitUntil { socket.state == .fallbackToPolling }
        XCTAssertTrue(fellBack, "should stop retrying and report .fallbackToPolling after maxReconnectAttempts")

        let taskCountAtFallback = tasks.count
        // No further reconnect attempts once fallen back.
        try? await Task.sleep(nanoseconds: 20_000_000) // 20ms — long enough for a stray reconnect to fire if one were scheduled
        XCTAssertEqual(tasks.count, taskCountAtFallback)
    }

    /// Holds a suspended sleep's continuation so the test can control exactly
    /// when the reconnect's backoff delay "elapses", instead of racing a real
    /// (or instant, which resolves before stop() has a chance to run) timer.
    private final class SuspendedSleep: @unchecked Sendable {
        var continuation: CheckedContinuation<Void, Never>?
    }

    func test_stop_cancelsTaskAndPendingReconnect() async {
        var tasks: [MockWebSocketTask] = []
        let suspendedSleep = SuspendedSleep()
        var randomSource = DeterministicRandomSource([1.0])
        let blockingBackoff = ReconnectBackoff(baseDelay: 1, maxDelay: 30, randomSource: randomSource, sleep: { _ in
            await withCheckedContinuation { suspendedSleep.continuation = $0 }
        })
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 5,
            backoff: blockingBackoff,
            makeTask: { _ in
                let task = MockWebSocketTask()
                tasks.append(task)
                return task
            }
        )

        socket.connect(vaultID: "vault-1")
        tasks[0].simulateFailure()

        // Wait until handleFailure() has actually run and is now blocked inside
        // the (controlled) backoff sleep, i.e. a reconnect is genuinely pending.
        let scheduled = await waitUntil { socket.reconnectAttempt == 1 && socket.state == .disconnected }
        XCTAssertTrue(scheduled)

        socket.stop()

        XCTAssertEqual(socket.state, .disconnected)
        XCTAssertEqual(tasks[0].cancelCallCount, 1)

        // Let the blocked sleep resolve now, after stop(). If stop() didn't
        // actually cancel the pending reconnect Task, this would let it proceed
        // and open a second task.
        suspendedSleep.continuation?.resume()
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(tasks.count, 1, "stop() should prevent the pending reconnect from opening a new task")
    }

    func test_vaultUpdatedMessage_decodesAndFiresOnEvent() async {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        let json = """
        {"type": "vault_updated", "vault": {"id": "vault-1", "owner": "GABC", "beneficiary": "GXYZ", "balance": 42, "check_in_interval": 2592000, "last_check_in": "2026-01-01T00:00:00Z", "ttl_remaining": 1000, "status": "active"}}
        """
        mockTask.simulateMessage(.string(json))

        let received = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(received)
        guard case .vaultUpdated(let vault) = receivedEvent else {
            return XCTFail("expected .vaultUpdated")
        }
        XCTAssertEqual(vault.id, "vault-1")
        XCTAssertEqual(vault.balance, 42)
    }

    func test_malformedMessage_doesNotFireOnEvent() async {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        mockTask.simulateMessage(.string("not valid json"))

        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertNil(receivedEvent)
    }

    // MARK: - #179 Ported event cases (vault_expired, vault_released, ping, error)

    func test_vaultExpiredMessage_decodesAndFiresOnEvent() async {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        let json = #"{"type": "vault_expired", "vault_id": "vault-1", "expired_at": "2026-06-01T12:00:00Z"}"#
        mockTask.simulateMessage(.string(json))

        let received = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(received, "vault_expired message should fire onEvent")
        guard case .vaultExpired(let id, let expiredAt) = receivedEvent else {
            return XCTFail("expected .vaultExpired, got \(String(describing: receivedEvent))")
        }
        XCTAssertEqual(id, "vault-1")
        // ISO8601: 2026-06-01T12:00:00Z
        XCTAssertEqual(expiredAt.timeIntervalSince1970, 1_748_779_200, accuracy: 1.0)
    }

    func test_vaultReleasedMessage_decodesAndFiresOnEvent() async {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-2")

        let json = #"{"type": "vault_released", "vault_id": "vault-2", "released_at": "2026-06-01T12:00:00Z", "amount": 5000000}"#
        mockTask.simulateMessage(.string(json))

        let received = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(received, "vault_released message should fire onEvent")
        guard case .vaultReleased(let id, _, let amount) = receivedEvent else {
            return XCTFail("expected .vaultReleased, got \(String(describing: receivedEvent))")
        }
        XCTAssertEqual(id, "vault-2")
        XCTAssertEqual(amount, 5_000_000)
    }

    func test_pingMessage_firesOnEvent() async {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        mockTask.simulateMessage(.string(#"{"type": "ping"}"#))

        let received = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(received, "ping message should fire onEvent")
        XCTAssertEqual(receivedEvent, .ping)
    }

    func test_errorMessage_decodesAndFiresOnEvent() async {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        let json = #"{"type": "error", "code": "invalid_vault_id", "message": "Vault not found"}"#
        mockTask.simulateMessage(.string(json))

        let received = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(received, "error message should fire onEvent")
        guard case .error(let code, let message) = receivedEvent else {
            return XCTFail("expected .error, got \(String(describing: receivedEvent))")
        }
        XCTAssertEqual(code, "invalid_vault_id")
        XCTAssertEqual(message, "Vault not found")
    }

    func test_unknownMessageType_firesUnknownEvent() async {
        // api-contract.md: "clients should ignore unrecognized values instead of erroring"
        // VaultEventSocket fires .unknown so callers can no-op cleanly.
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        mockTask.simulateMessage(.string(#"{"type": "future_event_type"}"#))

        let received = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(received, "unrecognized type should still fire onEvent with .unknown")
        XCTAssertEqual(receivedEvent, .unknown)
    }

    // MARK: - #257 WebSocket close code 4401 handling

    func test_4401Close_withSuccessfulRefresh_reconnectsAndContinues() async {
        // Arrange: first task closes with 4401; after refresh, second task receives a message.
        var tasks: [MockWebSocketTask] = []
        var refreshCallCount = 0
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 5,
            backoff: ReconnectBackoff(baseDelay: 0, maxDelay: 0, randomSource: DeterministicRandomSource([0.0]), sleep: { _ in }),
            makeTask: { _ in
                let task = MockWebSocketTask()
                tasks.append(task)
                return task
            },
            tokenRefresh: {
                refreshCallCount += 1
                return "refreshed-token-xyz"
            }
        )

        var receivedEvent: VaultEventSocket.VaultEvent?
        socket.onEvent = { receivedEvent = $0 }
        socket.connect(vaultID: "vault-1")

        // Simulate the server closing with 4401.
        tasks[0].simulateClose(code: URLSessionWebSocketTask.CloseCode(rawValue: 4401) ?? .invalid)

        // Wait for the refresh to fire and a second task to be created.
        let reconnected = await waitUntil { tasks.count == 2 }
        XCTAssertTrue(reconnected, "should open a new task after successful token refresh")
        XCTAssertEqual(refreshCallCount, 1, "should attempt refresh exactly once")
        XCTAssertNotEqual(socket.state, .authFailure, "state must not be authFailure after a successful refresh")

        // Second task delivers a real event.
        tasks[1].simulateMessage(.string(#"{"type": "ping"}"#))
        let gotEvent = await waitUntil { receivedEvent != nil }
        XCTAssertTrue(gotEvent, "should receive events after reconnecting with refreshed token")
    }

    func test_4401Close_withFailedRefresh_transitionsToAuthFailure() async {
        // Arrange: the refresh endpoint rejects the token (401 / token fully revoked).
        var tasks: [MockWebSocketTask] = []
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 5,
            backoff: ReconnectBackoff(baseDelay: 0, maxDelay: 0, randomSource: DeterministicRandomSource([0.0]), sleep: { _ in }),
            makeTask: { _ in
                let task = MockWebSocketTask()
                tasks.append(task)
                return task
            },
            tokenRefresh: {
                throw URLError(.userAuthenticationRequired)
            }
        )

        var stateChanges: [VaultEventSocket.ConnectionState] = []
        socket.onStateChange = { stateChanges.append($0) }
        socket.connect(vaultID: "vault-1")

        tasks[0].simulateClose(code: URLSessionWebSocketTask.CloseCode(rawValue: 4401) ?? .invalid)

        let fellToAuthFailure = await waitUntil { socket.state == .authFailure }
        XCTAssertTrue(fellToAuthFailure, "should transition to .authFailure when refresh fails after 4401")
        // Must not open a further reconnect task once auth fails.
        let taskCountAtFailure = tasks.count
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(tasks.count, taskCountAtFailure, "must not attempt further reconnects after .authFailure")
    }

    func test_nonAuth_closeCode_doesNotTriggerRefresh() async {
        // A normal 1001 (Going Away) or 1000 (Normal Closure) must not call the refresh endpoint.
        var tasks: [MockWebSocketTask] = []
        var refreshCallCount = 0
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 5,
            backoff: ReconnectBackoff(baseDelay: 0, maxDelay: 0, randomSource: DeterministicRandomSource([0.0]), sleep: { _ in }),
            makeTask: { _ in
                let task = MockWebSocketTask()
                tasks.append(task)
                return task
            },
            tokenRefresh: {
                refreshCallCount += 1
                return "should-not-be-called"
            }
        )

        socket.connect(vaultID: "vault-1")
        tasks[0].simulateFailure() // Generic failure, not a 4401 close.

        let reconnected = await waitUntil { tasks.count == 2 }
        XCTAssertTrue(reconnected, "should reconnect after a non-4401 drop")
        XCTAssertEqual(refreshCallCount, 0, "refresh must not be called for non-4401 failures")
    }
}

// MARK: - #20 VaultStore Real-Time Event Wiring Tests

@MainActor
final class VaultStoreEventWiringTests: XCTestCase {

    private func makeVault(id: String, balance: Int64) -> Vault {
        Vault(id: id, owner: "GABC", beneficiary: "GXYZ", balance: balance,
              checkInInterval: 2_592_000, lastCheckIn: Date(), ttlRemaining: 100_000, status: .active)
    }

    func test_subscribeToEvents_appliesIncomingVaultUpdateInPlace() async {
        let store = VaultStore()
        store.vaults = [makeVault(id: "vault-1", balance: 10_000_000), makeVault(id: "vault-2", balance: 20_000_000)]

        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })

        store.subscribeToEvents(vaultID: "vault-1", socket: socket)

        let updated = makeVault(id: "vault-1", balance: 99_000_000)
        socket.onEvent?(.vaultUpdated(updated))

        let applied = await waitUntil { store.vaults.first { $0.id == "vault-1" }?.balance == 99_000_000 }
        XCTAssertTrue(applied)
        // The untouched vault is left exactly as it was.
        XCTAssertEqual(store.vaults.first { $0.id == "vault-2" }?.balance, 20_000_000)
    }

    func test_unsubscribeFromEvents_stopsTheSocket() {
        let mockTask = MockWebSocketTask()
        let socket = VaultEventSocket(baseURL: URL(string: "https://api.example.com/v1")!, makeTask: { _ in mockTask })
        let store = VaultStore()

        store.subscribeToEvents(vaultID: "vault-1", socket: socket)
        store.unsubscribeFromEvents()

        XCTAssertEqual(mockTask.cancelCallCount, 1)
        XCTAssertEqual(socket.state, .disconnected)
    }
}
