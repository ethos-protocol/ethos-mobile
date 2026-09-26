import Foundation
import AppIntents

@available(iOS 16.1, *)
struct CheckInVaultIntent: AppIntent {
    static var title: LocalizedStringResource = "Check In Vault"
    static var description: LocalizedStringResource = "Check in to a vault to extend its TTL"
    static var openAppWhenRun = true

    @Parameter(title: "Vault ID") var vaultID: String

    func perform() async throws -> some IntentResult {
        guard let vault = try await findVault(by: vaultID) else {
            throw ShortcutError.vaultNotFound
        }

        try await APIClient.shared.checkIn(vaultID: vault.id)
        return .result(value: "Successfully checked in to vault \(vault.id)")
    }

    private func findVault(by id: String) async throws -> Vault? {
        let page = try await APIClient.shared.listVaults()
        return page.vaults.first { $0.id == id }
    }
}

@available(iOS 16.1, *)
struct ViewVaultExpiryIntent: AppIntent {
    static var title: LocalizedStringResource = "View Vault Expiry"
    static var description: LocalizedStringResource = "Check the remaining time before a vault expires"
    static var openAppWhenRun = true

    @Parameter(title: "Vault ID") var vaultID: String

    func perform() async throws -> some IntentResult {
        guard let vault = try await findVault(by: vaultID) else {
            throw ShortcutError.vaultNotFound
        }

        if let ttl = vault.ttlRemaining {
            let formatter = DateComponentsFormatter()
            formatter.allowedUnits = [.day, .hour, .minute, .second]
            formatter.unitsStyle = .abbreviated
            let formattedTime = formatter.string(from: TimeInterval(ttl / 1_000_000_000)) ?? "Unknown"
            return .result(value: "Vault \(vault.id) expires in \(formattedTime)")
        } else {
            return .result(value: "Vault \(vault.id) has no TTL information")
        }
    }

    private func findVault(by id: String) async throws -> Vault? {
        let page = try await APIClient.shared.listVaults()
        return page.vaults.first { $0.id == id }
    }
}

enum ShortcutError: LocalizedError {
    case vaultNotFound
    case authenticationRequired
    case networkError

    var errorDescription: String? {
        switch self {
        case .vaultNotFound: return "Vault not found"
        case .authenticationRequired: return "Authentication required"
        case .networkError: return "Network error occurred"
        }
    }
}

@available(iOS 16.1, *)
final class AppShortcutsProvider {
    static func registerShortcuts() {
        AppShortcutsProvider.registerCheckInShortcut()
        AppShortcutsProvider.registerViewExpiryShortcut()
    }

    private static func registerCheckInShortcut() {
    }

    private static func registerViewExpiryShortcut() {
    }
}
