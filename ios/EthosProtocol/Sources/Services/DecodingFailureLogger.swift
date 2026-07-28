import Foundation

/// Logs APIClient response-decoding failures for triage — mirrors DeepLinkLogger's
/// thread-safe, in-memory event log pattern. Response bodies are redacted of
/// known-sensitive fields before being logged, since a decode failure means the
/// body is by definition unexpected/unvalidated content.
final class DecodingFailureLogger {
    static let shared = DecodingFailureLogger()
    private init() {}

    private var eventLog: [DecodingFailureEntry] = []
    private let queue = DispatchQueue(label: "com.ethos-protocol.decoding-failure-logger", attributes: .concurrent)

    /// JSON keys (case-insensitive) whose values are replaced with "[REDACTED]"
    /// before a response body is logged.
    private static let sensitiveKeys: Set<String> = [
        "token", "credential_id", "public_key", "client_data_json", "signature",
        "secret", "otp", "password", "authorization", "provisioning_uri"
    ]

    /// Logs a decode failure for `path` where a `expectedType` was expected but
    /// couldn't be decoded from `responseBody`.
    func log(path: String, expectedType: String, responseBody: Data) {
        let entry = DecodingFailureEntry(
            path: path,
            expectedType: expectedType,
            redactedBody: Self.redact(responseBody),
            timestamp: Date()
        )
        queue.async(flags: .barrier) {
            self.eventLog.append(entry)
        }
    }

    /// Retrieves all logged events
    func getLoggedEvents() -> [DecodingFailureEntry] {
        queue.sync { self.eventLog }
    }

    /// Clears all logged events
    func clearLog() {
        queue.async(flags: .barrier) {
            self.eventLog.removeAll()
        }
    }

    /// Redacts values for known-sensitive keys from a JSON response body while
    /// preserving structure, so the shape of an unexpected response stays visible
    /// for triage without leaking tokens/credentials/secrets into logs.
    static func redact(_ data: Data) -> String {
        guard let json = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else {
            return "<non-JSON response, \(data.count) bytes>"
        }
        let redacted = redactValue(json)
        // JSONSerialization.data(withJSONObject:) requires a top-level array/dictionary;
        // wrap bare scalars (a response body that's just `"oops"` or `42`) so they can
        // still be serialized back out for logging.
        let wrapped: Any = (redacted is [String: Any] || redacted is [Any]) ? redacted : ["value": redacted]
        guard let redactedData = try? JSONSerialization.data(withJSONObject: wrapped, options: [.sortedKeys]),
              let string = String(data: redactedData, encoding: .utf8) else {
            return "<unrepresentable response>"
        }
        return string
    }

    private static func redactValue(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            var result: [String: Any] = [:]
            for (key, v) in dict {
                result[key] = sensitiveKeys.contains(key.lowercased()) ? "[REDACTED]" : redactValue(v)
            }
            return result
        } else if let array = value as? [Any] {
            return array.map(redactValue)
        } else {
            return value
        }
    }
}

/// A single entry in the decode-failure log
struct DecodingFailureEntry: Codable {
    let path: String
    let expectedType: String
    let redactedBody: String
    let timestamp: Date
}
