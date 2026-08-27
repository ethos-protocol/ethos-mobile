import Foundation

// `public` here (and on the members below): TTLWidget.swift's WidgetKit
// timeline provider reads Vault fields from APIClient.listAllVaults() across
// a real module boundary in the SPM build (Package.swift declares TTLWidget
// as a separate target depending on the EthosProtocol target) — internal
// (the Swift default) is invisible outside the defining module.
public struct Vault: Codable, Identifiable, Equatable {
    public let id: String
    public let owner: String
    public let beneficiary: String
    public let balance: Int64
    public let checkInInterval: UInt64
    public let lastCheckIn: Date
    public let ttlRemaining: UInt64?
    public let status: VaultStatus

    public enum VaultStatus: String, Codable {
        case active, expired, released, paused
    }

    public var isExpiringSoon: Bool {
        guard let ttl = ttlRemaining else { return false }
        return ttl < 86_400 // < 24 hours
    }

    public var formattedBalance: String {
        let xlm = Double(balance) / 10_000_000
        return String(format: "%.7f XLM", xlm)
    }
}

// Shared amount parsing/validation for the deposit and withdraw flows, which both
// take a user-entered XLM string and need to convert/validate it against the
// vault's stroop-denominated balance the same way.
enum VaultAmount {
    static let stroopsPerXLM = 10_000_000.0

    /// Parses a user-entered XLM amount string into stroops, or nil if the input
    /// isn't a positive, finite number.
    static func parseStroops(_ input: String) -> Int64? {
        guard let value = Double(input), value.isFinite, value > 0 else { return nil }
        let stroops = value * stroopsPerXLM
        guard stroops <= Double(Int64.max) else { return nil }
        return Int64(stroops)
    }

    static func hasSufficientBalance(amount: Int64, vaultBalance: Int64) -> Bool {
        amount > 0 && amount <= vaultBalance
    }
}

/// Structured, user-facing presentation of an error — derived from APIError when
/// possible so decode/server failures surface a concrete next step ("Try Again" /
/// "Contact Support") instead of a bare message with no available action.
struct ErrorPresentation: Equatable {
    let message: String
    let recoverySuggestion: String?
    let showsRetry: Bool
    let showsContactSupport: Bool

    init(_ error: Error) {
        if let apiError = error as? APIError {
            message = apiError.errorDescription ?? "Something went wrong"
            recoverySuggestion = apiError.recoverySuggestion
            showsRetry = apiError.isRetryable
            showsContactSupport = apiError.suggestsContactSupport
        } else {
            message = error.localizedDescription
            recoverySuggestion = nil
            showsRetry = false
            showsContactSupport = false
        }
    }

    init(message: String, recoverySuggestion: String? = nil, showsRetry: Bool = false, showsContactSupport: Bool = false) {
        self.message = message
        self.recoverySuggestion = recoverySuggestion
        self.showsRetry = showsRetry
        self.showsContactSupport = showsContactSupport
    }
}

// Client-side mirror of the backend's username policy for passkey registration, so
// malformed input is caught before it's sent as the WebAuthn userID/display name instead
// of surfacing as a generic backend rejection.
enum UsernameValidation {
    static let minLength = 3
    static let maxLength = 30
    private static let allowedCharacters = CharacterSet(charactersIn:
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")

    enum ValidationError: LocalizedError, Equatable {
        case tooShort
        case tooLong
        case invalidCharacters

        var errorDescription: String? {
            switch self {
            case .tooShort:
                return "Username must be at least \(UsernameValidation.minLength) characters."
            case .tooLong:
                return "Username must be \(UsernameValidation.maxLength) characters or fewer."
            case .invalidCharacters:
                return "Username can only contain letters, numbers, underscores, and hyphens."
            }
        }
    }

    /// Trims leading/trailing whitespace, then validates length and character set.
    /// Returns the trimmed username on success, or the first validation failure found.
    static func validate(_ input: String) -> Result<String, ValidationError> {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= minLength else { return .failure(.tooShort) }
        guard trimmed.count <= maxLength else { return .failure(.tooLong) }
        guard trimmed.unicodeScalars.allSatisfy(allowedCharacters.contains) else { return .failure(.invalidCharacters) }
        return .success(trimmed)
    }
}

enum BeneficiaryUpdate {
    /// A new beneficiary address is only valid if it's non-empty (after trimming)
    /// and actually differs from the vault's current beneficiary.
    static func isValidNewBeneficiary(_ input: String, currentBeneficiary: String) -> Bool {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed != currentBeneficiary
    }
}

struct AuthChallenge: Codable {
    let challenge: String
    let expiresAt: Date
    // Credential IDs already registered to the account this challenge is for. Empty
    // when there are none yet (e.g. a brand-new account) or when the server predates
    // this field — decoded defensively so older/partial responses still parse.
    let existingCredentialIds: [String]

    init(challenge: String, expiresAt: Date, existingCredentialIds: [String] = []) {
        self.challenge = challenge
        self.expiresAt = expiresAt
        self.existingCredentialIds = existingCredentialIds
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        challenge = try container.decode(String.self, forKey: .challenge)
        expiresAt = try container.decode(Date.self, forKey: .expiresAt)
        existingCredentialIds = try container.decodeIfPresent([String].self, forKey: .existingCredentialIds) ?? []
    }
}

struct AuthToken: Codable {
    let token: String
    let expiresAt: Date
}

/// Proof of identity for an existing vault-owning account that lost its original
/// passkey-holding device, gathered by RecoverAccessView before a new passkey may
/// be linked to that account (see PasskeyService.linkAdditionalPasskey).
struct AccountRecoveryProof: Codable {
    let email: String
    let backupCode: String
}

struct PushRegistration: Codable {
    let token: String
    let platform: String  // "ios" | "android"
    let locale: String    // BCP 47 language tag, e.g. "en-US"
}

// MARK: - 2FA Models

enum TwoFactorMethod: String, Codable, CaseIterable {
    case totp
    case sms
    case email
}

struct TwoFactorStatus: Codable {
    let vaultId: String
    let enabled: Bool
    let method: TwoFactorMethod?
    let verified: Bool
    let phone: String?
    let email: String?
}

struct Enable2FARequest: Codable {
    let method: TwoFactorMethod
    let phone: String?
    let email: String?
}

struct Enable2FAResponse: Codable {
    let vaultId: String
    let method: TwoFactorMethod
    let secret: String?
    let provisioningUri: String?
}

struct Verify2FARequest: Codable {
    let otp: String
}

// MARK: - Pagination Models (#112)

/// Paginated response for `GET /vaults`. See api-contract.md §Pagination.
struct VaultPage: Codable {
    let vaults: [Vault]
    /// Opaque cursor for the next page, or `nil` if this is the last page.
    let nextCursor: String?
    let hasMore: Bool
}
