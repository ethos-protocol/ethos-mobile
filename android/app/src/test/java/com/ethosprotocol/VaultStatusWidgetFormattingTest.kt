package com.ethosprotocol

import com.ethosprotocol.widget.VaultStatusWidget
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultStatusWidgetFormattingTest {

    private val now = Instant.parse("2026-07-27T12:00:00Z")

    @Test
    fun `formatLastCheckIn under a minute ago is Just now`() {
        val result = VaultStatusWidget.formatLastCheckIn("2026-07-27T11:59:45Z", now)
        assertEquals("Just now", result)
    }

    @Test
    fun `formatLastCheckIn minutes ago`() {
        val result = VaultStatusWidget.formatLastCheckIn("2026-07-27T11:45:00Z", now)
        assertEquals("15 minutes ago", result)
    }

    @Test
    fun `formatLastCheckIn singular hour ago`() {
        val result = VaultStatusWidget.formatLastCheckIn("2026-07-27T11:00:00Z", now)
        assertEquals("1 hour ago", result)
    }

    @Test
    fun `formatLastCheckIn plural hours ago`() {
        val result = VaultStatusWidget.formatLastCheckIn("2026-07-27T10:00:00Z", now)
        assertEquals("2 hours ago", result)
    }

    @Test
    fun `formatLastCheckIn days ago`() {
        val result = VaultStatusWidget.formatLastCheckIn("2026-07-24T12:00:00Z", now)
        assertEquals("3 days ago", result)
    }

    @Test
    fun `formatLastCheckIn falls back to raw string when unparseable`() {
        val result = VaultStatusWidget.formatLastCheckIn("not-a-timestamp", now)
        assertEquals("not-a-timestamp", result)
    }
}
