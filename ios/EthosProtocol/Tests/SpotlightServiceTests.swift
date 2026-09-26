import XCTest
import CoreSpotlight
@testable import EthosProtocol

// MARK: - #440 SpotlightService Tests
//
// Verifies that vault metadata is donated to Spotlight with the correct identifiers,
// attributes, and NSUserActivity configuration, and that revoke/revokeAll route through
// the right CSSearchableIndex deletion APIs.  A `MockSpotlightIndex` stands in for the
// real daemon so tests run without a device or Spotlight entitlement.

// MARK: - MockSpotlightIndex

/// Test double for `SpotlightIndexing` that records calls for assertion.
final class MockSpotlightIndex: SpotlightIndexing {

    // Recorded arguments — one entry per call.
    private(set) var indexedBatches: [[CSSearchableItem]] = []
    private(set) var deletedIdentifiers: [[String]] = []
    private(set) var deletedDomains: [[String]] = []

    // Inject an error to simulate indexing failures.
    var indexError: Error?
    var deleteError: Error?

    func indexSearchableItems(_ items: [CSSearchableItem],
                              completionHandler: ((Error?) -> Void)?) {
        indexedBatches.append(items)
        completionHandler?(indexError)
    }

    func deleteSearchableItems(withIdentifiers identifiers: [String],
                               completionHandler: ((Error?) -> Void)?) {
        deletedIdentifiers.append(identifiers)
        completionHandler?(deleteError)
    }

    func deleteSearchableItems(withDomainIdentifiers domainIdentifiers: [String],
                               completionHandler: ((Error?) -> Void)?) {
        deletedDomains.append(domainIdentifiers)
        completionHandler?(deleteError)
    }
}

// MARK: - SpotlightServiceTests

final class SpotlightServiceTests: XCTestCase {

    // Each test gets its own service + mock to avoid shared state.
    private var service: SpotlightService!
    private var mockIndex: MockSpotlightIndex!

    override func setUp() {
        super.setUp()
        service = SpotlightService()
        mockIndex = MockSpotlightIndex()
        service.index = mockIndex
    }

