import XCTest
@testable import EthosProtocol

// #822: VaultMergeStore must persist merge decisions in UserDefaults and unmerge cleanly.
// VaultDuplicateDetector must group vaults by shared beneficiary.
final class VaultMergeStoreTests: XCTestCase {

    private var defaults: UserDefaults!
    private let suiteName = "VaultMergeStoreTests"

    override func setUp() {
        super.setUp()
        // Each test run gets a clean UserDefaults suite so tests don't share state.
        // VaultMergeStore uses UserDefaults.standard, so we swap the standard suite
        // for the duration of the test via the suite-name trick used elsewhere in
        // this test suite (see PendingTwoFactorSessionStoreTests).
        defaults = UserDefaults(suiteName: "\(suiteName).\(UUID().uuidString)")!
        // Clear the key VaultMergeStore writes to, using the same key string.
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    // Helper: wipe the standard UserDefaults key that VaultMergeStore writes to,
    // so tests that exercise VaultMergeStore directly start clean.
    private func cleanMergeKey() {
        UserDefaults.standard.removeObject(forKey: "com.ethosprotocol.vault_merges")
    }

    override func setUpWithError() throws {
        cleanMergeKey()
    }

    override func tearDownWithError() throws {
        cleanMergeKey()
    }

    // MARK: - Merge

    func test_merge_hidesSourceVault() {
        VaultMergeStore.merge("v-dup", into: "v-keep")
        XCTAssertTrue(VaultMergeStore.hiddenVaultIds().contains("v-dup"))
    }

    func test_merge_doesNotHideKeeperVault() {
        VaultMergeStore.merge("v-dup", into: "v-keep")
        XCTAssertFalse(VaultMergeStore.hiddenVaultIds().contains("v-keep"))
    }

    func test_merge_replacesExistingDecisionForSameSource() {
        VaultMergeStore.merge("v-dup", into: "v-keep-1")
        VaultMergeStore.merge("v-dup", into: "v-keep-2")

        let hidden = VaultMergeStore.hiddenVaultIds()
        XCTAssertTrue(hidden.contains("v-dup"), "v-dup should still be hidden")
        XCTAssertEqual(hidden.count, 1, "Only one entry for v-dup; prior decision replaced")

        let merged = VaultMergeStore.mergedVaultIds(keptBy: "v-keep-2")
        XCTAssertEqual(merged, ["v-dup"])
        XCTAssertTrue(VaultMergeStore.mergedVaultIds(keptBy: "v-keep-1").isEmpty,
                      "Old keeper must no longer reference v-dup after the decision was replaced")
    }

    func test_mergedVaultIds_returnsAllSourcesForKeeper() {
        VaultMergeStore.merge("v-dup-a", into: "v-keep")
        VaultMergeStore.merge("v-dup-b", into: "v-keep")

        let merged = VaultMergeStore.mergedVaultIds(keptBy: "v-keep")
        XCTAssertEqual(Set(merged), ["v-dup-a", "v-dup-b"])
    }

    // MARK: - Unmerge

    func test_unmerge_makesVaultVisibleAgain() {
        VaultMergeStore.merge("v-dup", into: "v-keep")
        VaultMergeStore.unmerge("v-dup")
        XCTAssertFalse(VaultMergeStore.hiddenVaultIds().contains("v-dup"))
    }

    func test_unmerge_doesNotAffectOtherMerges() {
        VaultMergeStore.merge("v-dup-a", into: "v-keep")
        VaultMergeStore.merge("v-dup-b", into: "v-keep")

        VaultMergeStore.unmerge("v-dup-a")

        XCTAssertFalse(VaultMergeStore.hiddenVaultIds().contains("v-dup-a"))
        XCTAssertTrue(VaultMergeStore.hiddenVaultIds().contains("v-dup-b"))
    }

    func test_unmerge_nonExistentVault_isNoop() {
        VaultMergeStore.merge("v-dup", into: "v-keep")
        VaultMergeStore.unmerge("v-nobody")
        XCTAssertEqual(VaultMergeStore.hiddenVaultIds().count, 1)
    }

    // MARK: - Round-trip through UserDefaults

    func test_mergeRoundTrip_persistsAcrossNewRead() {
        VaultMergeStore.merge("v-dup", into: "v-keep")
        // hiddenVaultIds() re-reads from UserDefaults each time.
        let hidden = VaultMergeStore.hiddenVaultIds()
        XCTAssertTrue(hidden.contains("v-dup"))
    }

    func test_unmergeRoundTrip_removedFromPersistence() {
        VaultMergeStore.merge("v-dup", into: "v-keep")
        VaultMergeStore.unmerge("v-dup")
        XCTAssertTrue(VaultMergeStore.hiddenVaultIds().isEmpty)
    }
}

// #822: VaultDuplicateDetector must group vaults by shared beneficiary.
final class VaultDuplicateDetectorTests: XCTestCase {

