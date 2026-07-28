package com.ethosprotocol

import android.content.Intent
import android.net.Uri
import com.ethosprotocol.ui.MainActivity
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for issue #63: beneficiary acceptance must require and forward an invitation token
 * to prove the requester is the originally invited party, preventing unauthorized acceptance
 * by anyone who learns a vault ID.
 */
class BeneficiaryAcceptanceTest {

    @Test
    fun `extractBeneficiaryAcceptParams returns null when token is missing`() {
        val activity = spyk(MainActivity())
        val intent = mockk<Intent>(relaxed = true)
        val uri = Uri.parse("https://ethos-protocol.app/accept?vault_id=valid-vault-123")

        every { intent.data } returns uri

        // Use reflection to call private method for testing
        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBeneficiaryAcceptParams",
            Intent::class.java
        )
        method.isAccessible = true
        val result = method.invoke(activity, intent) as? Pair<*, *>

        assertNull("Accept intent without token must return null", result)
    }

    @Test
    fun `extractBeneficiaryAcceptParams returns null when token is blank`() {
        val activity = spyk(MainActivity())
        val intent = mockk<Intent>(relaxed = true)
        val uri = Uri.parse("https://ethos-protocol.app/accept?vault_id=valid-vault-123&token=")

        every { intent.data } returns uri

        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBeneficiaryAcceptParams",
            Intent::class.java
        )
        method.isAccessible = true
        val result = method.invoke(activity, intent) as? Pair<*, *>

        assertNull("Accept intent with blank token must return null", result)
    }

    @Test
    fun `extractBeneficiaryAcceptParams returns null when vault_id is missing`() {
        val activity = spyk(MainActivity())
        val intent = mockk<Intent>(relaxed = true)
        val uri = Uri.parse("https://ethos-protocol.app/accept?token=some-token-abc")

        every { intent.data } returns uri

        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBeneficiaryAcceptParams",
            Intent::class.java
        )
        method.isAccessible = true
        val result = method.invoke(activity, intent) as? Pair<*, *>

        assertNull("Accept intent without vault_id must return null", result)
    }

    @Test
    fun `extractBeneficiaryAcceptParams returns vaultId and token when both are valid`() {
        val activity = spyk(MainActivity())
        val intent = mockk<Intent>(relaxed = true)
        val uri = Uri.parse("https://ethos-protocol.app/accept?vault_id=vault-456&token=invite-token-xyz")

        every { intent.data } returns uri
        mockkObject(com.ethosprotocol.services.VaultDeepLinkParser)
        every { com.ethosprotocol.services.VaultDeepLinkParser.isValidVaultId("vault-456") } returns true

        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBeneficiaryAcceptParams",
            Intent::class.java
        )
        method.isAccessible = true
        val result = method.invoke(activity, intent) as? Pair<*, *>

        assertNotNull("Accept intent with valid vault_id and token must return pair", result)
        assertEquals("vault-456", result?.first)
        assertEquals("invite-token-xyz", result?.second)

        unmockkObject(com.ethosprotocol.services.VaultDeepLinkParser)
    }

    @Test
    fun `extractBeneficiaryAcceptParams returns null for wrong host`() {
        val activity = spyk(MainActivity())
        val intent = mockk<Intent>(relaxed = true)
        val uri = Uri.parse("https://evil-site.com/accept?vault_id=vault-123&token=stolen-token")

        every { intent.data } returns uri

        val method = MainActivity::class.java.getDeclaredMethod(
            "extractBeneficiaryAcceptParams",
            Intent::class.java
        )
        method.isAccessible = true
        val result = method.invoke(activity, intent) as? Pair<*, *>

        assertNull("Accept intent from wrong host must return null", result)
    }
}