    override func tearDown() {
        service = nil
        mockIndex = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeVault(
        id: String = "vault-abc123",
        balance: Int64 = 5_000_000,   // 0.5 XLM
        ttlRemaining: UInt64? = 3_600, // 1 hour in seconds
        status: Vault.VaultStatus = .active,
        assetCode: String = "XLM"
    ) -> Vault {
        Vault(
            id: id,
            owner: "GOWNER",
            beneficiary: "GBENEF",
            balance: balance,
            checkInInterval: 86_400,
            lastCheckIn: Date(),
            ttlRemaining: ttlRemaining,
            status: status,
            assetCode: assetCode
        )
    }

    // MARK: - donate(vault:)

    func test_donate_indexesSingleItem() {
        let vault = makeVault(id: "abc123")
        service.donate(vault: vault)

        XCTAssertEqual(mockIndex.indexedBatches.count, 1, "donate should trigger exactly one indexing call")
        XCTAssertEqual(mockIndex.indexedBatches[0].count, 1, "donate should index exactly one item")
    }

    func test_donate_usesCorrectUniqueIdentifier() {
        let vault = makeVault(id: "abc123")
        service.donate(vault: vault)

        let item = mockIndex.indexedBatches[0][0]
        XCTAssertEqual(item.uniqueIdentifier, "vault-abc123")
    }

    func test_donate_usesCorrectDomainIdentifier() {
        let vault = makeVault()
        service.donate(vault: vault)

        let item = mockIndex.indexedBatches[0][0]
        XCTAssertEqual(item.domainIdentifier, "com.ethosprotocol.vaults")
    }

    func test_donate_titleIsTruncatedToSixtyFourCharacters() {
        let longID = String(repeating: "x", count: 100)
        let vault = makeVault(id: longID)
        service.donate(vault: vault)

        let item = mockIndex.indexedBatches[0][0]
        let title = item.attributeSet.title ?? ""
        XCTAssertLessThanOrEqual(title.count, 64, "Title must be truncated to 64 characters")
        XCTAssertEqual(title, String(repeating: "x", count: 64))
    }

    func test_donate_titleEqualsVaultID_whenShort() {
        let vault = makeVault(id: "short-id")
        service.donate(vault: vault)

        let item = mockIndex.indexedBatches[0][0]
        XCTAssertEqual(item.attributeSet.title, "short-id")
    }

    func test_donate_descriptionContainsTTLInfo() {
        let vault = makeVault(id: "v1", ttlRemaining: 3_600)
        service.donate(vault: vault)

        let description = mockIndex.indexedBatches[0][0].attributeSet.contentDescription ?? ""
        XCTAssertTrue(description.contains("TTL:"), "Description must include TTL info; got: \(description)")
    }

    func test_donate_descriptionContainsBalance() {
        let vault = makeVault(id: "v1", balance: 10_000_000) // 1 XLM
        service.donate(vault: vault)

        let description = mockIndex.indexedBatches[0][0].attributeSet.contentDescription ?? ""
        XCTAssertTrue(description.contains("Balance:"), "Description must include balance; got: \(description)")
        XCTAssertTrue(description.contains("XLM"), "Description must include asset code; got: \(description)")
    }

    func test_donate_descriptionHandlesNilTTL() {
        let vault = makeVault(id: "v1", ttlRemaining: nil)
        service.donate(vault: vault)

        let description = mockIndex.indexedBatches[0][0].attributeSet.contentDescription ?? ""
        XCTAssertTrue(description.contains("N/A"), "Description should show N/A when TTL is absent; got: \(description)")
    }

    // MARK: - donateAll(vaults:)

    func test_donateAll_batchesItemsInSingleCall() {
        let vaults = (1...5).map { makeVault(id: "vault-\($0)") }
        service.donateAll(vaults: vaults)

        XCTAssertEqual(mockIndex.indexedBatches.count, 1, "donateAll should issue a single batch index call")
        XCTAssertEqual(mockIndex.indexedBatches[0].count, 5, "All vaults must be indexed in one batch")
    }

    func test_donateAll_assignsCorrectIdentifiersToEachVault() {
        let vaults = [makeVault(id: "alpha"), makeVault(id: "beta"), makeVault(id: "gamma")]
        service.donateAll(vaults: vaults)

        let identifiers = Set(mockIndex.indexedBatches[0].map { $0.uniqueIdentifier })
        XCTAssertEqual(identifiers, ["vault-alpha", "vault-beta", "vault-gamma"])
    }

    func test_donateAll_noopForEmptyArray() {
        service.donateAll(vaults: [])
        XCTAssertEqual(mockIndex.indexedBatches.count, 0, "donateAll([]) must not make any index calls")
    }

    func test_donateAll_eachItemHasCorrectDomain() {
        let vaults = [makeVault(id: "x"), makeVault(id: "y")]
        service.donateAll(vaults: vaults)

        let domains = Set(mockIndex.indexedBatches[0].map { $0.domainIdentifier ?? "" })
        XCTAssertEqual(domains, ["com.ethosprotocol.vaults"],
                       "Every indexed item must belong to the com.ethosprotocol.vaults domain")
    }

    // MARK: - revoke(vaultID:)

    func test_revoke_callsDeleteWithCorrectIdentifier() {
        service.revoke(vaultID: "abc123")

        XCTAssertEqual(mockIndex.deletedIdentifiers.count, 1)
        XCTAssertEqual(mockIndex.deletedIdentifiers[0], ["vault-abc123"])
    }

    func test_revoke_doesNotCallDomainDeletion() {
        service.revoke(vaultID: "some-vault")
        XCTAssertEqual(mockIndex.deletedDomains.count, 0,
                       "revoke should delete by identifier, not by domain")
    }

    func test_revoke_doesNotIndexAnything() {
        service.revoke(vaultID: "some-vault")
        XCTAssertEqual(mockIndex.indexedBatches.count, 0)
    }

    // MARK: - revokeAll()

    func test_revokeAll_deletesByDomainIdentifier() {
        service.revokeAll()

        XCTAssertEqual(mockIndex.deletedDomains.count, 1)
        XCTAssertEqual(mockIndex.deletedDomains[0], ["com.ethosprotocol.vaults"])
    }

    func test_revokeAll_doesNotCallIdentifierDeletion() {
        service.revokeAll()
        XCTAssertEqual(mockIndex.deletedIdentifiers.count, 0,
                       "revokeAll should delete by domain, not by individual identifiers")
    }

    // MARK: - makeSearchableItem

    func test_makeSearchableItem_setsKeywordsIncludingOwnerAndAssetCode() {
        let vault = makeVault(id: "kw-vault", assetCode: "USDC")
        // Manually set the owner via a known value — our factory sets it to "GOWNER".
        let item = service.makeSearchableItem(for: vault)

        let keywords = item.attributeSet.keywords ?? []
        XCTAssertTrue(keywords.contains("GOWNER"), "Keywords should include vault owner")
        XCTAssertTrue(keywords.contains("USDC"), "Keywords should include asset code")
        XCTAssertTrue(keywords.contains("vault"), "Keywords should include 'vault'")
        XCTAssertTrue(keywords.contains("ethos"), "Keywords should include 'ethos'")
    }

    func test_makeSearchableItem_setsExpirationDateFromTTL() {
        let ttl: UInt64 = 7_200 // 2 hours
        let vault = makeVault(ttlRemaining: ttl)
        let before = Date()
        let item = service.makeSearchableItem(for: vault)
        let after = Date()

        guard let expiry = item.expirationDate else {
            XCTFail("expirationDate should be set when vault has a TTL")
            return
        }
        // expiry should be approximately `before + ttl` seconds.
        let expectedMin = before.addingTimeInterval(TimeInterval(ttl) - 1)
        let expectedMax = after.addingTimeInterval(TimeInterval(ttl) + 1)
        XCTAssertTrue(expiry >= expectedMin && expiry <= expectedMax,
                      "expirationDate should be ≈ now + ttl seconds; got \(expiry)")
    }

    func test_makeSearchableItem_noExpirationDateWhenTTLIsNil() {
        let vault = makeVault(ttlRemaining: nil)
        let item = service.makeSearchableItem(for: vault)
        XCTAssertNil(item.expirationDate,
                     "expirationDate must not be set when vault has no TTL")
    }

    // MARK: - NSUserActivity properties

    func test_makeUserActivity_activityType() {
        let vault = makeVault(id: "ua-vault")
        let activity = service.makeUserActivity(for: vault)
        XCTAssertEqual(activity.activityType, "com.ethosprotocol.viewVault")
    }

    func test_makeUserActivity_userInfoContainsVaultID() {
        let vault = makeVault(id: "ua-vault")
        let activity = service.makeUserActivity(for: vault)

        let extractedID = activity.userInfo?["vaultID"] as? String
        XCTAssertEqual(extractedID, "ua-vault",
                       "userInfo[\"vaultID\"] must equal the vault's id")
    }

    func test_makeUserActivity_isEligibleForSearch() {
        let vault = makeVault(id: "search-vault")
        let activity = service.makeUserActivity(for: vault)
        XCTAssertTrue(activity.isEligibleForSearch,
                      "isEligibleForSearch must be true so the activity surfaces in Spotlight")
    }

    func test_makeUserActivity_isEligibleForPrediction() {
        let vault = makeVault(id: "pred-vault")
        let activity = service.makeUserActivity(for: vault)
        XCTAssertTrue(activity.isEligibleForPrediction,
                      "isEligibleForPrediction must be true for Siri prediction support")
    }

    func test_makeUserActivity_titleMatchesTruncatedID() {
        let vault = makeVault(id: "title-vault")
        let activity = service.makeUserActivity(for: vault)
        XCTAssertEqual(activity.title, "title-vault")
    }

    func test_makeUserActivity_longIDTitleTruncatedToSixtyFour() {
        let longID = String(repeating: "a", count: 80)
        let vault = makeVault(id: longID)
        let activity = service.makeUserActivity(for: vault)
        XCTAssertEqual(activity.title?.count, 64,
                       "NSUserActivity title must be truncated to 64 characters")
    }

    // MARK: - Static identifier helpers

    func test_uniqueIdentifier_format() {
        XCTAssertEqual(SpotlightService.uniqueIdentifier(for: "xyz"), "vault-xyz")
    }

    func test_activityType_constant() {
        XCTAssertEqual(SpotlightService.activityType, "com.ethosprotocol.viewVault")
    }

    func test_domainIdentifier_constant() {
        XCTAssertEqual(SpotlightService.domainIdentifier, "com.ethosprotocol.vaults")
    }

    // MARK: - Singleton

    func test_shared_isSingleton() {
        XCTAssertTrue(SpotlightService.shared === SpotlightService.shared,
                      "SpotlightService.shared must always resolve to the same instance")
    }

    // MARK: - Error tolerance (non-fatal logging)

    func test_donate_doesNotThrowOnIndexError() {
        // Even if the index returns an error, the call must complete without crashing.
        mockIndex.indexError = NSError(domain: "com.test", code: 1)
        let vault = makeVault()
        // This should not crash or propagate.
        service.donate(vault: vault)
        XCTAssertEqual(mockIndex.indexedBatches.count, 1, "donate must still call the index even if it will fail")
    }

    func test_revoke_doesNotThrowOnDeleteError() {
        mockIndex.deleteError = NSError(domain: "com.test", code: 2)
        service.revoke(vaultID: "any-id")
        XCTAssertEqual(mockIndex.deletedIdentifiers.count, 1, "revoke must still call delete even if it will fail")
    }
}
