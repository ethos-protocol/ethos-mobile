import Foundation
import XCTest

// MARK: - MockHTTPInterceptor
//
// A reusable URLProtocol subclass that gives tests fine-grained control over
// network responses without making real network calls.
//
// Design goals:
//   • URL-pattern → response mapping with wildcard support
//   • Delay injection for timeout / slow-network scenarios
//   • Request recording for post-hoc assertion
//   • Simple reset() between test cases
//
// Usage:
//   1. Register handlers before making requests:
//        MockHTTPInterceptor.register(
//            pattern: "/vaults",
//            response: APIFixtures.vaultListResponse()
//        )
//   2. Create a URLSession backed by this protocol:
//        let session = MockHTTPInterceptor.makeSession()
//   3. Pass the session to APIClient.makeTestInstance(session:)
//   4. After the test, call MockHTTPInterceptor.reset()
//
// Thread safety: all mutable state is guarded by a serial DispatchQueue so the
// handler table and recorded-request list can be written from setUp() on the main
// thread while URLProtocol callbacks arrive on background URLSession threads.

/// The data needed to synthesize a complete HTTP response.
struct MockResponse {
    let statusCode: Int
    let headers: [String: String]
    let body: Data
    /// Optional delay before delivering the response.  Use this to exercise
    /// timeout paths: set delay > URLRequest.timeoutInterval to trigger a
    /// URLError(.timedOut) via `MockHTTPInterceptor.registerTimeout(pattern:)`.
    let delay: TimeInterval

    init(statusCode: Int = 200,
         headers: [String: String] = ["Content-Type": "application/json"],
         body: Data = Data(),
         delay: TimeInterval = 0) {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.delay = delay
    }

    init(statusCode: Int = 200,
         headers: [String: String] = ["Content-Type": "application/json"],
         json: String,
         delay: TimeInterval = 0) {
        self.init(
            statusCode: statusCode,
            headers: headers,
            body: Data(json.utf8),
            delay: delay
        )
    }
}

/// A recorded snapshot of one intercepted request, available for assertion.
struct RecordedRequest {
    let url: URL
    let method: String
    let headers: [String: String]
    let body: Data?
    let timestamp: Date

    init(from urlRequest: URLRequest) {
        self.url = urlRequest.url ?? URL(string: "about:blank")!
        self.method = urlRequest.httpMethod ?? "GET"
        // HTTPHeaderField keys come back as lowercase from URLRequest on some
        // runtimes; normalise to lowercase so assertions are case-insensitive.
        self.headers = Dictionary(
            uniqueKeysWithValues: (urlRequest.allHTTPHeaderFields ?? [:])
                .map { ($0.key.lowercased(), $0.value) }
        )
        self.body = urlRequest.httpBody
        self.timestamp = Date()
    }
}

/// A URL pattern against which incoming request URLs are matched.
///
/// - exact: the full absolute URL string must equal the pattern string.
/// - contains: the URL string just needs to contain the substring (handy for
///   paths like "/vaults/vault-123/checkin" when the vault ID varies).
/// - regex: the URL string must match the provided NSRegularExpression pattern.
enum URLPattern {
    case exact(String)
    case contains(String)
    case regex(String)

    func matches(_ url: URL) -> Bool {
        let absolute = url.absoluteString
        switch self {
        case .exact(let s):
            return absolute == s
        case .contains(let s):
            return absolute.contains(s)
        case .regex(let pattern):
            return (try? NSRegularExpression(pattern: pattern))
                .map { $0.firstMatch(in: absolute, range: NSRange(absolute.startIndex..., in: absolute)) != nil }
                ?? false
        }
    }
}

// MARK: - Handler Entry

private struct HandlerEntry {
    let pattern: URLPattern
    let response: MockResponse?   // nil → inject error instead
    let error: Error?
}

// MARK: - MockHTTPInterceptor

final class MockHTTPInterceptor: URLProtocol {

    // MARK: Shared mutable state (guarded by `queue`)

