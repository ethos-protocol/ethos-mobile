package com.ethosprotocol

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.services.VaultDeepLinkParser
import com.ethosprotocol.ui.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for #198: reminder notifications carry an inline "Check In" action, and that action
 * goes through the in-app biometric-gated check-in screen rather than firing the API directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CheckInNotificationActionTest {

    private lateinit var context: Context
    private lateinit var helper: NotificationHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        helper = NotificationHelper(context)
    }

    private fun postedActionIntentFor(vaultId: String?): android.content.Intent? {
        helper.show("Check-in Reminder", "Your vault expires soon.", vaultId)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.last()
        val action = notification.actions?.firstOrNull() ?: return null
        assertEquals(NotificationHelper.CHECK_IN_ACTION_TITLE, action.title.toString())
        return shadowOf(action.actionIntent).savedIntent
    }

    @Test
    fun `reminder notification carries a check in action targeting the app`() {
        val intent = postedActionIntentFor("vault-1")

        assertNotNull("Reminder must carry a Check In action", intent)
        assertEquals(MainActivity::class.java.name, intent!!.component?.className)
        assertEquals(Uri.parse(NotificationHelper.checkInDeepLink("vault-1")), intent.data)
    }

    @Test
    fun `check in action routes through the biometric-gated check-in deep link`() {
        val intent = postedActionIntentFor("vault-1")

        // The check-in deep link lands on VaultDeepLinkScreen's CHECK_IN branch, which prompts
        // BiometricHelper before calling the API — the action never bypasses that gate.
        val parsed = VaultDeepLinkParser.parse(intent!!.data!!)
        assertEquals(VaultDeepLinkAction.CHECK_IN, parsed?.action)
        assertEquals("vault-1", parsed?.vaultId)
    }

    @Test
    fun `notification without a vault id carries no check in action`() {
        helper.show("Ethos-Protocol", "Action required for your vault.", null)

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).allNotifications.last()

        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun `check in action request code never collides with a vault notification id`() {
        val ids = (0 until 100).map { helper.notificationIdFor("vault-$it") }
        val actionCodes = ids.map { it + NotificationHelper.CHECK_IN_ACTION_REQUEST_CODE_OFFSET }

        assertTrue(actionCodes.none { it in ids })
        assertFalse(actionCodes.contains(0))
    }
}
