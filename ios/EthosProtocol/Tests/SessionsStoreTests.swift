import XCTest
@testable import EthosProtocol

// MARK: - #208 Session/Device List Tests

@MainActor
final class SessionsStoreTests: XCTestCase {

    private func makeSession(id: String, isCurrent: Bool = false) -> Session {
        Session(id: id, deviceName: "Test Device \(id)", platform: "ios",
                createdAt: Date(), lastActiveAt: Date(), isCurrent: isCurrent)
    }

    func test_load_populatesSessions() async {
        let store = SessionsStore()
        let sessions = [makeSession(id: "1", isCurrent: true), makeSession(id: "2")]
        store.listSessions = { sessions }

        await store.load()

        XCTAssertEqual(store.sessions, sessions)
        XCTAssertNil(store.error)
    }

    func test_load_failure_setsError() async {
        let store = SessionsStore()
        store.listSessions = { throw APIError.networkUnavailable }

        await store.load()

        XCTAssertTrue(store.sessions.isEmpty)
        XCTAssertNotNil(store.error)
    }

    func test_revoke_removesSessionFromLocalListOnSuccess() async {
        let store = SessionsStore()
        let current = makeSession(id: "1", isCurrent: true)
        let other = makeSession(id: "2")
        store.sessions = [current, other]
        var revokedID: String?
        store.revokeSession = { id in revokedID = id }

        await store.revoke(other)

        XCTAssertEqual(revokedID, "2")
        XCTAssertEqual(store.sessions, [current])
    }

    func test_revoke_failure_keepsSessionAndSetsError() async {
        let store = SessionsStore()
        let session = makeSession(id: "2")
        store.sessions = [session]
        store.revokeSession = { _ in throw APIError.serverError("Not found") }

        await store.revoke(session)

        XCTAssertEqual(store.sessions, [session])
        XCTAssertNotNil(store.error)
    }

    func test_revokeAllOthers_reloadsListOnSuccess() async {
        let store = SessionsStore()
        var revokeOthersCalled = false
        store.revokeOtherSessions = { revokeOthersCalled = true }
        let reloaded = [makeSession(id: "1", isCurrent: true)]
        store.listSessions = { reloaded }

        await store.revokeAllOthers()

        XCTAssertTrue(revokeOthersCalled)
        XCTAssertEqual(store.sessions, reloaded)
    }

    func test_revokeAllOthers_failure_setsError() async {
        let store = SessionsStore()
        store.revokeOtherSessions = { throw APIError.networkUnavailable }

        await store.revokeAllOthers()

        XCTAssertNotNil(store.error)
    }
}
