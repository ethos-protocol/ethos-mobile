import XCTest
@testable import EthosProtocol

// MARK: - #202 PendingTwoFactorSessionStore Tests

final class PendingTwoFactorSessionStoreTests: XCTestCase {

    @MainActor
    func test_session_returnsNilWhenNoneSaved() {
        let store = makeTestStore()
        XCTAssertNil(store.session(for: "vault-1"))
    }

    @MainActor
    func test_save_thenSession_returnsSavedSession() {
        let store = makeTestStore()
        let session = PendingTwoFactorSession(method: .sms, codeSent: true, createdAt: Date())

        store.save(session, for: "vault-1")

        XCTAssertEqual(store.session(for: "vault-1"), session)
    }

    @MainActor
    func test_session_isScopedPerVault() {
        let store = makeTestStore()
        let session = PendingTwoFactorSession(method: .email, codeSent: true, createdAt: Date())

        store.save(session, for: "vault-1")

        XCTAssertNil(store.session(for: "vault-2"),
            "A pending session for one vault must not leak to another")
    }

    @MainActor
    func test_clear_removesSession() {
        let store = makeTestStore()
        store.save(PendingTwoFactorSession(method: .totp, codeSent: true, createdAt: Date()), for: "vault-1")

        store.clear(for: "vault-1")

        XCTAssertNil(store.session(for: "vault-1"))
    }

    // ── Process-death restoration ────────────────────────────────────────────

    @MainActor
    func test_processDeath_freshInstanceRestoresSession() {
        let defaults = makeTestDefaults()
        let store1 = PendingTwoFactorSessionStore(defaults: defaults)
        let session = PendingTwoFactorSession(method: .sms, codeSent: true, createdAt: Date())
        store1.save(session, for: "vault-1")

        // Simulate the process being killed and relaunched.
        let store2 = PendingTwoFactorSessionStore(defaults: defaults)

        XCTAssertEqual(store2.session(for: "vault-1"), session,
            "A valid pending session must survive process death")
    }

    // ── Expiry of stale sessions ─────────────────────────────────────────────

    @MainActor
    func test_session_expiresAfterMaxAge() {
        var fakeNow = Date()
        let store = makeTestStore(now: { fakeNow })
        store.save(PendingTwoFactorSession(method: .totp, codeSent: true, createdAt: fakeNow), for: "vault-1")

        fakeNow = fakeNow.addingTimeInterval(PendingTwoFactorSessionStore.maxAge + 1)

        XCTAssertNil(store.session(for: "vault-1"),
            "A session older than maxAge must be treated as stale, not restored")
    }

    @MainActor
    func test_session_stillValidJustBeforeMaxAge() {
        var fakeNow = Date()
        let store = makeTestStore(now: { fakeNow })
        store.save(PendingTwoFactorSession(method: .totp, codeSent: true, createdAt: fakeNow), for: "vault-1")

        fakeNow = fakeNow.addingTimeInterval(PendingTwoFactorSessionStore.maxAge - 1)

        XCTAssertNotNil(store.session(for: "vault-1"))
    }

    @MainActor
    func test_session_expiry_clearsTheStaleEntry() {
        var fakeNow = Date()
        let defaults = makeTestDefaults()
        let store1 = PendingTwoFactorSessionStore(defaults: defaults, now: { fakeNow })
        store1.save(PendingTwoFactorSession(method: .totp, codeSent: true, createdAt: fakeNow), for: "vault-1")

        fakeNow = fakeNow.addingTimeInterval(PendingTwoFactorSessionStore.maxAge + 1)
        XCTAssertNil(store1.session(for: "vault-1"))

        // Once expired and pruned, a fresh reader (post-relaunch) must not see it either.
        let store2 = PendingTwoFactorSessionStore(defaults: defaults, now: { fakeNow })
        XCTAssertNil(store2.session(for: "vault-1"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private func makeTestDefaults() -> UserDefaults {
        let suiteName = "PendingTwoFactorSessionStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return defaults
    }

    @MainActor
    private func makeTestStore(now: (() -> Date)? = nil) -> PendingTwoFactorSessionStore {
        PendingTwoFactorSessionStore(defaults: makeTestDefaults(), now: now ?? { Date() })
    }
}
