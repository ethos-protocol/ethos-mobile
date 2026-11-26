import Foundation
import AppIntents

@available(iOS 16.1, *)
struct CheckInVaultIntent: AppIntent {
    static var title: LocalizedStringResource = "Check In Vault"
    static var description: LocalizedStringResource = "Check in to a vault to extend its TTL"
    static var openAppWhenRun = true

    @Parameter(title: "Vault ID") var vaultID: String

    func perform() async throws -> some IntentResult {
        let message = try await executeCheckIn()
        return .result(value: message)
    }

    func executeCheckIn(
        listVaults: () async throws -> [Vault] = { try await APIClient.shared.listVaults().vaults },
        idempotencyKey: String = UUID().uuidString,
        store: PendingCheckInStore = .shared,
        send: QueuedCheckIn.Send = { try await APIClient.shared.checkIn(vaultID: $0, idempotencyKey: $1) }
    ) async throws -> String {
        guard let vault = try await listVaults().first(where: { $0.id == vaultID }) else {
            throw ShortcutError.vaultNotFound
        }

        // #790: idempotent + offline-queued via the shared path.
        try await QueuedCheckIn.perform(
            vaultID: vault.id,
            idempotencyKey: idempotencyKey,
            store: store,
            send: send
        )
        return "Successfully checked in to vault \(vault.id)"
    }
}

@available(iOS 16.1, *)
struct ViewVaultExpiryIntent: AppIntent {
    static var title: LocalizedStringResource = "View Vault Expiry"
    static var description: LocalizedStringResource = "Check the remaining time before a vault expires"
    static var openAppWhenRun = true

    @Parameter(title: "Vault ID") var vaultID: String

    func perform() async throws -> some IntentResult {
        let message = try await executeViewExpiry()
        return .result(value: message)
    }

    func executeViewExpiry(
        listVaults: () async throws -> [Vault] = { try await APIClient.shared.listVaults().vaults }
    ) async throws -> String {
        guard let vault = try await listVaults().first(where: { $0.id == vaultID }) else {
            throw ShortcutError.vaultNotFound
        }

        if let ttl = vault.ttlRemaining {
            let formatter = DateComponentsFormatter()
            formatter.allowedUnits = [.day, .hour, .minute, .second]
            formatter.unitsStyle = .abbreviated
            let formattedTime = formatter.string(from: TimeInterval(ttl / 1_000_000_000)) ?? "Unknown"
            return "Vault \(vault.id) expires in \(formattedTime)"
        } else {
            return "Vault \(vault.id) has no TTL information"
        }
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
