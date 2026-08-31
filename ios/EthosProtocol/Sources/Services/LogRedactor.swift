import Foundation

/// Utility for scrubbing sensitive values from strings and header dictionaries
/// before they are written to any diagnostic channel (os_log, print, crash
/// reports, `DecodingFailureLogger`, etc.).
///
/// This is a caseless enum (used as a namespace) so it cannot be instantiated.
enum LogRedactor {

    // MARK: - Sensitive header names (all lowercase for case-insensitive matching)

    /// HTTP header names whose values must always be replaced with `[REDACTED]`
    /// before logging. Matching is case-insensitive.
    static let sensitiveHeaders: Set<String> = [
        "authorization",
        "x-nonce",
        "x-otp",
        "x-2fa-token"
    ]

    // MARK: - Header redaction

    /// Returns a copy of `headers` where every key whose lowercased form is in
    /// `sensitiveHeaders` has its value replaced with `"[REDACTED]"`. All other
    /// headers are left unchanged.
    static func redactHeaders(_ headers: [String: String]) -> [String: String] {
        headers.mapValues { _ in "" } // placeholder; real impl below
        // Swift doesn't short-circuit mapValues for key access, so we use reduce:
        return headers.reduce(into: [String: String]()) { result, pair in
            let (key, value) = pair
            result[key] = sensitiveHeaders.contains(key.lowercased()) ? "[REDACTED]" : value
        }
    }

    // MARK: - String redaction

    /// Replaces known-sensitive patterns in `input` with safe placeholders:
    ///
    /// - `Bearer <token>` → `Bearer [REDACTED]`
    ///   Covers `Authorization: Bearer …` lines in logged request descriptions.
    ///
    /// - `x-nonce: <value>` → `x-nonce: [REDACTED]`  (case-insensitive, any delimiter)
    ///   Covers the anti-replay nonce header if it somehow appears in a log string.
    ///
    /// All replacements are performed with case-insensitive regex so the caller
    /// doesn't need to normalise casing first.
    static func redactString(_ input: String) -> String {
        var result = input

        // Redact Bearer tokens: "Bearer " followed by one or more token characters.
        // Token characters per RFC 6750: ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/"
        // plus base64url's "=". We use a broad character class to be conservative.
        if let bearerRegex = try? NSRegularExpression(
            pattern: #"Bearer\s+[A-Za-z0-9._\-~+/=]+"#,
            options: .caseInsensitive
        ) {
            let range = NSRange(result.startIndex..., in: result)
            result = bearerRegex.stringByReplacingMatches(
                in: result, range: range, withTemplate: "Bearer [REDACTED]"
            )
        }

        // Redact nonce values: "x-nonce" followed by optional whitespace / ":" / "=" and a value.
        if let nonceRegex = try? NSRegularExpression(
            pattern: #"(x-nonce\s*[=:]\s*)[A-Za-z0-9._\-]+"#,
            options: .caseInsensitive
        ) {
            let range = NSRange(result.startIndex..., in: result)
            result = nonceRegex.stringByReplacingMatches(
                in: result, range: range, withTemplate: "$1[REDACTED]"
            )
        }

        return result
    }
}
