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
            systemPropertyReader = { "0" }
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
}
