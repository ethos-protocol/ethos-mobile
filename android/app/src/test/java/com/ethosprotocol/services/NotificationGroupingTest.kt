package com.ethosprotocol.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethosprotocol.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NotificationGroupingTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var helper: NotificationHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        helper = NotificationHelper(context, DoNotDisturbHelper(context))
    }

    @Test
    fun `check-in reminders cluster into one inbox summary`() = runBlocking {
        helper.show("Soon", "Vault A expires soon", "vault-a", 3_600L, isCritical = true)
        helper.show("Later", "Vault B expires later", "vault-b", 7_200L, isCritical = true)
        awaitPosted(3)

        val posted = shadowOf(notificationManager).allNotifications
        val children = posted.filter {
            it.group == NotificationHelper.CHECK_IN_GROUP_KEY && !it.isGroupSummary
        }
        val summary = posted.first {
            it.isGroupSummary && it.group == NotificationHelper.CHECK_IN_GROUP_KEY
        }

        assertEquals(2, children.size)
        assertEquals(
            context.getString(R.string.notification_group_summary_title, 2),
            summary.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        assertEquals(
            listOf("Vault A expires soon", "Vault B expires later"),
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
        )
    }

    @Test
    fun `queued and expired notifications use distinct groups and summaries`() {
        helper.showQueuedActions(2)
        helper.showVaultExpiredNotification("vault-c", PendingActionType.CHECK_IN)

        val posted = shadowOf(notificationManager).allNotifications
        assertTrue(posted.any { it.group == NotificationHelper.QUEUED_GROUP_KEY && !it.isGroupSummary })
        assertTrue(posted.any { it.group == NotificationHelper.EXPIRED_GROUP_KEY && !it.isGroupSummary })
        assertTrue(posted.any { it.isGroupSummary && it.group == NotificationHelper.QUEUED_GROUP_KEY })
        assertTrue(posted.any { it.isGroupSummary && it.group == NotificationHelper.EXPIRED_GROUP_KEY })
        assertEquals(
            NotificationHelper.QUEUED_CHANNEL_ID,
            posted.first { it.group == NotificationHelper.QUEUED_GROUP_KEY && !it.isGroupSummary }.channelId
        )
    }

    @Test
    fun `summary ids are stable and never collide with vault notification ids`() {
        val checkInSummary = NotificationHelper.summaryIdFor(NotificationHelper.CHECK_IN_GROUP_KEY)
        val queuedSummary = NotificationHelper.summaryIdFor(NotificationHelper.QUEUED_GROUP_KEY)

        assertEquals(checkInSummary, NotificationHelper.summaryIdFor(NotificationHelper.CHECK_IN_GROUP_KEY))
        assertTrue(checkInSummary >= 50_000)
        assertTrue(checkInSummary != queuedSummary)
        assertTrue(checkInSummary !in (0 until 100).map { helper.notificationIdFor("vault-$it") })
        assertFalse(checkInSummary == NotificationHelper.QUEUED_NOTIFICATION_ID)
    }

    @Test
    fun `sort key places the soonest expiry first`() {
        assertTrue(
            NotificationHelper.sortKeyFor(60L, "vault-a") <
                NotificationHelper.sortKeyFor(3_600L, "vault-b")
        )
        assertTrue(
            NotificationHelper.sortKeyFor(0L, "vault-c") <
                NotificationHelper.sortKeyFor(60L, "vault-a")
        )
        assertTrue(NotificationHelper.sortKeyFor(null) > NotificationHelper.sortKeyFor(3_600L))
    }

    @Test
    fun `all notification channels belong to one settings group`() {
        assertNotNull(
            notificationManager.getNotificationChannelGroup(NotificationHelper.NOTIFICATION_CHANNEL_GROUP_ID)
        )
        assertTrue(
            notificationManager.notificationChannels.all {
                it.group == NotificationHelper.NOTIFICATION_CHANNEL_GROUP_ID
            }
        )
    }

    @Test
    fun `cancelling queued actions also removes their group summary`() {
        helper.showQueuedActions(1)
        helper.cancelQueuedActions()

        assertTrue(shadowOf(notificationManager).allNotifications.none {
            it.group == NotificationHelper.QUEUED_GROUP_KEY
        })
    }

    private suspend fun awaitPosted(expectedCount: Int) {
        withTimeout(2_000L) {
            while (shadowOf(notificationManager).allNotifications.size < expectedCount) {
                delay(10)
            }
        }
    }
}
