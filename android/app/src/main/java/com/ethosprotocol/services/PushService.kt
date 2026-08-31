package com.ethosprotocol.services

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TTLFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var pushTokenRegistrar: PushTokenRegistrar
    @Inject lateinit var deliveryLog: NotificationDeliveryLog

    // #234: registration now retries with backoff and persists a "pending"
    // state on failure instead of firing the request and ignoring the outcome.
    override fun onNewToken(token: String) {
        pushTokenRegistrar.register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val vaultId = message.data["vault_id"]
        val type = message.data["type"] ?: "reminder"

        // #232: an already-foregrounded WebSocket connection may have applied
        // this exact vault_expired/vault_released event moments before this
        // push arrives — suppress the duplicate banner rather than showing it
        // twice, but still record every arrival for support triage (#235).
        if (vaultId != null && (type == "vault_expired" || type == "vault_released") &&
            deliveryLog.wasRecentlyDeliveredViaWebSocket(vaultId, type)
        ) {
            deliveryLog.record(NotificationDeliveryLog.Kind.SUPPRESSED,
                NotificationDeliveryLog.Source.PUSH, type, vaultId)
            return
        }

        val title = message.notification?.title ?: "Ethos-Protocol"
        val body = message.notification?.body ?: buildFallbackBody(type, vaultId)
        notificationHelper.show(title, body, vaultId)
        deliveryLog.record(NotificationDeliveryLog.Kind.DELIVERED,
            NotificationDeliveryLog.Source.PUSH, type, vaultId ?: "unknown")
    }
}

/**
 * The notification body used when the FCM payload is data-only (no server
 * `notification.body`). Identifies which vault needs attention — but never
 * anything beyond a truncated ID (#233): no full balance or beneficiary
 * address, which also matters for a locked-screen preview. `ttl_remaining`
 * is not included because it is not present in this payload today (unlike
 * the client-scheduled local reminders, which already have it) — see
 * PARITY.md for that as a tracked follow-up rather than a fabricated value.
 *
 * Extracted as a top-level function (rather than inline in onMessageReceived)
 * so it's testable without constructing a real FirebaseMessagingService/
 * RemoteMessage.
 */
fun buildFallbackBody(type: String, vaultId: String?): String {
    val truncatedVaultId = vaultId?.take(12)
    return when (type) {
        "expiry_warning" -> truncatedVaultId?.let { "Vault $it is expiring soon. Check in now." }
            ?: "Your vault is expiring soon. Check in now."
        "released" -> truncatedVaultId?.let { "Vault $it has been released to the beneficiary." }
            ?: "Your vault has been released to the beneficiary."
        else -> truncatedVaultId?.let { "Action required for vault $it." }
            ?: "Action required for your vault."
    }
}
