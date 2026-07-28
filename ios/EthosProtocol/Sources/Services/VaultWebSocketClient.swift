import Foundation

// MARK: - WebSocket Event Types (#110)
//
// All message types defined in shared/api-contract.md §WebSocket Message Schema.
// The server sends a discriminated JSON union keyed on "type".

enum WebSocketEvent {
    case vaultUpdated(vaultID: String, vault: Vault)
    case vaultExpired(vaultID: String, expiredAt: Date)
    case vaultReleased(vaultID: String, releasedAt: Date, amount: Int64)
    case ping
    case error(code: String, message: String)
    case disconnected(closeCode: URLSessionWebSocketTask.CloseCode)
}

// MARK: - Raw server message envelopes (internal, for decoding only)

private struct WSEnvelope: Decodable {
    let type: String
}

private struct WSVaultUpdatedMessage: Decodable {
    let vaultID: String
    let vault: Vault
    enum CodingKeys: String, CodingKey {
        case vaultID = "vault_id"
        case vault
    }
}

private struct WSVaultExpiredMessage: Decodable {
    let vaultID: String
    let expiredAt: Date
    enum CodingKeys: String, CodingKey {
        case vaultID = "vault_id"
        case expiredAt = "expired_at"
    }
}

private struct WSVaultReleasedMessage: Decodable {
    let vaultID: String
    let releasedAt: Date
    let amount: Int64
    enum CodingKeys: String, CodingKey {
        case vaultID = "vault_id"
        case releasedAt = "released_at"
        case amount
    }
}

private struct WSErrorMessage: Decodable {
    let code: String
    let message: String
}

// MARK: - VaultWebSocketClient

/// Stub WebSocket client for real-time vault events (#110).
///
/// Usage:
/// ```swift
/// let ws = VaultWebSocketClient(vaultID: "vault-123", token: authToken)
/// for await event in ws.events {
///     switch event { ... }
/// }
/// ```
///
/// Reconnect policy: exponential backoff starting at 1 s, capped at 60 s.
/// Authentication failures (server close code 4401) are not retried.
///
/// NOTE: This is a stub — the event loop, backoff, and message parsing are
/// structured correctly but no staging WebSocket endpoint exists yet. Connect
/// once one is available and replace the TODO comments below.
final class VaultWebSocketClient {
    private let vaultID: String
    private let baseURL: URL
    private let session: URLSession
    private let decoder: JSONDecoder

    // Reconnect backoff: doubles each attempt, capped at maxBackoff.
    private let baseBackoff: TimeInterval = 1
    private let maxBackoff: TimeInterval = 60

    init(vaultID: String, baseURL: URL? = nil, session: URLSession = .shared) {
        self.vaultID = vaultID
        let base = baseURL ?? URL(string:
            (Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String
             ?? "https://api.ethos-protocol.app/v1")
            .replacingOccurrences(of: "https://", with: "wss://")
            .replacingOccurrences(of: "http://", with: "ws://")
        )!
        self.baseURL = base
        self.session = session
        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
    }

    /// Async stream of WebSocket events. Reconnects automatically unless the
    /// server closes with code 4401 (auth failure).
    var events: AsyncStream<WebSocketEvent> {
        AsyncStream { continuation in
            Task {
                var backoff = baseBackoff
                while !Task.isCancelled {
                    do {
                        try await runSession(continuation: continuation)
                        // Clean close — stop reconnecting.
                        break
                    } catch {
                        // TODO: differentiate auth failure (4401) from transient errors
                        // once the staging endpoint is available. For now, always retry.
                        continuation.yield(.error(
                            code: "reconnect",
                            message: "WebSocket disconnected, retrying in \(Int(backoff))s"
                        ))
                        try await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
                        backoff = min(backoff * 2, maxBackoff)
                    }
                }
                continuation.finish()
            }
        }
    }

    // MARK: - Private

    private func runSession(continuation: AsyncStream<WebSocketEvent>.Continuation) async throws {
        guard var urlComponents = URLComponents(url: baseURL.appendingPathComponent("ws"),
                                                resolvingAgainstBaseURL: false) else {
            throw URLError(.badURL)
        }
        urlComponents.queryItems = [URLQueryItem(name: "vault_id", value: vaultID)]
        guard let url = urlComponents.url else { throw URLError(.badURL) }

        var request = URLRequest(url: url)
        if let token = KeychainService.shared.loadToken() {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let task = session.webSocketTask(with: request)
        task.resume()

        // TODO: send `pong` in response to server `ping` once staging endpoint is live.
        defer { task.cancel(with: .normalClosure, reason: nil) }

        while !Task.isCancelled {
            let message = try await task.receive()
            switch message {
            case .string(let text):
                guard let data = text.data(using: .utf8) else { continue }
                if let event = parseEvent(data) { continuation.yield(event) }
            case .data(let data):
                if let event = parseEvent(data) { continuation.yield(event) }
            @unknown default:
                break
            }
        }
    }

    private func parseEvent(_ data: Data) -> WebSocketEvent? {
        guard let envelope = try? decoder.decode(WSEnvelope.self, from: data) else { return nil }
        switch envelope.type {
        case "vault_updated":
            guard let msg = try? decoder.decode(WSVaultUpdatedMessage.self, from: data) else { return nil }
            return .vaultUpdated(vaultID: msg.vaultID, vault: msg.vault)
        case "vault_expired":
            guard let msg = try? decoder.decode(WSVaultExpiredMessage.self, from: data) else { return nil }
            return .vaultExpired(vaultID: msg.vaultID, expiredAt: msg.expiredAt)
        case "vault_released":
            guard let msg = try? decoder.decode(WSVaultReleasedMessage.self, from: data) else { return nil }
            return .vaultReleased(vaultID: msg.vaultID, releasedAt: msg.releasedAt, amount: msg.amount)
        case "ping":
            return .ping
        case "error":
            guard let msg = try? decoder.decode(WSErrorMessage.self, from: data) else { return nil }
            return .error(code: msg.code, message: msg.message)
        default:
            return nil
        }
    }
}
