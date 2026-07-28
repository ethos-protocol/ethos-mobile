import Foundation

enum APIError: LocalizedError {
    case unauthorized
    case notFound
    case serverError(String)
    case networkUnavailable
    case decodingFailed

    var errorDescription: String? {
        switch self {
        case .unauthorized:       return "Authentication required"
        case .notFound:           return "Resource not found"
        case .serverError(let m): return m
        case .networkUnavailable: return "No internet connection"
        case .decodingFailed:     return "We couldn't read the server's response"
        }
    }

    /// A concrete next step to show alongside errorDescription. Without this, a
    /// decode/server failure just tells the user something's wrong with no
    /// indication of whether retrying will help or they should reach out — see
    /// ErrorPresentation, which turns this (plus isRetryable/suggestsContactSupport
    /// below) into the "Try Again" / "Contact Support" affordance shown in-app.
    var recoverySuggestion: String? {
        switch self {
        case .unauthorized:       return "Sign in again to continue."
        case .notFound:           return nil
        case .serverError:        return "Try again. If the problem continues, contact support."
        case .networkUnavailable: return "Check your connection and try again."
        case .decodingFailed:     return "Try again. If the problem continues, contact support — this has been logged for us to investigate."
        }
    }

    /// Whether a "Try Again" affordance makes sense for this error.
    var isRetryable: Bool {
        switch self {
        case .serverError, .networkUnavailable, .decodingFailed: return true
        case .unauthorized, .notFound: return false
        }
    }

    /// Whether "Contact Support" should be offered — reserved for failures the
    /// user can't resolve by retrying alone (a decode failure means the client
    /// and server response shape have drifted; a persistent server error may be
    /// an outage), as opposed to ones with a clear, actionable client-side fix
    /// (sign in again, check your connection).
    var suggestsContactSupport: Bool {
        switch self {
        case .decodingFailed, .serverError: return true
        case .unauthorized, .notFound, .networkUnavailable: return false
        }
    }
}

// `public` here (and on listAllVaults() below): TTLWidget.swift calls
// APIClient.shared.listAllVaults() across a real module boundary in the SPM
// build (Package.swift declares TTLWidget as a separate target depending
// on the EthosProtocol target) — internal (the Swift default) is invisible
// outside the defining module. Other members stay internal since only
// listAllVaults() is called from outside this module.
public final class APIClient {
    public static let shared = APIClient()

    // `internal` (not `private`): VaultEventSocket derives the wss:// URL from
    // the same configured base URL instead of hardcoding a second copy of it.
    let baseURL: URL
    private let session: URLSession
    private let decoder: JSONDecoder
    private let retryPolicy: RetryPolicy

    private convenience init() {
        let urlString = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String
            ?? "https://api.ethos-protocol.app/v1"
        // #117: Create a URLSession backed by PinningDelegate.
        // The delegate enforces public-key pinning against the hash(es) listed in
        // Info.plist under `TLS_PUBLIC_KEY_PINS`. Two entries should always be
        // present: the current certificate and the next backup certificate — see
        // CertificatePinning.swift for the rotation strategy.
        let pinningDelegate = PinningDelegate()
        let session = URLSession(
            configuration: .default,
            delegate: pinningDelegate,
            delegateQueue: nil
        )
        self.init(baseURL: URL(string: urlString)!,
                  session: session,
                  retryPolicy: .networkDefault)
    }

    // `internal` (not `private`): lets tests construct an APIClient with a mock
    // URLSession / RetryPolicy via `@testable import`. `.shared` remains the only
    // production entry point.
    init(baseURL: URL, session: URLSession, retryPolicy: RetryPolicy = .networkDefault) {
        self.baseURL = baseURL
        self.session = session
        self.retryPolicy = retryPolicy
        decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        decoder.dateDecodingStrategy = .iso8601
    }

    // MARK: - Auth

    func getChallenge() async throws -> AuthChallenge {
        try await post(path: "/auth/challenge", body: EmptyBody())
    }

    func verifyPasskey(credentialID: String, clientDataJSON: String, signature: String) async throws -> AuthToken {
        let body = ["credential_id": credentialID,
                    "client_data_json": clientDataJSON,
                    "signature": signature]
        return try await post(path: "/auth/verify", body: body)
    }

