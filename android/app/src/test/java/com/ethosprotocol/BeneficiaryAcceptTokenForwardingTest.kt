package com.ethosprotocol

import android.net.Uri
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.BeneficiaryAcceptRequest
import com.ethosprotocol.services.VaultDeepLinkParser
import com.ethosprotocol.ui.AcceptanceViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for #196: the acceptance token carried by the deep link must survive the
 * whole way from the incoming [Uri] into the POST /vaults/{id}/accept request body. Dropping
 * it anywhere along that path makes the server reject every Android acceptance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BeneficiaryAcceptTokenForwardingTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `token parsed from the deep link uri reaches the accept api call`() = runTest {
        val uri = Uri.parse("https://ethos-protocol.app/vaults/vault-456/accept?token=invite-token-xyz")
        val link = VaultDeepLinkParser.parseBeneficiaryAccept(uri)
        assertNotNull("Acceptance link must parse", link)

        coEvery { apiClient.acceptBeneficiary(any(), any()) } returns ApiResult.Success(Unit)

        AcceptanceViewModel(apiClient).accept(link!!.vaultId, link.token)

        coVerify { apiClient.acceptBeneficiary("vault-456", "invite-token-xyz") }
    }

    @Test
    fun `accept request body carries the token`() {
        val body = Json.encodeToString(
            BeneficiaryAcceptRequest.serializer(),
            BeneficiaryAcceptRequest(vaultId = "vault-456", token = "invite-token-xyz")
        )

        assertTrue("Request body must carry the token", body.contains("\"token\":\"invite-token-xyz\""))
        assertTrue(body.contains("\"vault_id\":\"vault-456\""))
    }

    @Test
    fun `acceptance link without a token does not parse`() {
        val uri = Uri.parse("https://ethos-protocol.app/vaults/vault-456/accept")

        assertNull(VaultDeepLinkParser.parseBeneficiaryAccept(uri))
    }
}
