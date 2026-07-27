package com.ethosprotocol

import android.content.Context
import android.content.SharedPreferences
import com.ethosprotocol.services.NotificationHelper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationHelperTest {

    private lateinit var helper: NotificationHelper

    @Before
    fun setup() {
        val context: Context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns fakeSharedPreferences()
        helper = NotificationHelper(context)
    }

    @Test
    fun `same vault id always maps to the same notification id`() {
        val first = helper.notificationIdFor("vault-1")
        val second = helper.notificationIdFor("vault-1")

        assertEquals(first, second)
    }

    @Test
    fun `two different vault ids never collide across a large sample`() {
        val ids = (0 until 5_000).map { helper.notificationIdFor("vault-$it") }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `vault notification ids never collide with the queued check-in id`() {
        val ids = (0 until 100).map { helper.notificationIdFor("vault-$it") }

        assertFalse(ids.contains(NotificationHelper.QUEUED_NOTIFICATION_ID))
    }

    @Test
    fun `null vault id does not collide with a real vault id`() {
        val nullId = helper.notificationIdFor(null)
        val realId = helper.notificationIdFor("vault-1")

        assertNotEquals(nullId, realId)
    }

    private fun fakeSharedPreferences(): SharedPreferences {
        val backing = mutableMapOf<String, Int>()
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk()

        every { prefs.getInt(any(), any()) } answers { backing[firstArg()] ?: secondArg() }
        every { prefs.all } answers { backing.toMutableMap() }
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            backing[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just Runs

        return prefs
    }
}
