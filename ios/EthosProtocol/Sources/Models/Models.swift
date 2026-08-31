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
    /// Optional owner-set display name (#218). `nil` until set via
    /// `APIClient.updateVaultLabel`. Absent on the wire (every vault created
    /// before this shipped) decodes to `nil` the same way any other missing
    /// optional key would.
    public let label: String? = nil

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

    /// Display name for list/detail UI (#218): the user-set `label` if present,
    /// else a truncated `id` — never the raw full ID, which is unwieldy at a
    /// glance across many vaults.
    public var displayName: String {
        label ?? String(id.prefix(12))
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

    /// Whether `amount` is large enough relative to `vaultBalance` to warrant an
    /// extra confirmation step before withdrawing (#216) — reduces fat-finger risk
    /// on a product holding funds meant for a beneficiary. `thresholdBps` is the
    /// percentage of the vault's balance (in basis points, 10_000 = 100%) at or
    /// above which the warning triggers; configurable via
    /// `WithdrawalThreshold.largeWithdrawalBps` rather than hardcoded here, so it
    /// can be tuned without touching call sites.
    static func isLargeWithdrawal(amount: Int64, vaultBalance: Int64, thresholdBps: Int) -> Bool {
        guard vaultBalance > 0, amount > 0 else { return false }
        // A UI-only confirmation nudge, not a funds-moving calculation — Double's
        // precision is more than sufficient here, and avoids Int64 overflow that
        // `amount * 10_000` could hit for very large stroop values.
        let ratio = Double(amount) / Double(vaultBalance)
        return ratio * 10_000 >= Double(thresholdBps)
    }
}

/// Status filter chips for the vault list (#219).
public enum VaultListFilter: String, CaseIterable, Identifiable {
    case all, active, expiringSoon, expired

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .all: return "All"
        case .active: return "Active"
        case .expiringSoon: return "Expiring Soon"
        case .expired: return "Expired"
        }
    }

    fileprivate func matches(_ vault: Vault) -> Bool {
        switch self {
        case .all: return true
        case .active: return vault.status == .active
        case .expiringSoon: return vault.status == .active && vault.isExpiringSoon
        case .expired: return vault.status == .expired
        }
    }
}

/// Client-side search/filter over an already-fetched vault list (#219) — works
/// across every page already pulled in via `VaultStore.load`/`loadAll`, since
/// it's a pure filter over whatever `vaults` currently holds rather than a
/// server request.
enum VaultListFiltering {
    /// Filters `vaults` by `statusFilter`, then by `searchText` matched
    /// case-insensitively against the vault's `label` (if set) or `id`.
    /// Blank `searchText` (after trimming) matches everything.
    static func filter(_ vaults: [Vault], searchText: String, statusFilter: VaultListFilter) -> [Vault] {
        let statusFiltered = vaults.filter { statusFilter.matches($0) }
        let trimmed = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return statusFiltered }
        return statusFiltered.filter { vault in
            vault.id.range(of: trimmed, options: .caseInsensitive) != nil
                || (vault.label?.range(of: trimmed, options: .caseInsensitive) != nil)
        }
    }
}

/// Configurable threshold for the "large withdrawal" confirmation step (#216).
/// A single tunable knob rather than a literal scattered across call sites —
/// change `largeWithdrawalBps` here to retune without touching WithdrawView.
enum WithdrawalThreshold {
    /// Percentage of the vault's balance (basis points, 10_000 = 100%) at or
    /// above which a withdrawal is considered "large" and prompts an extra
    /// confirmation step. Defaults to 80%.
    static let largeWithdrawalBps = 8_000
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

/// One of possibly several passkeys registered to the authenticated account (#206, #207) —
/// an account is not limited to a single credential, so this is always modeled as a list
/// (`[PasskeyCredential]`), never a lone value.
struct PasskeyCredential: Codable, Identifiable, Equatable {
    let credentialId: String
    let deviceLabel: String?
    let createdAt: Date
    let lastUsedAt: Date?

    var id: String { credentialId }
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

// MARK: - Vault History (#217)

/// A single past action against a vault (check-in, deposit, withdrawal,
/// beneficiary change, creation). See api-contract.md §VaultHistoryEvent.
struct VaultHistoryEvent: Codable, Identifiable, Equatable {
    enum EventType: String, Codable {
        case checkIn = "check_in"
        case deposit
        case withdrawal
        case beneficiaryChanged = "beneficiary_changed"
        case created
    }

    let eventType: EventType
    let timestamp: Date
    /// Present for `.deposit`/`.withdrawal` (stroops), `nil` otherwise.
    let amount: Int64?
    /// Present only for `.beneficiaryChanged` (the new beneficiary), `nil` otherwise.
    let beneficiary: String?

    /// Synthesized rather than server-provided — history entries have no
    /// natural unique ID field on the wire, and List/ForEach need Identifiable.
    var id: String { "\(eventType.rawValue)-\(timestamp.timeIntervalSince1970)" }

    var displayTitle: String {
        switch eventType {
        case .checkIn: return "Checked In"
        case .deposit: return "Deposit"
        case .withdrawal: return "Withdrawal"
        case .beneficiaryChanged: return "Beneficiary Changed"
        case .created: return "Vault Created"
        }
    }
}

/// A page of vault history events, mirroring `VaultPage`'s cursor pattern (#217).
struct VaultHistoryPage {
    let events: [VaultHistoryEvent]
    let nextCursor: String?
}

// MARK: - Pagination Models (#112)

/// Paginated response for `GET /vaults`. See api-contract.md §Pagination.
struct VaultPage: Codable {
    let vaults: [Vault]
    /// Opaque cursor for the next page, or `nil` if this is the last page.
    let nextCursor: String?
    let hasMore: Bool
}
