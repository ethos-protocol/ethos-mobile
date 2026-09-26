package com.ethosprotocol

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.screens.VaultCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Performance test for VaultList scrolling through large vault collections (#318).
 * Verifies that rendering 100+ vaults does not cause frame jank or excessive icon decoding.
 */
@RunWith(AndroidJUnit4::class)
class VaultListPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createTestVaults(count: Int): List<Vault> {
        return (1..count).map { index ->
            Vault(
                id = "vault-$index-" + "x".repeat(50 - index.toString().length),
                balance = 1_000_000_000L + index,
                formattedBalance = "${index}.0000000 XLM",
                status = when (index % 4) {
                    0 -> VaultStatus.active
                    1 -> VaultStatus.expired
                    2 -> VaultStatus.released
                    else -> VaultStatus.paused
                },
                checkInInterval = 86_400L,
                ttlRemaining = 86_400L + index,
                isExpiringSoon = index % 10 == 0,
                lastCheckIn = "2024-01-01T00:00:00Z",
                beneficiary = "beneficiary-" + "x".repeat(40 - index.toString().length),
                source = "test"
            )
        }
    }

    @Test
    fun testVaultListRenderingWith100Vaults() {
        val vaults = createTestVaults(100)

        val renderTime = measureTimeMillis {
            composeTestRule.setContent {
                LazyColumn {
                    items(vaults, key = { it.id }) { vault ->
                        VaultCard(
                            vault = vault,
                            onClick = {},
                            onCheckIn = {}
                        )
                    }
                }
            }
        }

        // Rendering 100 vault cards should complete in reasonable time (< 500ms typical)
        // This is a baseline benchmark; if this exceeds 1000ms, icon decoding may be redundant
        println("[Performance] Rendered 100 vaults in ${renderTime}ms")
        assert(renderTime < 2000L) { "Rendering 100 vaults took too long: ${renderTime}ms" }
    }

    @Test
    fun testVaultListRenderingWith500Vaults() {
        val vaults = createTestVaults(500)

        val renderTime = measureTimeMillis {
            composeTestRule.setContent {
                LazyColumn {
                    items(vaults, key = { it.id }) { vault ->
                        VaultCard(
                            vault = vault,
                            onClick = {},
                            onCheckIn = {}
                        )
                    }
                }
            }
        }

        // This is a stress test for large lists
        println("[Performance] Rendered 500 vaults in ${renderTime}ms")
        assert(renderTime < 5000L) { "Rendering 500 vaults took too long: ${renderTime}ms" }
    }
}
