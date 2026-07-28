import XCTest
@testable import EthosProtocol

// MARK: - Issue #27: NetworkMonitor Cold-Start Tests

final class NetworkMonitorColdStartTests: XCTestCase {

    private struct MockPathProvider: NetworkPathProvider {
        let isCurrentlySatisfied: Bool
        func startMonitoring(_ handler: @escaping (Bool) -> Void) {
            // Intentionally never calls `handler` — these tests assert on the synchronous
            // snapshot NetworkMonitor reads at init, before any async path update could fire.
        }
    }

    func test_coldStart_offlineDevice_reportsDisconnectedImmediately() {
        let monitor = NetworkMonitor(provider: MockPathProvider(isCurrentlySatisfied: false))
        // No async path update ever fires in this test — if `isConnected` were still
        // defaulting to `true` at this point, the very first request made right after
        // launch would wrongly assume connectivity instead of using the offline cache path.
        XCTAssertFalse(monitor.isConnected)
    }

    func test_coldStart_onlineDevice_reportsConnectedImmediately() {
        let monitor = NetworkMonitor(provider: MockPathProvider(isCurrentlySatisfied: true))
        XCTAssertTrue(monitor.isConnected)
    }

    func test_pathUpdate_afterColdStart_updatesIsConnected() {
        final class RecordingProvider: NetworkPathProvider {
            let isCurrentlySatisfied = true
            var handler: ((Bool) -> Void)?
            func startMonitoring(_ handler: @escaping (Bool) -> Void) { self.handler = handler }
        }
        let provider = RecordingProvider()
        let monitor = NetworkMonitor(provider: provider)
        XCTAssertTrue(monitor.isConnected)

        provider.handler?(false)
        XCTAssertFalse(monitor.isConnected)
    }
}

// MARK: - Issue #25 / #26: OfflineCache Expiry, Age, and Eviction Tests

final class OfflineCacheExpiryTests: XCTestCase {

    override func tearDown() {
        OfflineCache.shared.maxAge = nil
        OfflineCache.shared.maxBytes = 20 * 1_024 * 1_024
        super.tearDown()
    }

    func test_age_forFreshEntry_isNearZero() {
        let key = "age-fresh-\(UUID())"
        OfflineCache.shared.save(Data("x".utf8), for: key)
        let age = OfflineCache.shared.age(for: key)
        XCTAssertNotNil(age)
        XCTAssertLessThan(age ?? .infinity, 2)
    }

    func test_age_forMissingKey_isNil() {
        XCTAssertNil(OfflineCache.shared.age(for: "missing-\(UUID())"))
    }

    func test_cachedAt_isRoughlyNow() {
        let key = "cachedat-\(UUID())"
        let before = Date()
        OfflineCache.shared.save(Data("x".utf8), for: key)
        let cachedAt = OfflineCache.shared.cachedAt(for: key)
        XCTAssertNotNil(cachedAt)
        XCTAssertGreaterThanOrEqual(cachedAt ?? .distantPast, before.addingTimeInterval(-1))
    }

    func test_load_entryOlderThanMaxAge_returnsNil() {
        let key = "expired-\(UUID())"
        OfflineCache.shared.save(Data("stale".utf8), for: key)
        // Backdate the entry directly rather than sleeping in the test.
        setCachedAt(Date().addingTimeInterval(-1000), for: key)

        OfflineCache.shared.maxAge = 500
        XCTAssertNil(OfflineCache.shared.load(for: key))
    }

    func test_load_entryWithinMaxAge_stillReturnsData() {
        let key = "fresh-within-ttl-\(UUID())"
        let data = Data("still good".utf8)
        OfflineCache.shared.save(data, for: key)

        OfflineCache.shared.maxAge = 3_600
        XCTAssertEqual(OfflineCache.shared.load(for: key), data)
    }

    func test_load_withNoMaxAgeSet_neverExpires() {
        let key = "no-ttl-\(UUID())"
        OfflineCache.shared.save(Data("x".utf8), for: key)
        setCachedAt(Date().addingTimeInterval(-1_000_000), for: key)

        XCTAssertNil(OfflineCache.shared.maxAge)
        XCTAssertNotNil(OfflineCache.shared.load(for: key))
    }

    private func setCachedAt(_ date: Date, for key: String) {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        let metaFile = dir.appendingPathComponent(key.sha256Hex + ".meta")
        try? date.timeIntervalSince1970.description.data(using: .utf8)?.write(to: metaFile)
    }
}

final class OfflineCacheEvictionTests: XCTestCase {

    override func setUp() {
        super.setUp()
        OfflineCache.shared.clearAll()
    }

    override func tearDown() {
        OfflineCache.shared.maxBytes = 20 * 1_024 * 1_024
        OfflineCache.shared.clearAll()
        super.tearDown()
    }

    func test_saveBeyondCap_evictsLeastRecentlyUsedFirst() {
        // Three ~1KB entries, capped at ~2KB — the third save must evict exactly one entry.
        OfflineCache.shared.maxBytes = 2_200
        let payload = Data(repeating: 0x41, count: 1_000)

        OfflineCache.shared.save(payload, for: "lru-a")
        OfflineCache.shared.save(payload, for: "lru-b")
        // Set mtimes explicitly rather than relying on OS mtime resolution between two saves
        // microseconds apart, which can collide and make ordering nondeterministic.
        setDataFileModified(Date(), for: "lru-a")
        setDataFileModified(Date().addingTimeInterval(-100), for: "lru-b")
        OfflineCache.shared.save(payload, for: "lru-c")

        XCTAssertNil(OfflineCache.shared.load(for: "lru-b"), "Least-recently-used entry should have been evicted")
        XCTAssertNotNil(OfflineCache.shared.load(for: "lru-a"), "Recently-touched entry should survive eviction")
        XCTAssertNotNil(OfflineCache.shared.load(for: "lru-c"), "Newly-written entry should survive eviction")
    }

    private func setDataFileModified(_ date: Date, for key: String) {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("EthosProtocolOfflineCache", isDirectory: true)
        let dataFile = dir.appendingPathComponent(key.sha256Hex)
        try? FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: dataFile.path)
    }

    func test_saveUnderCap_evictsNothing() {
        OfflineCache.shared.maxBytes = 1_024 * 1_024
        let payload = Data(repeating: 0x42, count: 100)
        OfflineCache.shared.save(payload, for: "small-a")
        OfflineCache.shared.save(payload, for: "small-b")

        XCTAssertNotNil(OfflineCache.shared.load(for: "small-a"))
        XCTAssertNotNil(OfflineCache.shared.load(for: "small-b"))
    }
}

final class OfflineCacheSignOutTests: XCTestCase {
    func test_clearAll_removesPreviouslySavedEntries() {
        let key = "signout-\(UUID())"
        OfflineCache.shared.save(Data("secret".utf8), for: key)
        XCTAssertNotNil(OfflineCache.shared.load(for: key))

        OfflineCache.shared.clearAll()

        XCTAssertNil(OfflineCache.shared.load(for: key))
    }
}
