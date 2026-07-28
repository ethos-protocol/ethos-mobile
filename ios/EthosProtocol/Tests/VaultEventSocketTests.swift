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

// MARK: - #20 ReconnectBackoff Tests

final class ReconnectBackoffTests: XCTestCase {

    func test_delay_doublesWithEachAttempt() {
        let backoff = ReconnectBackoff(baseDelay: 1.0, maxDelay: 100.0, sleep: { _ in })
        XCTAssertEqual(backoff.delay(forAttempt: 1), 1.0)
        XCTAssertEqual(backoff.delay(forAttempt: 2), 2.0)
        XCTAssertEqual(backoff.delay(forAttempt: 3), 4.0)
        XCTAssertEqual(backoff.delay(forAttempt: 4), 8.0)
    }

    func test_delay_capsAtMaxDelay() {
        let backoff = ReconnectBackoff(baseDelay: 1.0, maxDelay: 5.0, sleep: { _ in })
        XCTAssertEqual(backoff.delay(forAttempt: 10), 5.0)
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
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 5,
            backoff: ReconnectBackoff(baseDelay: 1, maxDelay: 30, sleep: { _ in }),
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
        let socket = VaultEventSocket(
            baseURL: URL(string: "https://api.example.com/v1")!,
            maxReconnectAttempts: 3,
            backoff: ReconnectBackoff(baseDelay: 1, maxDelay: 30, sleep: { _ in }),
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
        let blockingBackoff = ReconnectBackoff(baseDelay: 1, maxDelay: 30, sleep: { _ in
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
