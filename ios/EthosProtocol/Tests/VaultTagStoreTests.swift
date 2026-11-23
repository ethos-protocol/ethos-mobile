import XCTest
@testable import EthosProtocol

/// #826 — VaultTagStore persists vault labels.
/// Tests: add, remove, and persistence.
final class VaultTagStoreTests: XCTestCase {

    private let vaultId = "test-vault-tag-store"
    private let vaultId2 = "test-vault-tag-store-2"

    override func setUp() {
        super.setUp()
        VaultTagStore.clearTags(for: vaultId)
        VaultTagStore.clearTags(for: vaultId2)
    }

    override func tearDown() {
        VaultTagStore.clearTags(for: vaultId)
        VaultTagStore.clearTags(for: vaultId2)
        super.tearDown()
    }

    // MARK: - Add

    func testAddTagAppearsInTags() {
        VaultTagStore.addTag("family", to: vaultId)

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["family"])
    }

    func testAddMultipleTagsPreservesOrder() {
        VaultTagStore.addTag("savings", to: vaultId)
        VaultTagStore.addTag("personal", to: vaultId)
        VaultTagStore.addTag("work", to: vaultId)

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["savings", "personal", "work"])
    }

    func testAddTagNormalizesToLowercase() {
        VaultTagStore.addTag("Family", to: vaultId)

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["family"])
    }

    func testAddTagTrimsWhitespace() {
        VaultTagStore.addTag("  savings  ", to: vaultId)

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["savings"])
    }

    func testAddDuplicateTagIsIgnored() {
        VaultTagStore.addTag("family", to: vaultId)
        VaultTagStore.addTag("Family", to: vaultId) // same after normalization

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["family"])
    }

    func testAddEmptyTagIsIgnored() {
        VaultTagStore.addTag("", to: vaultId)
        VaultTagStore.addTag("   ", to: vaultId)

        XCTAssertTrue(VaultTagStore.tags(for: vaultId).isEmpty)
    }

    // MARK: - Remove

    func testRemoveTagRemovesIt() {
        VaultTagStore.addTag("savings", to: vaultId)
        VaultTagStore.addTag("personal", to: vaultId)

        VaultTagStore.removeTag("savings", from: vaultId)

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["personal"])
    }

    func testRemoveLastTagCleansUpVaultEntry() {
        VaultTagStore.addTag("family", to: vaultId)
        VaultTagStore.removeTag("family", from: vaultId)

        XCTAssertTrue(VaultTagStore.tags(for: vaultId).isEmpty)
        XCTAssertFalse(VaultTagStore.allKnownTags().contains("family"))
    }

    func testRemoveNonexistentTagIsNoop() {
        VaultTagStore.addTag("family", to: vaultId)
        VaultTagStore.removeTag("work", from: vaultId) // not present

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["family"])
    }

    func testClearTagsRemovesAllForVault() {
        VaultTagStore.addTag("savings", to: vaultId)
        VaultTagStore.addTag("personal", to: vaultId)

        VaultTagStore.clearTags(for: vaultId)

        XCTAssertTrue(VaultTagStore.tags(for: vaultId).isEmpty)
    }

    // MARK: - Persistence

    func testTagsRoundTripThroughUserDefaults() {
        VaultTagStore.addTag("savings", to: vaultId)
        VaultTagStore.addTag("personal", to: vaultId)

        // Re-read from UserDefaults by calling the computed property again.
        let loaded = VaultTagStore.tags(for: vaultId)

        XCTAssertEqual(loaded, ["savings", "personal"])
    }

    func testTagsForDifferentVaultsAreIndependent() {
        VaultTagStore.addTag("savings", to: vaultId)
        VaultTagStore.addTag("work", to: vaultId2)

        XCTAssertEqual(VaultTagStore.tags(for: vaultId), ["savings"])
        XCTAssertEqual(VaultTagStore.tags(for: vaultId2), ["work"])
    }

    func testAllKnownTagsReturnsDistinctSortedTags() {
        VaultTagStore.addTag("savings", to: vaultId)
        VaultTagStore.addTag("work", to: vaultId)
        VaultTagStore.addTag("savings", to: vaultId2) // duplicate across vaults

        let known = VaultTagStore.allKnownTags()

        XCTAssertEqual(known, ["savings", "work"])
    }
}
