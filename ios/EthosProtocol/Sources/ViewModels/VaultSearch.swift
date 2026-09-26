import Foundation

enum VaultStatusFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case healthy = "Healthy"
    case expiringSoon = "Expiring Soon"
    case expired = "Expired"
    case paused = "Paused"

    var id: String { rawValue }

    func matches(vault: Vault) -> Bool {
        switch self {
        case .all:
            return true
        case .healthy:
            return vault.status == .active && !vault.isExpiringSoon
        case .expiringSoon:
            return vault.status == .active && vault.isExpiringSoon
        case .expired:
            return vault.status == .expired
        case .paused:
            return vault.status == .paused
        }
    }
}

enum VaultSortOption: String, CaseIterable, Identifiable {
    case creationDate = "Creation Date"
    case expiryDate = "Expiry Date"
    case name = "Name"

    var id: String { rawValue }

    func compare(lhs: Vault, rhs: Vault) -> Bool {
        switch self {
        case .creationDate:
            return lhs.lastCheckIn > rhs.lastCheckIn
        case .expiryDate:
            let lhsTTL = lhs.ttlRemaining ?? UInt64.max
            let rhsTTL = rhs.ttlRemaining ?? UInt64.max
            return lhsTTL < rhsTTL
        case .name:
            return lhs.id.lowercased() < rhs.id.lowercased()
        }
    }
}

struct VaultSearchPreferences: Codable {
    var searchText: String = ""
    var statusFilter: String = VaultStatusFilter.all.rawValue
    var sortOption: String = VaultSortOption.expiryDate.rawValue

    private static let userDefaultsKey = "com.ethosprotocol.vault_search_preferences"

    static var current: VaultSearchPreferences {
        get {
            if let data = UserDefaults.standard.data(forKey: userDefaultsKey),
               let preferences = try? JSONDecoder().decode(VaultSearchPreferences.self, from: data) {
                return preferences
            }
            return VaultSearchPreferences()
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(data, forKey: userDefaultsKey)
            }
        }
    }
}