    private func makeVault(id: String, beneficiary: String) -> Vault {
        Vault(id: id, owner: "owner-1", beneficiary: beneficiary, balance: 0,
              checkInInterval: 86400, lastCheckIn: Date(), ttlRemaining: nil,
              status: .active, assetCode: "XLM", assetIssuer: nil, version: 0)
    }

    // MARK: - Detection thresholds

    func test_noDuplicates_returnsEmpty() {
        let vaults = [
            makeVault(id: "v1", beneficiary: "alice"),
            makeVault(id: "v2", beneficiary: "bob"),
        ]
        XCTAssertTrue(VaultDuplicateDetector.findDuplicateGroups(in: vaults).isEmpty)
    }

    func test_singleVault_returnsEmpty() {
        let vaults = [makeVault(id: "v1", beneficiary: "alice")]
        XCTAssertTrue(VaultDuplicateDetector.findDuplicateGroups(in: vaults).isEmpty)
    }

    func test_emptyList_returnsEmpty() {
        XCTAssertTrue(VaultDuplicateDetector.findDuplicateGroups(in: []).isEmpty)
    }

    func test_twoBeneficiaryMatches_returnsOneGroup() {
        let vaults = [
            makeVault(id: "v1", beneficiary: "alice"),
            makeVault(id: "v2", beneficiary: "alice"),
        ]
        let groups = VaultDuplicateDetector.findDuplicateGroups(in: vaults)
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(groups.first?.beneficiary, "alice")
        XCTAssertEqual(Set(groups.first?.vaults.map(\.id) ?? []), ["v1", "v2"])
    }

    func test_threeBeneficiaryMatches_allInOneGroup() {
        let vaults = [
            makeVault(id: "v1", beneficiary: "alice"),
            makeVault(id: "v2", beneficiary: "alice"),
            makeVault(id: "v3", beneficiary: "alice"),
        ]
        let groups = VaultDuplicateDetector.findDuplicateGroups(in: vaults)
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(groups.first?.vaults.count, 3)
    }

    func test_twoDifferentDuplicatePairs_returnsTwoGroups() {
        let vaults = [
            makeVault(id: "v1", beneficiary: "alice"),
            makeVault(id: "v2", beneficiary: "alice"),
            makeVault(id: "v3", beneficiary: "bob"),
            makeVault(id: "v4", beneficiary: "bob"),
        ]
        let groups = VaultDuplicateDetector.findDuplicateGroups(in: vaults)
        XCTAssertEqual(groups.count, 2)
    }

    func test_mixedUnique_andDuplicate() {
        let vaults = [
            makeVault(id: "v1", beneficiary: "alice"),
            makeVault(id: "v2", beneficiary: "alice"),
            makeVault(id: "v3", beneficiary: "carol"),
        ]
        let groups = VaultDuplicateDetector.findDuplicateGroups(in: vaults)
        XCTAssertEqual(groups.count, 1)
        XCTAssertEqual(groups.first?.beneficiary, "alice")
    }
}
