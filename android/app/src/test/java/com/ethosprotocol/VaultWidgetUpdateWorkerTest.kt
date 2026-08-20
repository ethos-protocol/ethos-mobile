package com.ethosprotocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.widget.VaultStatusWidget
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [VaultWidgetUpdateWorker] using [TestListenableWorkerBuilder].
 *
 * Covers:
 * - Network unavailable / API error → returns success without touching the widget
 * - Empty vault list → returns success without touching the widget
 * - Single vault → saveVaultData and refreshAll called with correct values (#79 fix)
 * - Multiple vaults → firstOrNull() selects the first vault in the list (#79 selection logic)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VaultWidgetUpdateWorkerTest {

    private val apiClient: ApiClient = mockk()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // VaultWidgetUpdateWorker.doWork() calls VaultWidgetUpdateWorker.schedule(context),
        // which calls WorkManager.getInstance(context) — real WorkManager is never
        // auto-initialized here (@Config(manifest = Config.NONE) means no manifest merging,
        // so WorkManagerInitializer's ContentProvider never runs). Initialize a test instance.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        mockkObject(VaultStatusWidget)
        every { VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any()) } just Runs
        every { VaultStatusWidget.refreshAll(any()) } just Runs
    }

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkObject(VaultStatusWidget)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildWorker(): VaultWidgetUpdateWorker =
        TestListenableWorkerBuilder<VaultWidgetUpdateWorker>(context)
            .setWorkerFactory(VaultWidgetUpdateWorkerFactory(apiClient))
            .build()

    private fun vault(
        id: String,
        ttlRemaining: Long? = 172_800L, // 2 days
        lastCheckIn: String = "2026-04-01T00:00:00Z"
    ) = Vault(
        id = id,
        owner = "GABC",
        beneficiary = "GXYZ",
        balance = 10_000_000L,
        checkInInterval = 2_592_000L,
        lastCheckIn = lastCheckIn,
        ttlRemaining = ttlRemaining,
        status = VaultStatus.active
    )

    // ---------------------------------------------------------------------------
    // 1. NetworkUnavailable → success, widget not updated
    // ---------------------------------------------------------------------------

    @Test
    fun `network unavailable returns success and does not update widget`() = runBlocking {
        coEvery { apiClient.listVaults() } returns ApiResult.NetworkUnavailable

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { VaultStatusWidget.refreshAll(any()) }
    }

    // ---------------------------------------------------------------------------
    // 2. API error → success, widget not updated
    // ---------------------------------------------------------------------------

    @Test
    fun `api error returns success and does not update widget`() = runBlocking {
        coEvery { apiClient.listVaults() } returns ApiResult.Error("Server error", 500)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { VaultStatusWidget.refreshAll(any()) }
    }

    // ---------------------------------------------------------------------------
    // 3. Empty vault list → success, widget not updated
    // ---------------------------------------------------------------------------

    @Test
    fun `empty vault list returns success and does not update widget`() = runBlocking {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(emptyList())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { VaultStatusWidget.refreshAll(any()) }
    }

    // ---------------------------------------------------------------------------
    // 4. Single vault → saveVaultData called with correct formatted values
    // ---------------------------------------------------------------------------

    @Test
    fun `single vault calls saveVaultData with correct vault name`() = runBlocking {
        val v = vault("GABCDEFGHIJKLMNOP", ttlRemaining = 172_800L)
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v))

        buildWorker().doWork()

        // id.take(12) + "…"
        val expectedName = "GABCDEFGHIJKL"  // take(12) = "GABCDEFGHIJK" wait — 12 chars
        // "GABCDEFGHIJKLMNOP".take(12) = "GABCDEFGHIJK" (12 chars) + "…"
        verify {
            VaultStatusWidget.saveVaultData(
                context = any(),
                vaultId = any(),
                vaultName = "GABCDEFGHIJK…",
                ttlRemaining = "2d 0h",
                lastCheckIn = v.lastCheckIn
            )
        }
    }

    @Test
    fun `single vault calls saveVaultData with correctly formatted ttl days and hours`() = runBlocking {
        // 90061 seconds = 1 day 1 hour 1 minute 1 second → formatTtl → "1d 1h"
        val v = vault("v1", ttlRemaining = 90_061L)
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v))

        buildWorker().doWork()

        verify {
            VaultStatusWidget.saveVaultData(context = any(), vaultId = any(), vaultName = any(), ttlRemaining = "1d 1h", lastCheckIn = any())
        }
    }

    @Test
    fun `single vault with ttl under one day formatted as hours only`() = runBlocking {
        // 3600 seconds = 1 hour, no days
        val v = vault("v1", ttlRemaining = 3_600L)
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v))

        buildWorker().doWork()

        verify {
            VaultStatusWidget.saveVaultData(context = any(), vaultId = any(), vaultName = any(), ttlRemaining = "1h", lastCheckIn = any())
        }
    }

    @Test
    fun `single vault with null ttl formatted as Unknown`() = runBlocking {
        val v = vault("v1", ttlRemaining = null)
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v))

        buildWorker().doWork()

        verify {
            VaultStatusWidget.saveVaultData(context = any(), vaultId = any(), vaultName = any(), ttlRemaining = "Unknown", lastCheckIn = any())
        }
    }

    @Test
    fun `single vault calls refreshAll after saveVaultData`() = runBlocking {
        val v = vault("v1")
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(v))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verifyOrder {
            VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any())
            VaultStatusWidget.refreshAll(any())
        }
    }

    // ---------------------------------------------------------------------------
    // 5. Multiple vaults → firstOrNull() selects the first vault (#79 fix)
    //
    // The current implementation uses result.data.firstOrNull().  #79 documents that
    // this selection logic needs to be fixed (e.g. select the vault expiring soonest).
    // These tests assert the CURRENT firstOrNull() behaviour so that once #79 is
    // implemented the tests will fail visibly, making the change deliberate.
    // ---------------------------------------------------------------------------

    @Test
    fun `multiple vaults selects the first vault in the list (pre-79 behaviour)`() = runBlocking {
        val first = vault("first-vault", ttlRemaining = 100_000L)
        val second = vault("second-vault", ttlRemaining = 50_000L)
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(first, second))

        buildWorker().doWork()

        // Only the first vault's id should appear in the widget data (#79: update this
        // once the "most urgent vault" selection logic replaces firstOrNull()).
        verify {
            VaultStatusWidget.saveVaultData(
                context = any(),
                vaultId = any(),
                vaultName = "first-vault…",
                ttlRemaining = any(),
                lastCheckIn = first.lastCheckIn
            )
        }
        verify(exactly = 0) {
            VaultStatusWidget.saveVaultData(context = any(), vaultId = any(), vaultName = "second-vault…", ttlRemaining = any(), lastCheckIn = any())
        }
    }

    @Test
    fun `multiple vaults calls refreshAll exactly once`() = runBlocking {
        val vaults = listOf(vault("v1"), vault("v2"), vault("v3"))
        coEvery { apiClient.listVaults() } returns ApiResult.Success(vaults)

        buildWorker().doWork()

        verify(exactly = 1) { VaultStatusWidget.refreshAll(any()) }
    }
}

// ============================================================================
// VaultWidgetUpdateWorkerFactory
// ============================================================================

/**
 * [WorkerFactory] that injects a mock [ApiClient] into [VaultWidgetUpdateWorker],
 * bypassing the Hilt/AssistedInject wiring not available in plain JVM unit tests.
 */
private class VaultWidgetUpdateWorkerFactory(
    private val apiClient: ApiClient
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return if (workerClassName == VaultWidgetUpdateWorker::class.java.name) {
            VaultWidgetUpdateWorker(appContext, workerParameters, apiClient)
        } else {
            null
        }
    }
}
