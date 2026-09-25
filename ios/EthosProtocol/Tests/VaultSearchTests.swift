import XCTest
@testable import EthosProtocol

final class VaultSearchTests: XCTestCase {
    let testVaults = [
        Vault(id: "GAAAA123456789", owner: "alice", beneficiary: "bob", balance: 1_000_000,
              checkInInterval: 86_400, lastCheckIn: Date(), ttlRemaining: 43_200, status: .active),
        Vault(id: "GBBBB987654321", owner: "charlie", beneficiary: "dave", balance: 2_000_000,
              checkInInterval: 86_400, lastCheckIn: Date(timeIntervalSinceNow: -86_400), ttlRemaining: 7_200, status: .active),
        Vault(id: "GCCCC555555555", owner: "eve", beneficiary: "frank", balance: 3_000_000,
              checkInInterval: 86_400, lastCheckIn: Date(timeIntervalSinceNow: -172_800), ttlRemaining: 0, status: .expired),
        Vault(id: "GDDDD111111111", owner: "grace", beneficiary: "henry", balance: 4_000_000,
              checkInInterval: 86_400, lastCheckIn: Date(timeIntervalSinceNow: -259_200), ttlRemaining: 172_800, status: .paused),
    ]

    override func tearDown() {
        super.tearDown()
        UserDefaults.standard.removeObject(forKey: "com.ethosprotocol.vault_search_preferences")
    }

    func testStatusFilterAll() {
        let filter = VaultStatusFilter.all
        let filtered = testVaults.filter { filter.matches(vault: $0) }
        XCTAssertEqual(filtered.count, 4)
    }

    func testStatusFilterHealthy() {
        let filter = VaultStatusFilter.healthy
        let filtered = testVaults.filter { filter.matches(vault: $0) }
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, "GAAAA123456789")
    }

    func testStatusFilterExpiringSoon() {
        let filter = VaultStatusFilter.expiringSoon
        let filtered = testVaults.filter { filter.matches(vault: $0) }
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, "GBBBB987654321")
    }

    func testStatusFilterExpired() {
        let filter = VaultStatusFilter.expired
        let filtered = testVaults.filter { filter.matches(vault: $0) }
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, "GCCCC555555555")
    }

    func testStatusFilterPaused() {
        let filter = VaultStatusFilter.paused
        let filtered = testVaults.filter { filter.matches(vault: $0) }
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, "GDDDD111111111")
    }

    func testSortByExpiryDate() {
        let sorted = testVaults.sorted { lhs, rhs in
            VaultSortOption.expiryDate.compare(lhs: lhs, rhs: rhs)
        }
        XCTAssertEqual(sorted.first?.id, "GBBBB987654321") // 7200 seconds
        XCTAssertEqual(sorted.last?.id, "GDDDD111111111") // max/paused
    }

    func testSortByName() {
        let sorted = testVaults.sorted { lhs, rhs in
            VaultSortOption.name.compare(lhs: lhs, rhs: rhs)
        }
        XCTAssertEqual(sorted.first?.id, "GAAAA123456789")
        XCTAssertEqual(sorted.last?.id, "GDDDD111111111")
    }

    func testSortByCreationDate() {
        let sorted = testVaults.sorted { lhs, rhs in
            VaultSortOption.creationDate.compare(lhs: lhs, rhs: rhs)
        }
        XCTAssertEqual(sorted.first?.id, "GAAAA123456789") // most recent lastCheckIn
    }

    func testSearchPreferencesStorage() {
        var prefs = VaultSearchPreferences()
        prefs.searchText = "GAAA"
        prefs.statusFilter = VaultStatusFilter.expiringSoon.rawValue
        prefs.sortOption = VaultSortOption.name.rawValue

        VaultSearchPreferences.current = prefs

        let loaded = VaultSearchPreferences.current
        XCTAssertEqual(loaded.searchText, "GAAA")
        XCTAssertEqual(loaded.statusFilter, "Expiring Soon")
        XCTAssertEqual(loaded.sortOption, "Name")
    }

    func testSearchTextFiltering() {
        let store = VaultStore()
        store.vaults = testVaults
        store.searchText = "GAAA"

        let filtered = store.filteredAndSortedVaults
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, "GAAAA123456789")
    }

    func testCaseSensitiveSearchFiltering() {
        let store = VaultStore()
        store.vaults = testVaults
        store.searchText = "gaaa"

        let filtered = store.filteredAndSortedVaults
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.id, "GAAAA123456789")
    }

    func testCombinedFiltering() {
        let store = VaultStore()
        store.vaults = testVaults
        store.statusFilter = .active
        store.sortOption = .expiryDate

        let filtered = store.filteredAndSortedVaults
        XCTAssertEqual(filtered.count, 2) // Two active vaults
        XCTAssertEqual(filtered.first?.id, "GBBBB987654321") // Expiring soonest
    }
}
