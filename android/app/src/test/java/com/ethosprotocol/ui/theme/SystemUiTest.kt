package com.ethosprotocol.ui.theme

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 33])
class SystemUiTest {

    @Test
    fun `system bar icons are dark only in the light theme`() {
        assertTrue(SystemUi.lightSystemBars(darkTheme = false))
        assertFalse(SystemUi.lightSystemBars(darkTheme = true))
    }

    @Test
    fun `navigation bar stays opaque before API 29 to keep icons readable`() {
        assertEquals(Color.WHITE, SystemUi.navigationBarColorFor(darkTheme = false, sdkInt = 28))
        assertEquals(Color.BLACK, SystemUi.navigationBarColorFor(darkTheme = true, sdkInt = 28))
        assertEquals(
            Color.TRANSPARENT,
            SystemUi.navigationBarColorFor(darkTheme = false, sdkInt = Build.VERSION_CODES.Q)
        )
    }

    @Test
    @Config(sdk = [28])
    fun `apply draws edge to edge with a transparent status bar`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        SystemUi.apply(activity.window, darkTheme = false)

        val edgeToEdgeFlags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        assertEquals(
            edgeToEdgeFlags,
            activity.window.decorView.systemUiVisibility and edgeToEdgeFlags
        )
        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(
            SystemUi.navigationBarColorFor(darkTheme = false),
            activity.window.navigationBarColor
        )
    }

    @Test
    @Config(sdk = [28])
    fun `apply sets light status bar flag on legacy Android in the light theme`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        SystemUi.apply(activity.window, darkTheme = false)

        assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)
    }

    @Test
    @Config(sdk = [28])
    fun `apply clears light system bar flags in the dark theme`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        SystemUi.apply(activity.window, darkTheme = true)

        val flags = activity.window.decorView.systemUiVisibility
        assertEquals(0, flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        assertEquals(0, flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    @Test
    @Config(sdk = [28])
    fun `apply keeps the compat inset controller in sync with the theme`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        SystemUi.apply(activity.window, darkTheme = true)

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
        assertTrue(WindowCompat.getInsetsController(activity.window, activity.window.decorView) != null)
    }
}
