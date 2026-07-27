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
 *
 * All I/O and system-property reads are injected via function parameters so the
 * class can be tested without a real device.
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
    }
) {

    // ── Public API ──────────────────────────────────────────────────────────

    val isRooted: Boolean
        get() = checkSuBinary()
            || checkRootApps()
            || checkRwSystemPartition()
            || checkTestKeys()
            || checkDebuggableProp()

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
}