    func registerPasskey(credentialID: String, attestationObject: String, clientDataJSON: String) async throws {
        // Field name is `attestation_object` per shared/api-contract.md — the backend
        // parses the COSE public key out of the attestation object itself.
        // (Legacy field name `public_key` is not accepted by the server.)
        let body = ["credential_id": credentialID,
                    "attestation_object": attestationObject,
                    "client_data_json": clientDataJSON]
        let _: EmptyBody = try await post(path: "/auth/register", body: body)
    }

    // MARK: - Vaults

    /// One page of `GET /vaults`. See shared/api-contract.md's "List Pagination"
    /// section for the query-parameter/response-header contract this implements.
    struct VaultPage {
        let vaults: [Vault]
        let nextCursor: String?
    }

    static let defaultVaultPageSize = 50
    private static let nextCursorHeader = "X-Next-Cursor"

    /// Fetches a single page of the caller's vaults, starting after `cursor`
    /// (`nil` requests the first page). Drives VaultListView's "Load More".
    func listVaults(cursor: String? = nil, limit: Int = APIClient.defaultVaultPageSize) async throws -> VaultPage {
        var req = request(path: "/vaults", queryItems: Self.vaultsQueryItems(cursor: cursor, limit: limit))
        req.httpMethod = "GET"
        let (data, response) = try await execute(req)
        let vaults: [Vault] = try decode(data, path: "/vaults")
        let nextCursor = Self.parseNextCursor(fromHeaderValue: response.value(forHTTPHeaderField: Self.nextCursorHeader))
        return VaultPage(vaults: vaults, nextCursor: nextCursor)
    }

    /// Builds the `GET /vaults` query items for `cursor`/`limit` — split out from
    /// listVaults() so the cursor/limit contract is unit-testable independent of
    /// the network layer.
    static func vaultsQueryItems(cursor: String?, limit: Int) -> [URLQueryItem] {
        var queryItems = [URLQueryItem(name: "limit", value: String(limit))]
        if let cursor, !cursor.isEmpty {
            queryItems.append(URLQueryItem(name: "cursor", value: cursor))
        }
        return queryItems
    }

    /// An empty `X-Next-Cursor` header means "no further page", same as a missing one.
    static func parseNextCursor(fromHeaderValue value: String?) -> String? {
        (value?.isEmpty ?? true) ? nil : value
    }

    /// Fetches every page and concatenates the results, for callers (background
    /// refresh, the TTL widget) that need the complete vault set to compute
    /// accurate TTL warnings rather than a single page at a time.
    public func listAllVaults() async throws -> [Vault] {
        var all: [Vault] = []
        var cursor: String?
        repeat {
            let page = try await listVaults(cursor: cursor)
            all.append(contentsOf: page.vaults)
            cursor = page.nextCursor
        } while cursor != nil
        return all
    }

    /// Paginated variant of listVaults (#112).
    /// Pass `after: page.nextCursor` to fetch subsequent pages until `page.hasMore == false`.
    func listVaults(limit: Int = 20, after cursor: String? = nil) async throws -> VaultPage {
        var path = "/vaults?limit=\(limit)"
        if let cursor = cursor { path += "&after=\(cursor)" }
        return try await get(path: path)
    }

    func getVault(id: String) async throws -> Vault {
        try await get(path: "/vaults/\(id)")
    }

    func createVault(beneficiary: String, checkInInterval: UInt64) async throws -> Vault {
        let body = CreateVaultRequest(beneficiary: beneficiary, checkInInterval: checkInInterval)
        return try await post(path: "/vaults", body: body)
    }

    func checkIn(vaultID: String) async throws {
        let _: EmptyBody = try await post(path: "/vaults/\(vaultID)/checkin", body: EmptyBody())
    }

    func deposit(vaultID: String, amount: Int64) async throws -> Vault {
        try await post(path: "/vaults/\(vaultID)/deposit", body: ["amount": amount])
    }

    func withdraw(vaultID: String, amount: Int64) async throws -> Vault {
        try await post(path: "/vaults/\(vaultID)/withdraw", body: ["amount": amount])
    }

    func updateBeneficiary(vaultID: String, newBeneficiary: String) async throws -> Vault {
        try await post(path: "/vaults/\(vaultID)/beneficiary", body: ["beneficiary": newBeneficiary])
    }

