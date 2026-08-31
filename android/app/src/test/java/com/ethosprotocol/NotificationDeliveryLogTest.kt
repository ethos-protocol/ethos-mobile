package com.ethosprotocol

import android.content.Context
import android.content.SharedPreferences
import com.ethosprotocol.services.NotificationDeliveryLog
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers #235 (the delivery log itself) and #232 (its use as the poll/push
 * dedup registry): recording, bounding, clearing, and the WebSocket-recency
 * check that decides whether a push banner should be suppressed.
 */
class NotificationDeliveryLogTest {

    private fun makeLog(): NotificationDeliveryLog {
        val context: Context = mockk(relaxed = true)
        every { context.getSharedPreferences(any<String>(), any()) } returns fakeSharedPreferences()
        return NotificationDeliveryLog(context)
    }

    private fun fakeSharedPreferences(): SharedPreferences {
        val backing = mutableMapOf<String, String?>()
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk()

        every { prefs.getString(any(), any()) } answers { backing[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg())
            editor
        }
        every { editor.apply() } just Runs

        return prefs
    }

    @Test
    fun `record and recentEvents, most recent first`() {
        val log = makeLog()
        log.record(NotificationDeliveryLog.Kind.SCHEDULED, NotificationDeliveryLog.Source.LOCAL, "ttl_warning", "vault-a")
        log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.PUSH, "vault_expired", "vault-b")

        val events = log.recentEvents()
        assertEquals(2, events.size)
        assertEquals("vault_expired", events[0].eventType)
        assertEquals("ttl_warning", events[1].eventType)
    }

    @Test
    fun `recentEvents never exceeds max entries`() {
        val log = makeLog()
        repeat(250) { i ->
            log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.PUSH, "vault_expired", "vault-$i")
        }
        assertEquals(200, log.recentEvents().size)
        // Oldest entries are dropped, not newest.
        assertEquals("vault-50", log.recentEvents().last().vaultId)
    }

    @Test
    fun `clear removes all events`() {
        val log = makeLog()
        log.record(NotificationDeliveryLog.Kind.SCHEDULED, NotificationDeliveryLog.Source.LOCAL, "ttl_warning", "vault-a")
        log.clear()
        assertTrue(log.recentEvents().isEmpty())
    }

    @Test
    fun `wasRecentlyDeliveredViaWebSocket true within window`() {
        val log = makeLog()
        val now = System.currentTimeMillis()
        log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.WEBSOCKET, "vault_expired", "vault-a", now)

        assertTrue(log.wasRecentlyDeliveredViaWebSocket("vault-a", "vault_expired", windowMillis = 30_000, nowMillis = now + 10_000))
    }

    @Test
    fun `wasRecentlyDeliveredViaWebSocket false outside window`() {
        val log = makeLog()
        val now = System.currentTimeMillis()
        log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.WEBSOCKET, "vault_expired", "vault-a", now)

        assertFalse(log.wasRecentlyDeliveredViaWebSocket("vault-a", "vault_expired", windowMillis = 30_000, nowMillis = now + 31_000))
    }

    @Test
    fun `wasRecentlyDeliveredViaWebSocket false for different vault`() {
        val log = makeLog()
        val now = System.currentTimeMillis()
        log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.WEBSOCKET, "vault_expired", "vault-a", now)

        assertFalse(log.wasRecentlyDeliveredViaWebSocket("vault-b", "vault_expired", nowMillis = now))
    }

    @Test
    fun `wasRecentlyDeliveredViaWebSocket false for different event type`() {
        val log = makeLog()
        val now = System.currentTimeMillis()
        log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.WEBSOCKET, "vault_expired", "vault-a", now)

        assertFalse(log.wasRecentlyDeliveredViaWebSocket("vault-a", "vault_released", nowMillis = now))
    }

    @Test
    fun `wasRecentlyDeliveredViaWebSocket false for push source`() {
        // Only a WebSocket delivery should suppress a later push — a prior push
        // delivery is not grounds to suppress another push.
        val log = makeLog()
        val now = System.currentTimeMillis()
        log.record(NotificationDeliveryLog.Kind.DELIVERED, NotificationDeliveryLog.Source.PUSH, "vault_expired", "vault-a", now)

        assertFalse(log.wasRecentlyDeliveredViaWebSocket("vault-a", "vault_expired", nowMillis = now))
    }

    @Test
    fun `wasRecentlyDeliveredViaWebSocket false for suppressed kind`() {
        // A suppressed entry is not itself a "delivered" event to dedup against.
        val log = makeLog()
        val now = System.currentTimeMillis()
        log.record(NotificationDeliveryLog.Kind.SUPPRESSED, NotificationDeliveryLog.Source.WEBSOCKET, "vault_expired", "vault-a", now)

        assertFalse(log.wasRecentlyDeliveredViaWebSocket("vault-a", "vault_expired", nowMillis = now))
    }
}
