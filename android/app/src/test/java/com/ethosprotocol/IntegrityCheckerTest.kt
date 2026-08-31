package com.ethosprotocol

import android.content.Context
import com.ethosprotocol.services.IntegrityChecker
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #118 — Unit tests for [IntegrityChecker] root-detection heuristics.
 *
 * Each heuristic is tested in isolation by injecting controlled implementations
 * of the file, package, mounts, build-tags, and system-property readers.
 *
 * Tests added in v1.1 (#272) cover the four new heuristics:
 * - checkWritableSystemPaths
 * - checkZygiskMagiskPaths
 * - checkMagiskHiddenPackages
 * - checkRootBinaries
 */
class IntegrityCheckerTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var checker: IntegrityChecker

    @Before
    fun setup() {
        // Start with all checkers returning "clean" defaults.
        checker = IntegrityChecker(
            context = context,
            fileExistsChecker = { false },
            packageChecker = { false },
            mountsReader = { "" },
            buildTagsReader = { "release-keys" },
            systemPropertyReader = { "0" },
            canWriteChecker = { false }
        )
    }

    // ── su binary ──────────────────────────────────────────────────────────

    @Test
    fun `checkSuBinary no su paths exist returns false`() {
        checker.fileExistsChecker = { false }
        assertFalse(checker.checkSuBinary())
    }

    @Test
    fun `checkSuBinary system bin su exists returns true`() {
        checker.fileExistsChecker = { it == "/system/bin/su" }
        assertTrue(checker.checkSuBinary())
    }

    @Test
    fun `checkSuBinary system xbin su exists returns true`() {
        checker.fileExistsChecker = { it == "/system/xbin/su" }
        assertTrue(checker.checkSuBinary())
    }

    @Test
    fun `checkSuBinary sbin su exists returns true`() {
        checker.fileExistsChecker = { it == "/sbin/su" }
        assertTrue(checker.checkSuBinary())
    }

    // ── root apps ─────────────────────────────────────────────────────────

    @Test
    fun `checkRootApps no root packages installed returns false`() {
        checker.packageChecker = { false }
        assertFalse(checker.checkRootApps())
    }

    @Test
    fun `checkRootApps magisk installed returns true`() {
        checker.packageChecker = { it == "com.topjohnwu.magisk" }
        assertTrue(checker.checkRootApps())
    }

    @Test
    fun `checkRootApps supersu installed returns true`() {
        checker.packageChecker = { it == "eu.chainfire.supersu" }
        assertTrue(checker.checkRootApps())
    }

    // ── rw /system ────────────────────────────────────────────────────────

    @Test
    fun `checkRwSystemPartition no system entry returns false`() {
        checker.mountsReader = { "" }
        assertFalse(checker.checkRwSystemPartition())
    }

    @Test
    fun `checkRwSystemPartition system mounted ro returns false`() {
        checker.mountsReader = {
            "/dev/block/sda1 /system ext4 ro,seclabel,relatime 0 0"
        }
        assertFalse(checker.checkRwSystemPartition())
    }

    @Test
    fun `checkRwSystemPartition system mounted rw returns true`() {
        checker.mountsReader = {
            "/dev/block/sda1 /system ext4 rw,seclabel,relatime 0 0"
        }
        assertTrue(checker.checkRwSystemPartition())
    }

    @Test
    fun `checkRwSystemPartition non-system partition rw does not trigger`() {
        checker.mountsReader = {
            "/dev/block/sda2 /data ext4 rw,seclabel,relatime 0 0"
        }
        assertFalse(checker.checkRwSystemPartition())
    }

    // ── test-keys ─────────────────────────────────────────────────────────

    @Test
    fun `checkTestKeys release-keys returns false`() {
        checker.buildTagsReader = { "release-keys" }
        assertFalse(checker.checkTestKeys())
    }

    @Test
    fun `checkTestKeys test-keys returns true`() {
        checker.buildTagsReader = { "test-keys" }
        assertTrue(checker.checkTestKeys())
    }

    @Test
    fun `checkTestKeys null tags returns false`() {
        checker.buildTagsReader = { "" }
        assertFalse(checker.checkTestKeys())
    }

    // ── ro.debuggable ─────────────────────────────────────────────────────

    @Test
    fun `checkDebuggableProp value 0 returns false`() {
        checker.systemPropertyReader = { "0" }
        assertFalse(checker.checkDebuggableProp())
    }

    @Test
    fun `checkDebuggableProp value 1 returns true`() {
        checker.systemPropertyReader = { "1" }
        assertTrue(checker.checkDebuggableProp())
    }

    @Test
    fun `checkDebuggableProp empty string returns false`() {
        checker.systemPropertyReader = { "" }
        assertFalse(checker.checkDebuggableProp())
    }

    // ── checkWritableSystemPaths (v1.1 #272) ──────────────────────────────

    @Test
    fun `checkWritableSystemPaths system not writable returns false`() {
        checker.fileExistsChecker = { it == "/system" }
        checker.canWriteChecker = { false }
        assertFalse(checker.checkWritableSystemPaths())
    }

    @Test
    fun `checkWritableSystemPaths system writable returns true`() {
        checker.fileExistsChecker = { it == "/system" }
        checker.canWriteChecker = { it == "/system" }
        assertTrue(checker.checkWritableSystemPaths())
    }

    @Test
    fun `checkWritableSystemPaths system bin writable returns true`() {
        checker.fileExistsChecker = { it == "/system/bin" }
        checker.canWriteChecker = { it == "/system/bin" }
        assertTrue(checker.checkWritableSystemPaths())
    }

    @Test
    fun `checkWritableSystemPaths vendor writable returns true`() {
        checker.fileExistsChecker = { it == "/vendor" }
        checker.canWriteChecker = { it == "/vendor" }
        assertTrue(checker.checkWritableSystemPaths())
    }

    @Test
    fun `checkWritableSystemPaths path exists but not writable returns false`() {
        // Path exists but canWrite returns false — healthy read-only partition.
        checker.fileExistsChecker = { it in listOf("/system", "/system/bin", "/vendor") }
        checker.canWriteChecker = { false }
        assertFalse(checker.checkWritableSystemPaths())
    }

    @Test
    fun `checkWritableSystemPaths path does not exist returns false`() {
        checker.fileExistsChecker = { false }
        checker.canWriteChecker = { true }   // would fire if file existed
        assertFalse(checker.checkWritableSystemPaths())
    }

    // ── checkZygiskMagiskPaths (v1.1 #272) ────────────────────────────────

    @Test
    fun `checkZygiskMagiskPaths no magisk paths exist and no property returns false`() {
        checker.fileExistsChecker = { false }
        checker.systemPropertyReader = { "0" }
        assertFalse(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths data adb magisk exists returns true`() {
        checker.fileExistsChecker = { it == "/data/adb/magisk" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths data adb modules exists returns true`() {
        checker.fileExistsChecker = { it == "/data/adb/modules" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths sbin magisk marker exists returns true`() {
        checker.fileExistsChecker = { it == "/sbin/.magisk" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths dev magisk marker exists returns true`() {
        checker.fileExistsChecker = { it == "/dev/.magisk" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths ksu directory exists returns true`() {
        checker.fileExistsChecker = { it == "/data/adb/ksu" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths zygisk enable property set returns true`() {
        checker.fileExistsChecker = { false }
        checker.systemPropertyReader = { key -> if (key == "ro.zygisk.enable") "1" else "0" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    @Test
    fun `checkZygiskMagiskPaths magisk db exists returns true`() {
        checker.fileExistsChecker = { it == "/data/adb/magisk.db" }
        assertTrue(checker.checkZygiskMagiskPaths())
    }

    // ── checkMagiskHiddenPackages (v1.1 #272) ─────────────────────────────

    @Test
    fun `checkMagiskHiddenPackages no suspicious packages returns false`() {
        checker.packageChecker = { false }
        assertFalse(checker.checkMagiskHiddenPackages())
    }

    @Test
    fun `checkMagiskHiddenPackages huskydg magisk returns true`() {
        checker.packageChecker = { it == "io.github.huskydg.magisk" }
        assertTrue(checker.checkMagiskHiddenPackages())
    }

    @Test
    fun `checkMagiskHiddenPackages magisk stub package returns true`() {
        checker.packageChecker = { it == "com.topjohnwu.magisk.stub" }
        assertTrue(checker.checkMagiskHiddenPackages())
    }

    @Test
    fun `checkMagiskHiddenPackages kernelsu manager returns true`() {
        checker.packageChecker = { it == "me.weishu.kernelsu" }
        assertTrue(checker.checkMagiskHiddenPackages())
    }

    @Test
    fun `checkMagiskHiddenPackages xposed installer returns true`() {
        checker.packageChecker = { it == "de.robv.android.xposed.installer" }
        assertTrue(checker.checkMagiskHiddenPackages())
    }

    // ── checkRootBinaries (v1.1 #272) ─────────────────────────────────────

    @Test
    fun `checkRootBinaries no root binaries exist returns false`() {
        checker.fileExistsChecker = { false }
        assertFalse(checker.checkRootBinaries())
    }

    @Test
    fun `checkRootBinaries system bin busybox exists returns true`() {
        checker.fileExistsChecker = { it == "/system/bin/busybox" }
        assertTrue(checker.checkRootBinaries())
    }

    @Test
    fun `checkRootBinaries system xbin busybox exists returns true`() {
        checker.fileExistsChecker = { it == "/system/xbin/busybox" }
        assertTrue(checker.checkRootBinaries())
    }

    @Test
    fun `checkRootBinaries daemonsu exists returns true`() {
        checker.fileExistsChecker = { it == "/system/bin/daemonsu" }
        assertTrue(checker.checkRootBinaries())
    }

    @Test
    fun `checkRootBinaries magiskinit exists returns true`() {
        checker.fileExistsChecker = { it == "/system/bin/magiskinit" }
        assertTrue(checker.checkRootBinaries())
    }

    @Test
    fun `checkRootBinaries data adb magiskinit exists returns true`() {
        checker.fileExistsChecker = { it == "/data/adb/magisk/magiskinit" }
        assertTrue(checker.checkRootBinaries())
    }

    @Test
    fun `checkRootBinaries ksud exists returns true`() {
        checker.fileExistsChecker = { it == "/system/bin/ksud" }
        assertTrue(checker.checkRootBinaries())
    }

    // ── isRooted integration ──────────────────────────────────────────────

    @Test
    fun `isRooted all heuristics negative returns false`() {
        // All checkers already set to clean defaults in @Before
        assertFalse(checker.isRooted)
    }

    @Test
    fun `isRooted su binary heuristic fires returns true`() {
        checker.fileExistsChecker = { it == "/system/bin/su" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted root app heuristic fires returns true`() {
        checker.packageChecker = { it == "com.topjohnwu.magisk" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted rw system heuristic fires returns true`() {
        checker.mountsReader = { "/dev/block/sda1 /system ext4 rw,relatime 0 0" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted test keys heuristic fires returns true`() {
        checker.buildTagsReader = { "test-keys" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted debuggable prop heuristic fires returns true`() {
        checker.systemPropertyReader = { "1" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted writable system path fires returns true`() {
        checker.fileExistsChecker = { it == "/system" }
        checker.canWriteChecker = { it == "/system" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted zygisk path fires returns true`() {
        checker.fileExistsChecker = { it == "/data/adb/magisk" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted magisk hidden package fires returns true`() {
        checker.packageChecker = { it == "io.github.huskydg.magisk" }
        assertTrue(checker.isRooted)
    }

    @Test
    fun `isRooted root binary fires returns true`() {
        checker.fileExistsChecker = { it == "/system/xbin/busybox" }
        assertTrue(checker.isRooted)
    }
}