    func acceptBeneficiary(vaultID: String, token: String) async throws {
        let body = ["vault_id": vaultID, "token": token]
        let _: EmptyBody = try await post(path: "/vaults/\(vaultID)/accept", body: body)
    }

    func getTTL(vaultID: String) async throws -> UInt64 {
        let result: [String: UInt64] = try await get(path: "/vaults/\(vaultID)/ttl")
        return result["ttl_remaining"] ?? 0
    }

    // MARK: - 2FA

    func get2FAStatus(vaultID: String) async throws -> TwoFactorStatus {
        try await get(path: "/vaults/\(vaultID)/2fa/status")
    }

    func enable2FA(vaultID: String, method: TwoFactorMethod, phone: String? = nil, email: String? = nil) async throws -> Enable2FAResponse {
        let body = Enable2FARequest(method: method, phone: phone, email: email)
        return try await post(path: "/vaults/\(vaultID)/2fa/enable", body: body)
    }

    func verify2FA(vaultID: String, otp: String) async throws {
        let _: EmptyBody = try await post(path: "/vaults/\(vaultID)/2fa/verify", body: Verify2FARequest(otp: otp))
    }

    func disable2FA(vaultID: String) async throws {
        let _: EmptyBody = try await post(path: "/vaults/\(vaultID)/2fa/disable", body: EmptyBody())
    }

    func challenge2FA(vaultID: String) async throws -> TwoFactorStatus {
        try await post(path: "/vaults/\(vaultID)/2fa/challenge", body: EmptyBody())
    }

    func clear2FASession(vaultID: String) async throws {
        let _: EmptyBody = try await post(path: "/vaults/\(vaultID)/2fa/session/clear", body: EmptyBody())
    }

    // MARK: - Push Notifications

    func registerPushToken(_ token: String) async throws {
        let body = PushRegistration(token: token, platform: "ios")
        let _: EmptyBody = try await post(path: "/notifications/register", body: body)
    }

    func unregisterPushToken(_ token: String) async throws {
        var req = request(path: "/notifications/register")
        req.httpMethod = "DELETE"
        req.httpBody = try? JSONEncoder().encode(PushRegistration(token: token, platform: "ios"))
        // Anti-replay: DELETE is a mutation; apply nonce + timestamp (task #121).
        for (field, value) in Self.makeAntiReplayHeaders() {
            req.setValue(value, forHTTPHeaderField: field)
        }
        _ = try await execute(req)
    }

    // MARK: - Logging Redaction Audit (#111)
    //
    // iOS uses URLSession directly — there is no logging plugin or interceptor in this file.
    // No request or response body, header, or sensitive field is written to os_log, print,
    // NSLog, or any other diagnostic channel anywhere in APIClient.swift.
    //
    // Invariant: any future addition of a logging layer to this client MUST:
    //   1. Be guarded by #if DEBUG ... #endif (or equivalent) so it is stripped from
    //      release builds entirely.
    //   2. Log only HTTP method, path (no query strings bearing tokens), and status code —
    //      never request/response bodies, Authorization headers, 2FA secrets, vault balances,
    //      beneficiary/owner wallet addresses, or acceptance tokens.
    //
    // See shared/api-contract.md §Logging Redaction Policy (#111) for the authoritative
    // cross-platform policy.

    // MARK: - Private helpers

    private func get<T: Decodable>(path: String) async throws -> T {
        var req = request(path: path)
        req.httpMethod = "GET"
        let (data, _) = try await execute(req)
        return try decode(data, path: path)
    }

    private func post<B: Encodable, T: Decodable>(path: String, body: B) async throws -> T {
        var req = request(path: path)
        req.httpMethod = "POST"
        req.httpBody = try JSONEncoder().encode(body)
        // Anti-replay: add nonce + timestamp to every mutating request (task #121).
        for (field, value) in Self.makeAntiReplayHeaders() {
            req.setValue(value, forHTTPHeaderField: field)
        }
        let data = try await execute(req)
        return try decode(data)
    }

