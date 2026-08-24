import Foundation

/// Minimal surface of URLSessionWebSocketTask this service depends on, so tests
/// can substitute a fake task instead of a real socket connection. Mirrors
/// BackgroundRefreshTask's protocol-wrapper pattern (BackgroundRefreshService.swift).
protocol WebSocketTasking: AnyObject {
    func resume()
    func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?)
    func receive(completionHandler: @escaping (Result<URLSessionWebSocketTask.Message, Error>) -> Void)
}

extension URLSessionWebSocketTask: WebSocketTasking {}

/// Exponential backoff for WebSocket reconnect attempts, capped at `maxDelay`,
/// with randomized jitter to reduce synchronized reconnect storms.
/// Distinct from RetryPolicy (APIClient's bounded GET retry): that retries a
/// single request a few times, while this backs off an indefinite reconnect loop.
public struct ReconnectBackoff {
    let baseDelay: TimeInterval
    let maxDelay: TimeInterval
    /// Random source for jitter computation. Injected to allow deterministic testing;
    /// defaults to SystemRandomSource in production.
    let randomSource: RandomSourceProvider
    let sleep: (TimeInterval) async throws -> Void

    public static let socketDefault = ReconnectBackoff(
        baseDelay: 1.0,
        maxDelay: 30.0,
        randomSource: SystemRandomSource(),
        sleep: { seconds in try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
    )
    
    public init(baseDelay: TimeInterval, maxDelay: TimeInterval, randomSource: RandomSourceProvider, sleep: @escaping (TimeInterval) async throws -> Void) {
        self.baseDelay = baseDelay
        self.maxDelay = maxDelay
        self.randomSource = randomSource
        self.sleep = sleep
    }

    /// Delay before reconnect attempt number `attempt` (1-based): exponential backoff
    /// `baseDelay * 2^(attempt-1)` capped at `maxDelay`, with randomized jitter applied
    /// (multiply by a random factor in [0, 1)) to reduce synchronized reconnect storms.
    /// The resulting delay is always ≥ 0 and ≤ the pre-jitter exponential value.
    public func delay(forAttempt attempt: Int) -> TimeInterval {
        let baseBackoff = min(maxDelay, baseDelay * pow(2.0, Double(max(0, attempt - 1))))
        let jitter = randomSource.randomDouble()
        return baseBackoff * jitter
    }
}

/// Real-time per-vault event stream over the WebSocket documented in
/// shared/api-contract.md: `wss://.../v1/ws?vault_id={id}`. Reconnects with
/// exponential backoff on drop. After `maxReconnectAttempts` consecutive
/// failures it stops retrying and reports `.fallbackToPolling` — callers keep
/// working via VaultStore's existing pull-to-refresh / BackgroundRefreshService
/// polling, neither of which ever depended on this socket.
@MainActor
final class VaultEventSocket {

    enum ConnectionState: Equatable {
        case disconnected
        case connecting
        case connected
        case fallbackToPolling
    }

    enum VaultEvent: Equatable {
        /// Server-side vault state changed (TTL refresh, check-in, deposit, withdrawal,
        /// beneficiary change, status transition). The full updated `Vault` is embedded.
        case vaultUpdated(Vault)
        /// Vault has transitioned to `expired` status.
        case vaultExpired(vaultID: String, expiredAt: Date)
        /// Funds released to the beneficiary.
        case vaultReleased(vaultID: String, releasedAt: Date, amount: Int64)
        /// Server keepalive — no action required; clients may reply with `pong`.
        case ping
        /// Server signals a recoverable error (e.g. invalid vault_id on connect).
        case error(code: String, message: String)
        /// Unrecognised message type — ignored per api-contract.md §WebSocket
        /// ("clients should ignore unrecognized values instead of erroring").
        case unknown
    }

