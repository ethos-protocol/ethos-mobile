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
    }

    private static let validationRegex = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{1,128}$")

    /// Validates that a vault ID or token matches the expected format
    private func isValidIdentifier(_ value: String) -> Bool {
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return Self.validationRegex.firstMatch(in: value, range: range) != nil
    }

    /// Parses a universal link or custom-scheme URL into a typed DeepLink, or returns nil if unrecognised.
    func parse(url: URL) -> DeepLink? {
        // ethosprotocol://vault/{vault_id}/{action}
        if url.scheme == "ethosprotocol", url.host == "vault" {
            let parts = url.pathComponents.filter { $0 != "/" }
            guard parts.count == 2, let action = VaultAction(rawValue: parts[1]) else { return nil }
            guard isValidIdentifier(parts[0]) else { return nil }
            let link = DeepLink.vaultAction(vaultID: parts[0], action: action)
            logDeepLink(link)
            return link
        }

        guard url.host == "ethos-protocol.app" else { return nil }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let parts = url.pathComponents.filter { $0 != "/" }

        // /vaults/{vaultID}/invite
        if parts.count == 3, parts[0] == "vaults", parts[2] == "invite" {
            guard isValidIdentifier(parts[1]) else { return nil }
            let link = DeepLink.vaultInvitation(vaultID: parts[1])
            logDeepLink(link)
            return link
        }

        // /vaults/{vaultID}/accept?token={token}
        if parts.count == 3, parts[0] == "vaults", parts[2] == "accept" {
            guard isValidIdentifier(parts[1]) else { return nil }
            let token = components?.queryItems?.first(where: { $0.name == "token" })?.value ?? ""
            guard !token.isEmpty, isValidIdentifier(token) else { return nil }
            let link = DeepLink.beneficiaryAcceptance(vaultID: parts[1], token: token)
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
