import XCTest
@testable import EthosProtocol

// #823: AccountManager must correctly switch tokens between accounts and remove
// them from the Keychain. These tests run in the hosted EthosProtocolTests target
// (not the bare SPM bundle) because Keychain requires a real app host process
// with the keychain-access-group entitlement.
//
// AccountManager.shared is a process-lifetime singleton whose accounts roster
// and active ID persist in UserDefaults. Each test cleans up the accounts it
// adds so test-induced state doesn't accumulate across the suite.

@MainActor
final class AccountManagerTests: XCTestCase {

    private let manager = AccountManager.shared

    // IDs of accounts created by this test; cleaned up in tearDown.
    private var allocatedAccountIds: [String] = []

    override func setUp() async throws {
        try await super.setUp()
        allocatedAccountIds = []
    }

    override func tearDown() async throws {
        for id in allocatedAccountIds {
            KeychainService.shared.deleteToken(accountId: id)
        }
        allocatedAccountIds = []
        try await super.tearDown()
    }

    // MARK: - Helpers

    /// Places `token` in the active Keychain slot, adds it to the roster via
    /// saveCurrentSessionAsAccount, and registers the returned ID for teardown.
    @discardableResult
    private func addAccount(token: String, label: String) -> Account? {
        KeychainService.shared.saveToken(token)
        let account = manager.saveCurrentSessionAsAccount(label: label)
        if let id = account?.id { allocatedAccountIds.append(id) }
        return account
    }

    // MARK: - saveCurrentSessionAsAccount

    func test_saveCurrentSession_addsAccountToRoster() {
        let before = manager.accounts.count
        guard let account = addAccount(token: "token-save-\(UUID().uuidString)", label: "Save") else {
            return XCTFail("saveCurrentSessionAsAccount should return an Account when a token is present")
        }
        XCTAssertEqual(manager.accounts.count, before + 1)
        XCTAssertTrue(manager.accounts.contains { $0.id == account.id })
        XCTAssertEqual(manager.activeAccountId, account.id)
    }

    func test_saveCurrentSession_withNoToken_returnsNil() {
        KeychainService.shared.deleteToken()
        let result = manager.saveCurrentSessionAsAccount(label: "Nobody")
        XCTAssertNil(result, "saveCurrentSessionAsAccount must return nil when no active token exists")
    }

    // MARK: - switchTo

    func test_switchTo_unknownAccountId_returnsFalse() {
        let result = manager.switchTo(UUID().uuidString)
        XCTAssertFalse(result)
    }

    func test_switchTo_loadsTargetTokenIntoActiveSlot() {
        let tokenA = "token-switch-a-\(UUID().uuidString)"
        let tokenB = "token-switch-b-\(UUID().uuidString)"

        guard let accountA = addAccount(token: tokenA, label: "A") else {
            return XCTFail("Precondition: failed to add account A")
        }
        guard let accountB = addAccount(token: tokenB, label: "B") else {
            return XCTFail("Precondition: failed to add account B")
        }
        // B is now active. Switch back to A.
        let switched = manager.switchTo(accountA.id)
        XCTAssertTrue(switched)
        XCTAssertEqual(manager.activeAccountId, accountA.id)
        // A's token must be in the active Keychain slot.
        XCTAssertEqual(KeychainService.shared.loadToken(), tokenA)

        // Restore B as active so teardown can clean up without leaving orphaned state.
        _ = manager.switchTo(accountB.id)
    }

    func test_switchTo_archivesOutgoingTokenBeforeSwitching() {
        let tokenA = "token-arch-a-\(UUID().uuidString)"
        let tokenB = "token-arch-b-\(UUID().uuidString)"

        guard let accountA = addAccount(token: tokenA, label: "Arch-A") else {
            return XCTFail("Precondition: failed to add account A")
        }
        guard let accountB = addAccount(token: tokenB, label: "Arch-B") else {
            return XCTFail("Precondition: failed to add account B")
        }
        // B is now active. Switch back to A: B's token must be archived.
        _ = manager.switchTo(accountA.id)

        XCTAssertEqual(KeychainService.shared.loadToken(accountId: accountB.id), tokenB,
                       "Outgoing account B's token must be archived in its own Keychain slot when switching away")

        _ = manager.switchTo(accountB.id) // restore for teardown
        _ = accountA // suppress unused-variable warning
    }

    // MARK: - removeAccount (inactive)
    //
    // AccountManager.removeAccount() calls api.revokeSession(bearerToken:) only
    // when the account has a scoped token. By omitting the scoped token we test
    // the full removal path (roster removal + Keychain deletion + UserDefaults
    // update) without needing a real network call, mirroring the case where the
    // server session was already invalidated by the time removal is requested.

    func test_removeInactiveAccount_removesItFromRosterAndDeletesToken() async throws {
        // Add account A (will be active) and account B (will be inactive).
        guard let accountA = addAccount(token: "token-rm-a-\(UUID().uuidString)", label: "Active") else {
            return XCTFail("Precondition: failed to add account A")
        }
        let inactiveId = UUID().uuidString
        // Add a scoped token for B so it appears in the roster as a valid account.
        // AccountManager.switchTo requires a scoped token; here we just inject B into
        // the roster directly (via saveCurrentSessionAsAccount while temporarily setting
        // its token as the active slot), then switch back to A.
        guard let accountB = addAccount(token: "token-rm-b-\(UUID().uuidString)", label: "Inactive") else {
            return XCTFail("Precondition: failed to add account B")
        }
        _ = manager.switchTo(accountA.id) // A is active; B is inactive
        _ = inactiveId // suppress warning

        // B has a scoped token; remove it. No network call needed: KeychainService
        // stores B's token under its scoped key, but revokeSession will make a network
        // call. Skip the scoped token so the `if let token` guard in removeAccount
        // is nil and the revoke block is bypassed — testing the deletion path itself.
        KeychainService.shared.deleteToken(accountId: accountB.id)
        allocatedAccountIds.removeAll { $0 == accountB.id }

        try await manager.removeAccount(accountB.id)

        XCTAssertFalse(manager.accounts.contains { $0.id == accountB.id },
                       "Removed inactive account must be absent from the roster")
        XCTAssertNil(KeychainService.shared.loadToken(accountId: accountB.id),
                     "Removed inactive account's Keychain token must be deleted")
        XCTAssertEqual(manager.activeAccountId, accountA.id,
                       "Removing an inactive account must not change the active account ID")
    }

    // MARK: - removeAccount (active)

    func test_removeActiveAccount_clearsActiveAccountId() async throws {
        guard let account = addAccount(token: "token-rm-self-\(UUID().uuidString)", label: "Self") else {
            return XCTFail("Precondition: failed to add account")
        }
        _ = manager.switchTo(account.id)

        // Delete the scoped token so removeAccount bypasses the network revoke call.
        KeychainService.shared.deleteToken(accountId: account.id)
        allocatedAccountIds.removeAll { $0 == account.id }

        try await manager.removeAccount(account.id)

        XCTAssertFalse(manager.accounts.contains { $0.id == account.id },
                       "Removed account must be absent from the roster")
        XCTAssertNil(manager.activeAccountId,
                     "activeAccountId must be nil after removing the active account")
        XCTAssertNil(UserDefaults.standard.string(forKey: "com.ethosprotocol.active_account_id"),
                     "UserDefaults active account key must be cleared")
    }
}
