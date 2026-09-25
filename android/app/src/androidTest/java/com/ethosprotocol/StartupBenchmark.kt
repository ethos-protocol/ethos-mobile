package com.ethosprotocol

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark tests for app startup performance (#322).
 *
 * Measures cold-start, warm-start, and hot-start latency with/without baseline profiles.
 * Results are used to detect regressions before they reach production.
 *
 * Run via:
 *   ./gradlew benchmark -Pandroid.testInstrumentationRunnerArguments.class=com.ethosprotocol.StartupBenchmark
 *
 * Or in CI via a separate job with the macrobenchmark variant:
 *   ./gradlew benchmarkDemoDebug -k startup
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    /**
     * Cold start benchmark: app killed, app data cleared, no baseline profile.
     * This is the worst-case startup scenario and directly impacts first-impression UX.
     * Target: < 1500ms total (goal: < 1000ms)
     */
    @Test
    fun coldStart() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
        measureBlock = {
            startActivityAndWait()
        }
    )

    /**
     * Warm start benchmark: app process alive, activity recreated.
     * Common scenario when returning from background or opening from notifications.
     * Target: < 500ms (goal: < 300ms)
     */
    @Test
    fun warmStart() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        setupBlock = {
            startActivityAndWait()
        },
        measureBlock = {
            // Restart the activity by pressing home then relaunching
            pressHome()
            startActivityAndWait()
        }
    )

    /**
     * Hot start benchmark: app in foreground, activity resumed.
     * Fastest startup path, primarily tests screen transition overhead.
     * Target: < 100ms
     */
    @Test
    fun hotStart() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.HOT,
        setupBlock = {
            startActivityAndWait()
        },
        measureBlock = {
            device.pressBack()
            startActivityAndWait()
        }
    )

    /**
     * Cold start with baseline profile enabled.
     * Baseline profiles (when bundled in the APK) significantly reduce JIT compilation overhead.
     * This simulates the user experience after Google Play delivers the optimized profile.
     * Target: < 1200ms (improvement: 200-300ms from profile optimization)
     */
    @Test
    fun coldStartWithBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.BaselineProfileGuided(),
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
        measureBlock = {
            startActivityAndWait()
        }
    )

    private fun startActivityAndWait() {
        val intent = Intent().apply {
            setPackage("com.ethosprotocol")
            action = "android.intent.action.MAIN"
            addCategory("android.intent.category.LAUNCHER")
        }
        device.startActivityAndWait(intent)
    }

    private fun pressHome() {
        device.pressHome()
        Thread.sleep(1_000) // Wait for home screen to stabilize
    }
}
