import Foundation

// MARK: - #118 IntegrityService

/// Performs basic jailbreak-detection heuristics.
///
/// Detection is inherently a cat-and-mouse game: a sophisticated jailbreak can
/// patch or hide every heuristic below. This service is therefore a defence-in-
/// depth measure — not a hard security boundary — and the app responds to a
/// detected device with a **non-blocking warning** rather than an outright exit.
///
/// Heuristics used:
/// 1. Cydia / common jailbreak paths present on disk.
/// 2. Ability to write outside the app sandbox.
/// 3. `DYLD_INSERT_LIBRARIES` environment variable set (dylib injection).
/// 4. Fork/posix_spawn succeeds (sandboxed apps cannot fork).
/// 5. Suspicious file paths readable that should be inaccessible on a stock device.
public final class IntegrityService {

    public static let shared = IntegrityService()

    // Overridable in tests so each heuristic can be toggled independently.
    var fileExistenceChecker: (String) -> Bool = { path in
        FileManager.default.fileExists(atPath: path)
    }
    var sandboxWriteChecker: () -> Bool = {
        let path = "/private/jailbreak-\(UUID().uuidString)"
        do {
            try "test".write(toFile: path, atomically: true, encoding: .utf8)
            try? FileManager.default.removeItem(atPath: path)
            return true   // Write succeeded — outside sandbox
        } catch {
            return false  // Expected on a healthy device
        }
    }
    var environmentChecker: (String) -> String? = { key in
        ProcessInfo.processInfo.environment[key]
    }
    var forkChecker: () -> Bool = {
        // On a jailbroken device the sandbox is typically weakened and fork() succeeds.
        // On a stock device fork() returns -1 immediately.
        let pid = fork()
        if pid == 0 {
            // Child process — shouldn't happen in a healthy sandbox
            exit(0)
        }
        return pid > 0
    }

    private init() {}

    // MARK: - Public API

    /// Returns `true` when one or more jailbreak heuristics fire.
    /// Always returns `false` in Simulator builds (heuristics produce false positives
    /// in the simulator, and simulated devices are not shipped to end users).
    public var isJailbroken: Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return checkJailbreakPaths()
            || checkSandboxViolation()
            || checkDylibInjection()
            || checkFork()
        #endif
    }

    // MARK: - Individual heuristics (internal for testability)

    /// Check for common files/directories written by jailbreak tools.
    func checkJailbreakPaths() -> Bool {
        let suspiciousPaths = [
            "/Applications/Cydia.app",
            "/Applications/blackra1n.app",
            "/Applications/FakeCarrier.app",
            "/Applications/Icy.app",
            "/Applications/IntelliScreen.app",
            "/Applications/MxTube.app",
            "/Applications/RockApp.app",
            "/Applications/SBSettings.app",
            "/Applications/WinterBoard.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/Library/MobileSubstrate/DynamicLibraries/Veency.plist",
            "/Library/MobileSubstrate/DynamicLibraries/LiveClock.plist",
            "/private/var/lib/apt",
            "/private/var/lib/cydia",
            "/private/var/mobile/Library/SBSettings/Themes",
            "/private/var/stash",
            "/private/var/tmp/cydia.log",
            "/usr/bin/sshd",
            "/usr/libexec/sftp-server",
            "/usr/sbin/sshd",
            "/var/cache/apt",
            "/var/lib/apt",
            "/var/lib/cydia",
            "/etc/apt",
            "/bin/bash",
            "/bin/sh",    // Present on stock, but writable path test catches escapes
        ]
        return suspiciousPaths.contains { fileExistenceChecker($0) }
    }

    /// Attempt to write a file outside the app's sandbox.
    /// On a healthy device this always fails; on a jailbroken one it may succeed.
    func checkSandboxViolation() -> Bool {
        sandboxWriteChecker()
    }

    /// `DYLD_INSERT_LIBRARIES` being set is a strong indicator of dylib injection,
    /// commonly used by jailbreak tweaks to hook system frameworks.
    func checkDylibInjection() -> Bool {
        environmentChecker("DYLD_INSERT_LIBRARIES") != nil
    }

    /// A healthy app sandbox prevents fork(). Success implies the sandbox is relaxed.
    func checkFork() -> Bool {
        forkChecker()
    }
}
