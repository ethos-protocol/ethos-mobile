package com.ethosprotocol

import com.ethosprotocol.widget.VaultStatusWidget
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultStatusWidgetDeepLinkTest {

    @Test
    fun `deepLinkUri targets the displayed vault's view-details route`() {
        val uri = VaultStatusWidget.deepLinkUri("vault-abc-123")
        assertEquals("ethosprotocol://vault/vault-abc-123/view-details", uri)
    }

    @Test
    fun `deepLinkUri changes with the displayed vault id`() {
        val first = VaultStatusWidget.deepLinkUri("v1")
        val second = VaultStatusWidget.deepLinkUri("v2")
        assertEquals("ethosprotocol://vault/v1/view-details", first)
        assertEquals("ethosprotocol://vault/v2/view-details", second)
    }
}