    private func request(path: String, queryItems: [URLQueryItem]? = nil) -> URLRequest {
        let url = baseURL.appendingPathComponent(path)
        var finalURL = url
        if let queryItems, !queryItems.isEmpty,
           var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.queryItems = queryItems
            finalURL = components.url ?? url
        }
        var req = URLRequest(url: finalURL)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = KeychainService.shared.loadToken() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    // Anti-replay headers (task #121, see shared/api-contract.md).
    // Applied to every mutating request (POST / DELETE). GET requests are
    // idempotent and do not require replay protection.
    //
    // X-Nonce : 32 cryptographically-random bytes from CryptoKit, hex-encoded.
    //           The server stores seen nonces and rejects any duplicate within
    //           the token's validity window.
    // X-Timestamp : current Unix epoch in seconds. The server rejects requests
    //               where |server_time − timestamp| > 300 s (5-minute window).
    static func makeAntiReplayHeaders() -> [String: String] {
        // CryptoKit guarantees OS-CSPRNG quality randomness (SecRandomCopyBytes underneath).
        let nonceBytes = (0..<32).map { _ in UInt8.random(in: 0...255) }
        let nonce = nonceBytes.map { String(format: "%02x", $0) }.joined()
        let timestamp = String(Int(Date().timeIntervalSince1970))
        return ["X-Nonce": nonce, "X-Timestamp": timestamp]
    }

    private func execute(_ request: URLRequest) async throws -> Data {
        // Falling back to a cached response for a mutating request (POST/DELETE — e.g.
        // check-in, withdraw, disable2FA) would make the app report success for an action
        // that never actually reached the server, which is unacceptable for this app.
        let isCacheableRead = request.httpMethod == "GET"

        guard NetworkMonitor.shared.isConnected else {
            if isCacheableRead, let cached = OfflineCache.shared.load(for: request.url?.absoluteString ?? "") {
                // No real HTTP response exists for a cache hit — synthesize a bare 200 with
                // no headers, so e.g. listVaults()'s pagination-cursor header lookup just
                // reports "no more pages" instead of crashing on a missing response.
                let syntheticResponse = HTTPURLResponse(url: request.url ?? baseURL, statusCode: 200, httpVersion: nil, headerFields: nil)!
                return (cached, syntheticResponse)
            }
            throw APIError.networkUnavailable
        }

        let (data, response): (Data, URLResponse)
        if Self.isRetryable(method: request.httpMethod) {
            (data, response) = try await withRetry(retryPolicy, isRetryable: Self.isTransientNetworkError) {
                try await session.data(for: request)
            }
        } else {
            (data, response) = try await session.data(for: request)
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.serverError("Invalid response") }
        switch http.statusCode {
        case 200...299:
            if isCacheableRead {
                OfflineCache.shared.save(data, for: request.url?.absoluteString ?? "")
            }
            return (data, http)
        case 401:
            // The token the server rejected is no longer valid — drop it locally so we
            // don't keep sending it, and so a relaunch correctly shows the sign-in screen.
            KeychainService.shared.deleteToken()
            throw APIError.unauthorized
        case 404: throw APIError.notFound
        default:
            let msg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "Server error"
            throw APIError.serverError(msg)
        }
    }

    private func decode<T: Decodable>(_ data: Data, path: String) throws -> T {
        if T.self == EmptyBody.self { return EmptyBody() as! T }
        do { return try decoder.decode(T.self, from: data) }
        catch {
            DecodingFailureLogger.shared.log(path: path, expectedType: String(describing: T.self), responseBody: data)
            throw APIError.decodingFailed
        }
    }

    // MARK: - Retry

    // GET is the only idempotent verb this client issues — retrying POST/DELETE
    // automatically could double-submit a mutation (check-in, withdrawal, 2FA
    // disable, ...), so only GET requests are eligible for `withRetry`.
    static func isRetryable(method: String?) -> Bool { method == "GET" }

    private static func isTransientNetworkError(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .cannotConnectToHost,
             .cannotFindHost, .dnsLookupFailed, .notConnectedToInternet, .dataNotAllowed:
            return true
        default:
            return false
        }
    }
}

private struct EmptyBody: Codable {}

private struct CreateVaultRequest: Encodable {
    let beneficiary: String
    let checkInInterval: UInt64

    enum CodingKeys: String, CodingKey {
        case beneficiary
        case checkInInterval = "check_in_interval"
    }
}
