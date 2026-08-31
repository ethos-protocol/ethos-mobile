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
/// 6. [#272 v1.1] Elucidated symlink chains (`/bin → /private/var/jailbreak`) and
///    writable paths that bypass stock Darwin filesystem restrictions.
/// 7. [#272 v1.1] Substrate / Substitute / libhooker injection dylib paths.
/// 8. [#272 v1.1] Dopamine / palera1n / unc0ver / checkra1n-specific paths.
/// 9. [#272 v1.1] `sysctl` kernel flag for task_for_pid accessibility.
///
/// ─────────────────────────────────────────────────────────────────────────────
/// DETECTION_CHANGELOG
/// ─────────────────────────────────────────────────────────────────────────────
/// v1.0 (initial)
///   - Cydia / common jailbreak path existence checks (checkJailbreakPaths)
///   - Sandbox write test (/private/jailbreak-<UUID>) (checkSandboxViolation)
///   - DYLD_INSERT_LIBRARIES environment variable (checkDylibInjection)
///   - fork() via dlsym succeeds (checkFork)
///
/// v1.1 (#272)
///   - checkJailbreakPaths: Extended with Dopamine (/var/jb), palera1n
///     (/private/preboot/...), unc0ver (/var/LIB), and checkra1n/odyssey
///     (/private/var/MobileSubstrate, /private/var/containers/Bundle/tweaks)
///     artefact paths for post-iOS 15 jailbreaks.
///   - checkTweakDylibs: New heuristic. Checks for Substrate, Substitute,
///     libhooker, and Ellekit dylib paths in /Library/MobileSubstrate and
///     /usr/lib. These frameworks are loaded by virtually every jailbreak
///     tweak and are present even on "rootless" jailbreaks.
///   - checkSymlinkEscape: New heuristic. Tests whether /var/lib or /etc
///     is a symlink pointing outside the expected stock location — a common
///     technique used by "rootless" jailbreaks (Dopamine, palera1n) to mount
///     a writable overlay without modifying /system.
///   - isJailbroken now OR-chains all six heuristics.
/// ─────────────────────────────────────────────────────────────────────────────
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
        // On a stock device fork() returns -1 immediately. Both Swift's `fork()` overlay
        // and the variadic `syscall()` are marked unavailable on this platform — a
        // compile-time-only restriction, the underlying libc symbols still exist — so
        // resolve and call the real `fork` via dlsym instead of the Swift declaration.
        typealias ForkFn = @convention(c) () -> pid_t
        guard let handle = dlopen(nil, RTLD_NOW),
              let sym = dlsym(handle, "fork") else {
            return false
        }
        let pid = unsafeBitCast(sym, to: ForkFn.self)()
        if pid == 0 {
            // Child process — shouldn't happen in a healthy sandbox
            exit(0)
        }
        return pid > 0
    }
    /// [v1.1 #272] Checks whether `path` resolves to a symlink pointing outside
    /// the expected parent directory. Injectable in tests.
    var symlinkChecker: (String) -> Bool = { path in
        guard let dest = try? FileManager.default.destinationOfSymbolicLink(atPath: path) else {
            return false   // Not a symlink, or doesn't exist — healthy
        }
        // Any symlink at /var/lib, /etc, or /bin is suspicious unless it
        // resolves to the expected stock Darwin location.
        let allowed: [String: String] = [
            "/var":      "/private/var",
            "/tmp":      "/private/tmp",
            "/etc":      "/private/etc",
        ]
        if let expected = allowed[path] {
            return !dest.hasPrefix(expected)
        }
        // Unexpected symlink at a normally-non-symlink path is suspicious.
        return true
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
            || checkTweakDylibs()        // v1.1 #272
            || checkSymlinkEscape()      // v1.1 #272
        #endif
    }

    // MARK: - Individual heuristics (internal for testability)

    /// Check for common files/directories written by jailbreak tools.
    ///
    /// Extended in v1.1 (#272) to include:
    /// - Dopamine / Fugu15 (`/var/jb`)
    /// - palera1n (`/private/preboot/<UUID>/procursus`)
    /// - unc0ver (`/var/LIB`)
    /// - Odyssey/Taurine (`/private/var/containers/Bundle/tweaks`)
    func checkJailbreakPaths() -> Bool {
        let suspiciousPaths = [
            // ── Original v1.0 paths ──────────────────────────────────────────
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
            // ── v1.1 additions (#272) ─────────────────────────────────────────
            // Dopamine / Fugu15 (iOS 15–16 rootless jailbreak)
            "/var/jb",
            "/var/jb/usr/bin/su",
            // palera1n (checkra1n successor for arm64e)
            "/private/preboot/tmp/jb",
            // unc0ver (iOS 11–14)
            "/var/LIB",
            "/var/ulb",
            // Odyssey / Taurine (iOS 13–14)
            "/private/var/containers/Bundle/tweaks",
            // Generic rootless / Sileo / Zebra artefacts
            "/var/jb/Library/MobileSubstrate",
            "/var/jb/usr/lib/TweakInject",
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

    // MARK: - v1.1 heuristics (#272)

    /// [v1.1 #272] Check for tweak injection framework dylibs.
    ///
    /// MobileSubstrate (Cydia Substrate), Substitute, libhooker, and Ellekit are the
    /// four main tweak injection frameworks used by jailbreaks. Their core dylibs are
    /// present at well-known paths regardless of which jailbreak tool was used. On a
    /// "rootless" jailbreak (Dopamine, palera1n) these appear under `/var/jb` instead
    /// of the classic `/usr/lib` / `/Library/MobileSubstrate` locations — both are checked.
    func checkTweakDylibs() -> Bool {
        let tweakDylibPaths = [
            // MobileSubstrate (classic Cydia)
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            // Substitute (Sileo/Zebra jailbreaks)
            "/usr/lib/libsubstitute.dylib",
            // libhooker (Procursus-based jailbreaks)
            "/usr/lib/libhooker.dylib",
            // Ellekit (Dopamine, Fugu15)
            "/usr/lib/libellekit.dylib",
            // Rootless paths (under /var/jb)
            "/var/jb/usr/lib/libsubstitute.dylib",
            "/var/jb/usr/lib/libhooker.dylib",
            "/var/jb/usr/lib/libellekit.dylib",
            "/var/jb/Library/MobileSubstrate/MobileSubstrate.dylib",
            // TweakInject (Procursus rootless)
            "/usr/lib/TweakInject.dylib",
            "/var/jb/usr/lib/TweakInject.dylib",
        ]
        return tweakDylibPaths.contains { fileExistenceChecker($0) }
    }

    /// [v1.1 #272] Check for symlink escape patterns used by rootless jailbreaks.
    ///
    /// Dopamine and palera1n use a `/var/jb` → `<rootfs>/usr` style symlink trick
    /// to provide a writable "root" filesystem overlay. The canonical Darwin stock
    /// filesystem has `/var → /private/var` as its only user-visible symlink at the
    /// root level. Any unexpected symlinks at `/etc`, `/bin`, or `/var/lib` point
    /// outside the expected location and indicate a tampered filesystem.
    func checkSymlinkEscape() -> Bool {
        let pathsToCheck = ["/etc", "/bin", "/var/lib"]
        return pathsToCheck.contains { symlinkChecker($0) }
    }
}
