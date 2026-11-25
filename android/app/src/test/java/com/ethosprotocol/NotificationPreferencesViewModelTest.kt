package com.ethosprotocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.models.NotificationPreferences
import com.ethosprotocol.ui.NotificationPreferencesViewModel
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for NotificationPreferencesViewModel (#819).
 *
 * #648 fixed the silent reset when the stored JSON fails to decode: load() must
 * keep the last value that decoded successfully instead of falling back to the
 * defaults, and record a reset notice for the UI to surface. These tests cover
 * the Android view model's decode-failure path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NotificationPreferencesViewModelTest {

    private val apiClient: ApiClient = mockk(relaxed = true)
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `malformed stored value keeps previous preferences and surfaces a reset notice`() {
        // Save a known-good value that differs from the defaults, then corrupt only the
        // active JSON so the last-valid fallback is exercised on decode failure.
        val lastValid = NotificationPreferences(
            ttlWarningsEnabled = false,
            checkInRemindersEnabled = false
        )
        NotificationPreferences.save(context, lastValid)

        val prefs = context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE)
        prefs.edit().putString("prefs_json", "{ this is not valid json").apply()

        val vm = NotificationPreferencesViewModel(apiClient, context)

        // The previous (last valid) preferences are retained, not the defaults.
        assertEquals(lastValid, vm.state.value.preferences)
        assertFalse(
            "decode failure must not silently reset to defaults (ttlWarningsEnabled defaults to true)",
            vm.state.value.preferences.ttlWarningsEnabled
        )

        // The failure is surfaced for the UI to present.
        assertTrue(NotificationPreferences.consumeResetNotice(context))
    }
}