    private(set) var state: ConnectionState = .disconnected {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    private(set) var reconnectAttempt = 0

    var onEvent: ((VaultEvent) -> Void)?
    var onStateChange: ((ConnectionState) -> Void)?

    // Injected for testing; defaults to a real URLSessionWebSocketTask. See
    // BackgroundRefreshService.vaultListProvider for the same DI pattern.
    var makeTask: (URLRequest) -> WebSocketTasking
    var backoff: ReconnectBackoff
    let maxReconnectAttempts: Int

    private let baseURL: URL
    private let decoder: JSONDecoder
    private var task: WebSocketTasking?
    private var vaultID: String?
    private var isStopped = true
    private var reconnectTask: Task<Void, Never>?

    init(baseURL: URL,
         maxReconnectAttempts: Int = 5,
         backoff: ReconnectBackoff = .socketDefault,
         makeTask: ((URLRequest) -> WebSocketTasking)? = nil) {
        self.baseURL = baseURL
        self.maxReconnectAttempts = maxReconnectAttempts
        self.backoff = backoff
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
        self.decoder = decoder
        self.makeTask = makeTask ?? { request in URLSession.shared.webSocketTask(with: request) }
    }

    /// Connects (or reconnects from scratch, resetting the backoff counter) to
    /// `vaultID`'s event stream.
    func connect(vaultID: String) {
        self.vaultID = vaultID
        isStopped = false
        reconnectAttempt = 0
        reconnectTask?.cancel()
        openSocket()
    }

    /// Stops the stream and cancels any pending reconnect. Safe to call
    /// regardless of current state.
    func stop() {
        isStopped = true
        reconnectTask?.cancel()
        reconnectTask = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        state = .disconnected
    }

    private func openSocket() {
        guard let vaultID, !isStopped else { return }
        state = .connecting

        guard let url = Self.webSocketURL(baseURL: baseURL, vaultID: vaultID) else {
            handleFailure()
            return
        }
        var request = URLRequest(url: url)
        if let token = KeychainService.shared.loadToken() {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let newTask = makeTask(request)
        task = newTask
        newTask.resume()
        // Optimistic: resume() has no synchronous success/failure signal, so this
        // reports "connected" before the handshake is actually confirmed. Real
        // confirmation — and the point where the reconnect-attempt counter is
        // allowed to reset — happens in handleReceive() on the first message
        // actually received. Resetting it here instead would let a socket that
        // fails immediately after every resume() spin forever without ever
        // reaching maxReconnectAttempts / .fallbackToPolling.
        state = .connected
        listen()
    }

    /// Builds `wss://<host>/<path>/ws?vault_id=<id>` from an `https://` (or
    /// `ws(s)://`) base API URL — split out from openSocket() for testability.
    nonisolated static func webSocketURL(baseURL: URL, vaultID: String) -> URL? {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return nil }
        switch components.scheme {
        case "https": components.scheme = "wss"
        case "http":  components.scheme = "ws"
        default: break // already ws/wss (e.g. in tests), leave as-is
        }
        components.path = components.path.hasSuffix("/") ? components.path + "ws" : components.path + "/ws"
        components.queryItems = [URLQueryItem(name: "vault_id", value: vaultID)]
        return components.url
    }

    private func listen() {
        guard let task else { return }
        task.receive { [weak self] result in
            Task { @MainActor in
                self?.handleReceive(result)
            }
        }
    }

    private func handleReceive(_ result: Result<URLSessionWebSocketTask.Message, Error>) {
        guard !isStopped else { return }
        switch result {
        case .success(let message):
            // Any successfully received message — not just a recognized one —
            // confirms the connection is actually live, so this is where the
            // backoff counter resets (see the comment in openSocket()).
            reconnectAttempt = 0
            handle(message: message)
            listen()
        case .failure:
            handleFailure()
        }
    }

    private func handle(message: URLSessionWebSocketTask.Message) {
        let data: Data?
        switch message {
        case .data(let d): data = d
        case .string(let s): data = Data(s.utf8)
        default: data = nil
        }
        guard let data,
              let envelope = try? decoder.decode(WireEnvelope.self, from: data) else { return }
        let event: VaultEvent
        switch envelope.type {
        case "vault_updated":
            guard let msg = try? decoder.decode(WireVaultUpdated.self, from: data) else { return }
            event = .vaultUpdated(msg.vault)
        case "vault_expired":
            guard let msg = try? decoder.decode(WireVaultExpired.self, from: data) else { return }
            event = .vaultExpired(vaultID: msg.vaultID, expiredAt: msg.expiredAt)
        case "vault_released":
            guard let msg = try? decoder.decode(WireVaultReleased.self, from: data) else { return }
            event = .vaultReleased(vaultID: msg.vaultID, releasedAt: msg.releasedAt, amount: msg.amount)
        case "ping":
            event = .ping
        case "error":
            guard let msg = try? decoder.decode(WireError.self, from: data) else { return }
            event = .error(code: msg.code, message: msg.message)
        default:
            // api-contract.md: "clients should ignore unrecognized values instead of erroring"
            event = .unknown
        }
        onEvent?(event)
    }

    private func handleFailure() {
        guard !isStopped else { return }
        // Deliberately keep `task` set to the now-failed task rather than nilling it:
        // openSocket() unconditionally overwrites it once a reconnect actually opens a
        // new one, and until then stop() still needs a reference to cancel for cleanup.
        reconnectAttempt += 1
        if reconnectAttempt >= maxReconnectAttempts {
            state = .fallbackToPolling
            return
        }
        state = .disconnected
        let delay = backoff.delay(forAttempt: reconnectAttempt)
        reconnectTask = Task { @MainActor [weak self] in
            guard let self else { return }
            try? await self.backoff.sleep(delay)
            guard !Task.isCancelled, !self.isStopped else { return }
            self.openSocket()
        }
    }

    // MARK: - Wire message decoders (internal, one per server→client type)

    /// Top-level discriminator — always present in every server frame.
    private struct WireEnvelope: Decodable {
        let type: String
    }

    private struct WireVaultUpdated: Decodable {
        let vault: Vault
    }

    private struct WireVaultExpired: Decodable {
        let vaultID: String
        let expiredAt: Date
        enum CodingKeys: String, CodingKey {
            case vaultID = "vault_id"
            case expiredAt = "expired_at"
        }
    }

    private struct WireVaultReleased: Decodable {
        let vaultID: String
        let releasedAt: Date
        let amount: Int64
        enum CodingKeys: String, CodingKey {
            case vaultID = "vault_id"
            case releasedAt = "released_at"
            case amount
        }
    }

    private struct WireError: Decodable {
        let code: String
        let message: String
    }
}
