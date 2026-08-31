import XCTest
@testable import EthosProtocol

// MARK: - #118 IntegrityService Tests

/// Tests for `IntegrityService` jailbreak-detection heuristics.
///
/// Each heuristic is tested in isolation by injecting controlled versions of the
/// file-system, sandbox, environment, fork, tweak-dylib, and symlink checks. The
/// `isJailbroken` computed property is covered by stubs that override all checks.
///
/// Note: `isJailbroken` short-circuits to `false` in Simulator builds at compile
/// time (`#if targetEnvironment(simulator)`), so the integration tests below
/// always pass in CI. The individual heuristic functions are tested directly
/// since they don't have the simulator guard.
///
/// Tests added in v1.1 (#272) cover:
/// - `checkTweakDylibs` (Substitute / libhooker / Ellekit)
/// - `checkSymlinkEscape` (/etc, /bin, /var/lib)
/// - Extended `checkJailbreakPaths` (Dopamine, palera1n, unc0ver, Odyssey)
final class IntegrityServiceTests: XCTestCase {

    // MARK: - Jailbreak path detection

    func test_checkJailbreakPaths_nonePresent_returnsFalse() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { _ in false }   // nothing exists

        XCTAssertFalse(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_cydiaPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/Applications/Cydia.app" }

        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_mobileSubstratePresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in
            path == "/Library/MobileSubstrate/MobileSubstrate.dylib"
        }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_sshPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/usr/bin/sshd" }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_aptPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/etc/apt" }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    // v1.1 (#272) — new jailbreak tool artefacts

    func test_checkJailbreakPaths_dopamineVarJb_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/var/jb" }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_palera1nPreboot_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/private/preboot/tmp/jb" }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_unc0verVarLIB_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/var/LIB" }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    func test_checkJailbreakPaths_odysseyTweaksBundle_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in
            path == "/private/var/containers/Bundle/tweaks"
        }
        XCTAssertTrue(svc.checkJailbreakPaths())
    }

    // MARK: - Sandbox violation detection

    func test_checkSandboxViolation_writeFails_returnsFalse() {
        let svc = IntegrityService.shared
        svc.sandboxWriteChecker = { false }   // write rejected — healthy sandbox

        XCTAssertFalse(svc.checkSandboxViolation())
    }

    func test_checkSandboxViolation_writeSucceeds_returnsTrue() {
        let svc = IntegrityService.shared
        svc.sandboxWriteChecker = { true }    // write allowed — sandbox compromised

        XCTAssertTrue(svc.checkSandboxViolation())
    }

    // MARK: - Dylib injection detection

    func test_checkDylibInjection_envVarAbsent_returnsFalse() {
        let svc = IntegrityService.shared
        svc.environmentChecker = { _ in nil }

        XCTAssertFalse(svc.checkDylibInjection())
    }

    func test_checkDylibInjection_envVarPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.environmentChecker = { key in
            key == "DYLD_INSERT_LIBRARIES" ? "/usr/lib/tweakname.dylib" : nil
        }
        XCTAssertTrue(svc.checkDylibInjection())
    }

    // MARK: - Fork detection

    func test_checkFork_forkFails_returnsFalse() {
        let svc = IntegrityService.shared
        svc.forkChecker = { false }   // fork() returned -1 — healthy sandbox

        XCTAssertFalse(svc.checkFork())
    }

    func test_checkFork_forkSucceeds_returnsTrue() {
        let svc = IntegrityService.shared
        svc.forkChecker = { true }    // fork() returned > 0 — sandbox weakened

        XCTAssertTrue(svc.checkFork())
    }

    // MARK: - Tweak dylib detection (v1.1 #272)

    func test_checkTweakDylibs_noDylibsPresent_returnsFalse() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { _ in false }
        XCTAssertFalse(svc.checkTweakDylibs())
    }

    func test_checkTweakDylibs_substitutePresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/usr/lib/libsubstitute.dylib" }
        XCTAssertTrue(svc.checkTweakDylibs())
    }

    func test_checkTweakDylibs_libhookerPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/usr/lib/libhooker.dylib" }
        XCTAssertTrue(svc.checkTweakDylibs())
    }

    func test_checkTweakDylibs_ellekitPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/usr/lib/libellekit.dylib" }
        XCTAssertTrue(svc.checkTweakDylibs())
    }

    func test_checkTweakDylibs_rootlessSubstitutePresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/var/jb/usr/lib/libsubstitute.dylib" }
        XCTAssertTrue(svc.checkTweakDylibs())
    }

    func test_checkTweakDylibs_tweakInjectPresent_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/usr/lib/TweakInject.dylib" }
        XCTAssertTrue(svc.checkTweakDylibs())
    }

    // MARK: - Symlink escape detection (v1.1 #272)

    func test_checkSymlinkEscape_noSymlinks_returnsFalse() {
        let svc = IntegrityService.shared
        svc.symlinkChecker = { _ in false }
        XCTAssertFalse(svc.checkSymlinkEscape())
    }

    func test_checkSymlinkEscape_etcIsSymlink_returnsTrue() {
        let svc = IntegrityService.shared
        svc.symlinkChecker = { path in path == "/etc" }
        XCTAssertTrue(svc.checkSymlinkEscape())
    }

    func test_checkSymlinkEscape_binIsSymlink_returnsTrue() {
        let svc = IntegrityService.shared
        svc.symlinkChecker = { path in path == "/bin" }
        XCTAssertTrue(svc.checkSymlinkEscape())
    }

    func test_checkSymlinkEscape_varLibIsSymlink_returnsTrue() {
        let svc = IntegrityService.shared
        svc.symlinkChecker = { path in path == "/var/lib" }
        XCTAssertTrue(svc.checkSymlinkEscape())
    }

    // MARK: - isJailbroken integration

    /// On a healthy device (all heuristics negative), `isJailbroken` must be `false`.
    /// The `#if targetEnvironment(simulator)` guard in the production code ensures
    /// this test also passes in CI (simulator always returns false at compile time).
    func test_isJailbroken_allHeuristicsNegative_returnsFalse() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { _ in false }
        svc.sandboxWriteChecker = { false }
        svc.environmentChecker = { _ in nil }
        svc.forkChecker = { false }
        svc.symlinkChecker = { _ in false }

        // In simulator builds the property short-circuits to false, so this test
        // always passes in CI. On a real device it exercises all heuristics.
        XCTAssertFalse(svc.isJailbroken)
    }

    func test_isJailbroken_pathHeuristicFires_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/Applications/Cydia.app" }
        svc.sandboxWriteChecker = { false }
        svc.environmentChecker = { _ in nil }
        svc.forkChecker = { false }
        svc.symlinkChecker = { _ in false }

        // This will be true only on a device build; simulator always returns false.
        #if !targetEnvironment(simulator)
        XCTAssertTrue(svc.isJailbroken)
        #endif
    }

    func test_isJailbroken_tweakDylibHeuristicFires_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { path in path == "/usr/lib/libsubstitute.dylib" }
        svc.sandboxWriteChecker = { false }
        svc.environmentChecker = { _ in nil }
        svc.forkChecker = { false }
        svc.symlinkChecker = { _ in false }

        #if !targetEnvironment(simulator)
        XCTAssertTrue(svc.isJailbroken)
        #endif
    }

    func test_isJailbroken_symlinkEscapeHeuristicFires_returnsTrue() {
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { _ in false }
        svc.sandboxWriteChecker = { false }
        svc.environmentChecker = { _ in nil }
        svc.forkChecker = { false }
        svc.symlinkChecker = { path in path == "/etc" }

        #if !targetEnvironment(simulator)
        XCTAssertTrue(svc.isJailbroken)
        #endif
    }

    // MARK: - Singleton

    func test_shared_isSingleton() {
        XCTAssertTrue(IntegrityService.shared === IntegrityService.shared)
    }

    // MARK: - Teardown: restore original checkers after each test

    override func tearDown() {
        super.tearDown()
        // Restore production implementations so other tests are unaffected.
        let svc = IntegrityService.shared
        svc.fileExistenceChecker = { FileManager.default.fileExists(atPath: $0) }
        svc.sandboxWriteChecker = {
            let path = "/private/jailbreak-\(UUID().uuidString)"
            do {
                try "test".write(toFile: path, atomically: true, encoding: .utf8)
                try? FileManager.default.removeItem(atPath: path)
                return true
            } catch { return false }
        }
        svc.environmentChecker = { ProcessInfo.processInfo.environment[$0] }
        svc.forkChecker = {
            // `fork()` is marked unavailable at compile time on this platform (the
            // underlying libc symbol still exists) — resolve and call it via dlsym,
            // matching IntegrityService's production forkChecker.
            typealias ForkFn = @convention(c) () -> pid_t
            guard let handle = dlopen(nil, RTLD_NOW),
                  let sym = dlsym(handle, "fork") else {
                return false
            }
            let pid = unsafeBitCast(sym, to: ForkFn.self)()
            if pid == 0 { exit(0) }
            return pid > 0
        }
        svc.symlinkChecker = { path in
            guard let dest = try? FileManager.default.destinationOfSymbolicLink(atPath: path)
            else { return false }
            let allowed: [String: String] = [
                "/var": "/private/var",
                "/tmp": "/private/tmp",
                "/etc": "/private/etc",
            ]
            if let expected = allowed[path] { return !dest.hasPrefix(expected) }
            return true
        }
    }
}
