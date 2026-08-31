package com.ethosprotocol.services

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * #118 — Root-detection heuristics for Android.
 *
 * Root detection is a defence-in-depth measure, not a hard security boundary.
 * A sophisticated root can hide every heuristic below. The app responds to a
 * detected device with a **non-blocking warning** rather than a hard exit.
 *
 * Heuristics:
 * 1. `su` binary present on PATH or in common root directories.
 * 2. Well-known root-management app packages installed (Magisk, SuperSU, etc.).
 * 3. `/system` partition mounted read-write (rw in `/proc/mounts`).
 * 4. Test-keys build tag — production devices use release-keys.
 * 5. `ro.debuggable` system property set to "1".
 * 6. [#272] Writable system paths that should always be read-only (overlay mounts).
 * 7. [#272] Suspicious Zygisk/Magisk module paths and marker files.
 * 8. [#272] Magisk-specific packages and hidden manager variants.
 * 9. [#272] Known root binary names beyond `su` (busybox variants, daemonsu).
 *
 * All I/O and system-property reads are injected via function parameters so the
 * class can be tested without a real device.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DETECTION_CHANGELOG
 * ─────────────────────────────────────────────────────────────────────────────
 * v1.0 (initial)
 *   - su binary detection in common paths
 *   - Known root app package detection (Magisk, SuperSU, KingRoot, etc.)
 *   - /system rw mount detection via /proc/mounts
 *   - test-keys build tag
 *   - ro.debuggable=1
 *
 * v1.1 (#272)
 *   - Added checkWritableSystemPaths(): tests whether /system or /system/bin
 *     is writable. A writable /system is a definitive root indicator even when
 *     /proc/mounts has been tampered to hide the rw flag.
 *   - Added checkZygiskMagiskPaths(): scans for Zygisk loader paths
 *     (/data/adb/modules, /data/adb/magisk), Magisk tmpfs mounts, and the
 *     zygisk.enabled property — covering Magisk v24+ (Zygisk mode) which
 *     no longer places binaries in /sbin.
 *   - Added checkMagiskHiddenPackages(): detects renamed Magisk manager APKs
 *     (com.topjohnwu.magisk.*, io.github.huskydg.magisk) and the stub APK
 *     package name com.topjohnwu.magisk.stub that some "hide" installations use.
 *   - Added checkRootBinaries(): looks for busybox, daemonsu, and other root
 *     companion binaries that indicate a rooted environment even when `su`
 *     itself has been renamed.
 *   - isRooted now OR-chains all nine heuristics.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class IntegrityChecker(
    private val context: Context,
    // Overridable in tests
    internal var fileExistsChecker: (String) -> Boolean = { File(it).exists() },
    internal var packageChecker: (String) -> Boolean = { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    },
    internal var mountsReader: () -> String = {
        try { File("/proc/mounts").readText() } catch (e: Exception) { "" }
    },
    internal var buildTagsReader: () -> String = {
        android.os.Build.TAGS ?: ""
    },
    internal var systemPropertyReader: (String) -> String = { key ->
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as? String ?: ""
        } catch (e: Exception) { "" }
    },
    // [#272 v1.1] Injected canWrite check, separate from fileExistsChecker so that
    // tests can simulate "file exists and is writable" without needing real I/O.
    internal var canWriteChecker: (String) -> Boolean = { path ->
        try { File(path).canWrite() } catch (e: Exception) { false }
    }
) {

    // ── Public API ──────────────────────────────────────────────────────────

    val isRooted: Boolean
        get() = checkSuBinary()
            || checkRootApps()
            || checkRwSystemPartition()
            || checkTestKeys()
            || checkDebuggableProp()
            || checkWritableSystemPaths()     // v1.1 #272
            || checkZygiskMagiskPaths()       // v1.1 #272
            || checkMagiskHiddenPackages()    // v1.1 #272
            || checkRootBinaries()            // v1.1 #272

    // ── Individual heuristics (internal for testability) ────────────────────

    /** Look for `su` in common locations and on PATH. */
    internal fun checkSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/dev/com.koushikdutta.superuser.daemon/"
        )
        return paths.any { fileExistsChecker(it) }
    }

    /** Detect presence of well-known root management apps. */
    internal fun checkRootApps(): Boolean {
        val rootPackages = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot"
        )
        return rootPackages.any { packageChecker(it) }
    }

    /** `/system` mounted read-write is a strong root indicator. */
    internal fun checkRwSystemPartition(): Boolean {
        val mounts = mountsReader()
        return mounts.lines().any { line ->
            val parts = line.trim().split("\\s+".toRegex())
            // Format: <device> <mountpoint> <fstype> <options> …
            parts.size >= 4 && parts[1] == "/system" &&
                parts[3].split(",").contains("rw")
        }
    }

    /** Production ROM builds should be signed with release-keys, not test-keys. */
    internal fun checkTestKeys(): Boolean {
        return buildTagsReader().contains("test-keys")
    }

    /**
     * `ro.debuggable=1` is set on development/engineering builds and some roots
     * to enable adb root. It should be "0" on production devices.
     */
    internal fun checkDebuggableProp(): Boolean {
        return systemPropertyReader("ro.debuggable") == "1"
    }

    // ── v1.1 heuristics (#272) ──────────────────────────────────────────────

    /**
     * [v1.1 #272] Check whether critical read-only paths are actually writable.
     *
     * Even when `/proc/mounts` has been tampered to hide a rw remount
     * (a technique used by some systemless root overlays), attempting to write
     * to a normally-immutable path will succeed on a rooted device. This
     * heuristic detects that without actually writing anything.
     *
     * Paths checked:
     * - `/system`         — the primary system partition
     * - `/system/bin`     — system binary directory
     * - `/vendor`         — vendor partition (immutable on production devices)
     */
    internal fun checkWritableSystemPaths(): Boolean {
        val readOnlyPaths = listOf("/system", "/system/bin", "/vendor")
        return readOnlyPaths.any { path ->
            fileExistsChecker(path) && canWriteChecker(path)
        }
    }

    /**
     * [v1.1 #272] Detect Zygisk and Magisk v24+ module/marker paths.
     *
     * Magisk v24+ introduced Zygisk mode, which injects into the Zygote process
     * directly instead of placing binaries in `/sbin` (which is no longer
     * available on newer Android). The following paths are written by Magisk
     * during installation and at boot and are not present on unrooted devices:
     *
     * - `/data/adb/magisk`          — Magisk installation directory
     * - `/data/adb/modules`         — Magisk module directory
     * - `/data/adb/magisk.db`       — Magisk policy database
     * - `/data/adb/magisk_simple`   — alternative Magisk install layout
     * - `/sbin/.magisk`             — legacy Magisk tmpfs overlay (pre-v24)
     * - `/dev/.magisk`              — Magisk runtime directory (some versions)
     * - `/data/adb/ksu`             — KernelSU installation directory
     *
     * The `ro.zygisk.enable` system property is also checked: Zygisk sets it
     * to "1" at boot on Magisk v24+.
     */
    internal fun checkZygiskMagiskPaths(): Boolean {
        val magiskPaths = listOf(
            "/data/adb/magisk",
            "/data/adb/modules",
            "/data/adb/magisk.db",
            "/data/adb/magisk_simple",
            "/sbin/.magisk",
            "/dev/.magisk",
            "/data/adb/ksu"
        )
        if (magiskPaths.any { fileExistsChecker(it) }) return true
        // ro.zygisk.enable is set by Magisk Zygisk mode
        return systemPropertyReader("ro.zygisk.enable") == "1"
    }

    /**
     * [v1.1 #272] Detect hidden or renamed Magisk manager packages.
     *
     * The Magisk "hide" feature (and its successor "DenyList") can rename the
     * Magisk Manager APK to a random package name, defeating simple package-name
     * checks. However, several known patterns remain:
     *
     * - `io.github.huskydg.magisk`     — HuskyDG's Magisk Delta fork
     * - `io.github.vvb2060.magisk`     — patched Magisk variants
     * - `com.topjohnwu.magisk.stub`    — stub APK used by Magisk's hide feature
     * - `me.weishu.kernelsu`           — KernelSU manager
     * - `com.rovo89.xposedinstaller`   — Xposed Framework installer
     * - `de.robv.android.xposed.installer` — alternative Xposed package name
     */
    internal fun checkMagiskHiddenPackages(): Boolean {
        val suspiciousPackages = listOf(
            "io.github.huskydg.magisk",
            "io.github.vvb2060.magisk",
            "com.topjohnwu.magisk.stub",
            "me.weishu.kernelsu",
            "com.rovo89.xposedinstaller",
            "de.robv.android.xposed.installer"
        )
        return suspiciousPackages.any { packageChecker(it) }
    }

    /**
     * [v1.1 #272] Look for root companion binaries beyond `su` itself.
     *
     * Busybox, daemonsu (SuperSU's persistent daemon), and magiskinit are
     * present on rooted devices and indicate root even if `su` has been renamed.
     * KernelSU uses `ksud` as its userspace daemon.
     */
    internal fun checkRootBinaries(): Boolean {
        val rootBinaries = listOf(
            "/system/bin/busybox",
            "/system/xbin/busybox",
            "/sbin/busybox",
            "/data/local/xbin/busybox",
            "/system/bin/daemonsu",
            "/system/xbin/daemonsu",
            "/system/bin/magiskinit",
            "/data/adb/magisk/magiskinit",
            "/system/bin/ksud",
            "/data/adb/ksud"
        )
        return rootBinaries.any { fileExistsChecker(it) }
    }
}