    private static let queue = DispatchQueue(label: "com.ethosprotocol.tests.MockHTTPInterceptor")
    private static var handlers: [HandlerEntry] = []
    private static var _recordedRequests: [RecordedRequest] = []

    /// All requests intercepted since the last `reset()`, in arrival order.
    static var recordedRequests: [RecordedRequest] {
        queue.sync { _recordedRequests }
    }

    // MARK: Registration API

    /// Register a URL pattern → mock response mapping.
    /// Handlers are evaluated in registration order; the first match wins.
    static func register(pattern: URLPattern, response: MockResponse) {
        queue.async {
            handlers.append(HandlerEntry(pattern: pattern, response: response, error: nil))
        }
    }

    /// Register a URL pattern that always returns an error (e.g. network failure).
    static func register(pattern: URLPattern, error: Error) {
        queue.async {
            handlers.append(HandlerEntry(pattern: pattern, response: nil, error: error))
        }
    }

    /// Convenience: register an exact URL → response mapping.
    static func register(url: String, response: MockResponse) {
        register(pattern: .exact(url), response: response)
    }

    /// Convenience: register an exact URL → error mapping.
    static func register(url: String, error: Error) {
        register(pattern: .exact(url), error: error)
    }

    /// Register a timeout for the given pattern by injecting NSURLErrorTimedOut.
    /// Tests that exercise timeout handling should use this rather than a real delay
    /// to keep the test suite fast; the delay on `MockResponse` is for verifying
    /// that the *caller* enforces its own deadline independently of the server.
    static func registerTimeout(pattern: URLPattern) {
        let error = URLError(.timedOut)
        register(pattern: pattern, error: error)
    }

    /// Register a timeout for an exact URL.
    static func registerTimeout(url: String) {
        registerTimeout(pattern: .exact(url))
    }

    /// Remove all registered handlers and recorded requests.
    static func reset() {
        queue.async {
            handlers.removeAll()
            _recordedRequests.removeAll()
        }
        // Drain the queue synchronously so the next test starts from a clean state.
        queue.sync {}
    }

    // MARK: URLSession factory

    /// Returns a URLSession whose requests are handled exclusively by this interceptor.
    /// Pass the result to `APIClient.makeTestInstance(session:)`.
    static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockHTTPInterceptor.self]
        // Short timeout so delay-injection tests complete in reasonable time.
        config.timeoutIntervalForRequest = 2
        return URLSession(configuration: config)
    }

    // MARK: URLProtocol overrides

    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }

        // Record the request.
        let recorded = RecordedRequest(from: request)
        MockHTTPInterceptor.queue.async {
            MockHTTPInterceptor._recordedRequests.append(recorded)
        }

        // Find the first matching handler.
        var matchedEntry: HandlerEntry?
        MockHTTPInterceptor.queue.sync {
            matchedEntry = MockHTTPInterceptor.handlers.first { $0.pattern.matches(url) }
        }

        guard let entry = matchedEntry else {
            // No handler → 404 to keep tests deterministic.
            let response = HTTPURLResponse(
                url: url,
                statusCode: 404,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            let body = Data(#"{"error":"MockHTTPInterceptor: no handler registered for this URL"}"#.utf8)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
            return
        }

        if let error = entry.error {
            // Simulate the delay even for errors (e.g. slow-timeout scenario).
            let delay = (entry.response?.delay ?? 0)
            if delay > 0 {
                Thread.sleep(forTimeInterval: delay)
            }
            client?.urlProtocol(self, didFailWithError: error)
            return
        }

        guard let mockResponse = entry.response else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }

        if mockResponse.delay > 0 {
            Thread.sleep(forTimeInterval: mockResponse.delay)
        }

        let httpResponse = HTTPURLResponse(
            url: url,
            statusCode: mockResponse.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: mockResponse.headers
        )!

        client?.urlProtocol(self, didReceive: httpResponse, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: mockResponse.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
