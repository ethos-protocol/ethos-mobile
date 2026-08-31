import Foundation

final class UniversalLinkRouter {
    static let shared = UniversalLinkRouter()
    private init() {}

    enum VaultAction: String, Equatable {
        case checkIn = "check-in"
        case withdraw = "withdraw"
        case viewDetails = "view-details"
        case manageBeneficiary = "manage-beneficiary"
    }

    enum DeepLink: Equatable {
        case vaultInvitation(vaultID: String)
        case beneficiaryAcceptance(vaultID: String, token: String)
        case vaultAction(vaultID: String, action: VaultAction)
        /// #258: Web-initiated account recovery — opened from an emailed link.
        /// The `token` pre-fills the "finish recovery" screen so the user doesn't retype it.
        case recoveryLink(token: String)
    }

    private static let validationRegex = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{1,128}$")

    /// Validates that a vault ID or token matches the expected format
    private func isValidIdentifier(_ value: String) -> Bool {
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return Self.validationRegex.firstMatch(in: value, range: range) != nil
    }

    // #259: Vault IDs owned by the currently signed-in user.
    //
    // When non-nil, deep links referencing a vault not in this set are rejected
    // client-side before any API call is made. The rejection is intentionally
    // generic — it must not reveal whether the vault exists — to avoid leaking
    // vault-existence information via deep-link probing.
    //
    // Set to nil (the default) when the vault list has not been loaded yet;
    // ownership is treated as unknown and the check is skipped. The server
    // will return 403/404 if the vault doesn't belong to the caller.
    var ownerVaultIDs: Set<String>? = nil

    private func isOwnedVault(_ vaultID: String) -> Bool {
        guard let owned = ownerVaultIDs else { return true }  // unknown — skip check
        return owned.contains(vaultID)
    }

    /// Parses a universal link or custom-scheme URL into a typed DeepLink, or returns nil if unrecognised.
    func parse(url: URL) -> DeepLink? {
        // ethosprotocol://vault/{vault_id}/{action}
        if url.scheme == "ethosprotocol", url.host == "vault" {
            let parts = url.pathComponents.filter { $0 != "/" }
            guard parts.count == 2, let action = VaultAction(rawValue: parts[1]) else { return nil }
            guard isValidIdentifier(parts[0]) else { return nil }
            guard isOwnedVault(parts[0]) else { return nil }
            let link = DeepLink.vaultAction(vaultID: parts[0], action: action)
            logDeepLink(link)
            return link
        }

        guard url.scheme == "https", url.host == "ethos-protocol.app" else { return nil }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let parts = url.pathComponents.filter { $0 != "/" }

        // /vaults/{vaultID}/invite
        if parts.count == 3, parts[0] == "vaults", parts[2] == "invite" {
            guard isValidIdentifier(parts[1]) else { return nil }
            guard isOwnedVault(parts[1]) else { return nil }
            let link = DeepLink.vaultInvitation(vaultID: parts[1])
            logDeepLink(link)
            return link
        }

        // /vaults/{vaultID}/accept?token={token}
        if parts.count == 3, parts[0] == "vaults", parts[2] == "accept" {
            guard isValidIdentifier(parts[1]) else { return nil }
            guard isOwnedVault(parts[1]) else { return nil }
            let token = components?.queryItems?.first(where: { $0.name == "token" })?.value ?? ""
            guard token.isEmpty || isValidIdentifier(token) else { return nil }
            let link = DeepLink.beneficiaryAcceptance(vaultID: parts[1], token: token)
            logDeepLink(link)
            return link
        }

        // #258: /auth/recover/link?token={token}
        if parts.count == 3, parts[0] == "auth", parts[1] == "recover", parts[2] == "link" {
            let token = components?.queryItems?.first(where: { $0.name == "token" })?.value ?? ""
            // Recovery tokens must always be present and well-formed — a missing or
            // malformed token cannot pre-fill the recovery step, so return nil to avoid
            // routing into an unrecoverable broken state.
            guard !token.isEmpty, isValidIdentifier(token) else { return nil }
            let link = DeepLink.recoveryLink(token: token)
            logDeepLink(link)
            return link
        }

        return nil
    }

    private func logDeepLink(_ link: DeepLink) {
        let event: DeepLinkLogger.DeepLinkEvent
        switch link {
        case .vaultInvitation:
            event = .vaultInvitation
        case .beneficiaryAcceptance:
            event = .beneficiaryAcceptance
        case .recoveryLink:
            event = .recoveryLink
        case .vaultAction(_, let action):
            switch action {
            case .checkIn:
                event = .vaultActionCheckIn
            case .withdraw:
                event = .vaultActionWithdraw
            case .viewDetails:
                event = .vaultActionViewDetails
            case .manageBeneficiary:
                event = .vaultActionManageBeneficiary
            }
        }
        DeepLinkLogger.shared.log(event: event)
    }
}
