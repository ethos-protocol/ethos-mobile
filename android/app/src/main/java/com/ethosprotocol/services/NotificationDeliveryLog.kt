package com.ethosprotocol.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, on-device record of notification lifecycle events (scheduled /
 * delivered / suppressed), so a support ticket like "I never got my TTL
 * warning" is answerable from a debug screen instead of requiring backend
 * log correlation (#235).
 *
 * Doubles as the dedup registry for #232: a `vault_expired`/`vault_released`
 * event applied via the WebSocket is recorded here, and `PushService`'s
 * `onMessageReceived` checks it before showing a banner for the same event.
 *
 * **No sensitive vault data is ever recorded** — only a vault ID, an event
 * type string (e.g. "vault_expired", "ttl_warning"), a delivery channel, and
 * a timestamp. Never balance, beneficiary, or any other vault field.
 */
@Serializable
data class NotificationDeliveryEvent(
    val kind: NotificationDeliveryLog.Kind,
    val source: NotificationDeliveryLog.Source,
    val eventType: String,
    val vaultId: String,
    val timestampMillis: Long
)

@Singleton
class NotificationDeliveryLog @Inject constructor(@ApplicationContext context: Context) {

    enum class Kind { SCHEDULED, DELIVERED, SUPPRESSED }
    enum class Source { LOCAL, PUSH, WEBSOCKET }

    companion object {
        private const val PREFS_NAME = "notification_delivery_log"
        private const val KEY_EVENTS = "events"
        // Bounded so this never grows unbounded across a long-lived install —
        // only recent history is useful for support triage.
        private const val MAX_ENTRIES = 200
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun record(kind: Kind, source: Source, eventType: String, vaultId: String, atMillis: Long = System.currentTimeMillis()) {
        val events = loadLocked().toMutableList()
        events.add(NotificationDeliveryEvent(kind, source, eventType, vaultId, atMillis))
        val trimmed = if (events.size > MAX_ENTRIES) events.takeLast(MAX_ENTRIES) else events
        saveLocked(trimmed)
    }

    /** All logged events, most recent first, for the debug screen. */
    @Synchronized
    fun recentEvents(): List<NotificationDeliveryEvent> = loadLocked().reversed()

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }

    /**
     * #232: was [eventType] for [vaultId] already delivered via the WebSocket
     * within [windowMillis] of [nowMillis]? Used to suppress a duplicate push
     * banner for an event the UI already reflects.
     */
    @Synchronized
    fun wasRecentlyDeliveredViaWebSocket(
        vaultId: String,
        eventType: String,
        windowMillis: Long = 30_000,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = loadLocked().any { event ->
        event.source == Source.WEBSOCKET &&
            event.kind == Kind.DELIVERED &&
            event.vaultId == vaultId &&
            event.eventType == eventType &&
            (nowMillis - event.timestampMillis) in 0..windowMillis
    }

    private val listSerializer = ListSerializer(NotificationDeliveryEvent.serializer())

    private fun loadLocked(): List<NotificationDeliveryEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching { Json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private fun saveLocked(events: List<NotificationDeliveryEvent>) {
        prefs.edit().putString(KEY_EVENTS, Json.encodeToString(listSerializer, events)).apply()
    }
}
