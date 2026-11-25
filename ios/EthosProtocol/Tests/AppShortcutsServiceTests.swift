import XCTest
@testable import EthosProtocol

@available(iOS 16.1, *)
final class AppShortcutsServiceTests: XCTestCase {

    private func makeStore() -> PendingCheckInStore {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppShortcutsServiceTests-\(UUID().uuidString).json")
        return PendingCheckInStore(fileURL: url)
    }

    private func makeVault(id: String = "vault-1", ttlRemaining: UInt64? = 3_600_000_000_000) -> Vault {
        Vault(
            id: id,
            owner: "GOWNER",
            beneficiary: "GBENEFICIARY",
            balance: 1_000_000,
            checkInInterval: 86_400,
            lastCheckIn: Date(),
            ttlRemaining: ttlRemaining,
            status: .active
        )
    }

    // MARK: - CheckInVaultIntent

    func test_checkInIntent_online_usesIdempotencyKeyAndDoesNotQueue() async throws {
        let store = makeStore()
        let vault = makeVault(id: "vault-online")
        var sentCalls: [(vaultID: String, idempotencyKey: String)] = []

        var intent = CheckInVaultIntent()
        intent.vaultID = vault.id

        let message = try await intent.executeCheckIn(
            listVaults: { [vault] },
            idempotencyKey: "shortcut-key-1",
            store: store,
            send: { vaultID, key in
                sentCalls.append((vaultID, key))
            }
        )

        XCTAssertEqual(message, "Successfully checked in to vault vault-online")
        XCTAssertEqual(sentCalls.count, 1)
        XCTAssertEqual(sentCalls.first?.vaultID, "vault-online")
        XCTAssertEqual(sentCalls.first?.idempotencyKey, "shortcut-key-1")
        XCTAssertEqual(store.count, 0)
    }

    func test_checkInIntent_offline_queuesCheckInWithIdempotencyKey() async throws {
        let store = makeStore()
        let vault = makeVault(id: "vault-offline")

        var intent = CheckInVaultIntent()
        intent.vaultID = vault.id

        let message = try await intent.executeCheckIn(
            listVaults: { [vault] },
            idempotencyKey: "shortcut-offline-key",
            store: store,
            send: { _, _ in
                throw APIError.networkUnavailable
            }
        )

        XCTAssertEqual(message, "Successfully checked in to vault vault-offline")
        let queued = store.getAll()
        XCTAssertEqual(queued.count, 1)
        XCTAssertEqual(queued.first?.vaultId, "vault-offline")
        XCTAssertEqual(queued.first?.idempotencyKey, "shortcut-offline-key")
    }

    func test_checkInIntent_defaultIdempotencyKey_isNonEmpty() async throws {
        let store = makeStore()
        let vault = makeVault(id: "vault-default-key")
        var capturedKey: String?

        var intent = CheckInVaultIntent()
        intent.vaultID = vault.id

        _ = try await intent.executeCheckIn(
            listVaults: { [vault] },
            store: store,
            send: { _, key in
                capturedKey = key
            }
        )

        XCTAssertNotNil(capturedKey)
        XCTAssertFalse(capturedKey?.isEmpty ?? true)
    }

    func test_checkInIntent_repeatedWithSameKey_reusesIdempotencyKey() async throws {
        let store = makeStore()
        let vault = makeVault(id: "vault-retry")
        var sentKeys: [String] = []

        var intent = CheckInVaultIntent()
        intent.vaultID = vault.id

        for _ in 0..<2 {
            _ = try await intent.executeCheckIn(
                listVaults: { [vault] },
                idempotencyKey: "stable-key",
                store: store,
                send: { _, key in
                    sentKeys.append(key)
                }
            )
        }

        XCTAssertEqual(sentKeys, ["stable-key", "stable-key"])
        XCTAssertEqual(store.count, 0)
    }

    func test_checkInIntent_vaultNotFound_throwsAndDoesNotSendOrQueue() async {
        let store = makeStore()
        let otherVault = makeVault(id: "vault-other")
        var sendCalled = false

        var intent = CheckInVaultIntent()
        intent.vaultID = "vault-missing"

        do {
            _ = try await intent.executeCheckIn(
                listVaults: { [otherVault] },
                idempotencyKey: "unused-key",
                store: store,
                send: { _, _ in
                    sendCalled = true
                }
            )
            XCTFail("Expected ShortcutError.vaultNotFound")
        } catch let error as ShortcutError {
            XCTAssertEqual(error.errorDescription, ShortcutError.vaultNotFound.errorDescription)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertFalse(sendCalled)
        XCTAssertEqual(store.count, 0)
    }

    func test_checkInIntent_nonNetworkError_rethrowsWithoutQueueing() async {
        let store = makeStore()
        let vault = makeVault(id: "vault-err")

        var intent = CheckInVaultIntent()
        intent.vaultID = vault.id

        do {
            _ = try await intent.executeCheckIn(
                listVaults: { [vault] },
                idempotencyKey: "err-key",
                store: store,
                send: { _, _ in
                    throw ShortcutError.authenticationRequired
                }
            )
            XCTFail("Expected ShortcutError.authenticationRequired")
        } catch let error as ShortcutError {
            XCTAssertEqual(error.errorDescription, ShortcutError.authenticationRequired.errorDescription)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(store.count, 0)
    }

    // MARK: - ViewVaultExpiryIntent

    func test_viewVaultExpiryIntent_withTTL_returnsFormattedMessage() async throws {
        let vault = makeVault(id: "vault-ttl", ttlRemaining: 3_600_000_000_000)

        var intent = ViewVaultExpiryIntent()
        intent.vaultID = vault.id

        let message = try await intent.executeViewExpiry(listVaults: { [vault] })
        XCTAssertTrue(message.hasPrefix("Vault vault-ttl expires in "))
    }

    func test_viewVaultExpiryIntent_withoutTTL_returnsNoTTLMessage() async throws {
        let vault = makeVault(id: "vault-no-ttl", ttlRemaining: nil)

        var intent = ViewVaultExpiryIntent()
        intent.vaultID = vault.id

        let message = try await intent.executeViewExpiry(listVaults: { [vault] })
        XCTAssertEqual(message, "Vault vault-no-ttl has no TTL information")
    }

    func test_viewVaultExpiryIntent_vaultNotFound_throwsVaultNotFound() async {
        var intent = ViewVaultExpiryIntent()
        intent.vaultID = "missing"

        do {
            _ = try await intent.executeViewExpiry(listVaults: { [] })
            XCTFail("Expected ShortcutError.vaultNotFound")
        } catch let error as ShortcutError {
            XCTAssertEqual(error.errorDescription, ShortcutError.vaultNotFound.errorDescription)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    // MARK: - ShortcutError & AppShortcutsProvider

    func test_shortcutError_descriptions() {
        XCTAssertEqual(ShortcutError.vaultNotFound.errorDescription, "Vault not found")
        XCTAssertEqual(ShortcutError.authenticationRequired.errorDescription, "Authentication required")
        XCTAssertEqual(ShortcutError.networkError.errorDescription, "Network error occurred")
    }

    func test_registerShortcuts_doesNotCrash() {
        AppShortcutsProvider.registerShortcuts()
    }
}
