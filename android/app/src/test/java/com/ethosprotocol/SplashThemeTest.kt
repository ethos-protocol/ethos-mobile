package com.ethosprotocol

import android.graphics.Color
import androidx.core.splashscreen.R as CoreR
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SplashThemeTest {

    @After
    fun resetQualifiers() {
        RuntimeEnvironment.setQualifiers("")
    }

    @Test
    fun `splash theme declares a branded icon and returns to the app theme`() {
        val theme = RuntimeEnvironment.getApplication().resources.newTheme()
        theme.applyStyle(R.style.Theme_EthosProtocol_Splash, true)

        val typed = theme.obtainStyledAttributes(
            intArrayOf(
                CoreR.attr.windowSplashScreenBackground,
                CoreR.attr.windowSplashScreenAnimatedIcon,
                CoreR.attr.windowSplashScreenAnimationDuration,
                CoreR.attr.postSplashScreenTheme
            )
        )

        assertTrue(typed.hasValue(0))
        assertEquals(R.drawable.ic_splash, typed.getResourceId(1, 0))
        assertTrue(typed.getInt(2, 0) in 1..1_000)
        assertEquals(R.style.Theme_EthosProtocol, typed.getResourceId(3, 0))
        typed.recycle()
    }

    @Test
    fun `splash background adapts to night mode`() {
        val resources = RuntimeEnvironment.getApplication().resources
        val theme = resources.newTheme()
        theme.applyStyle(R.style.Theme_EthosProtocol_Splash, true)

        RuntimeEnvironment.setQualifiers("notnight")
        val lightBackground = resources.getColor(R.color.splash_background, theme)
        RuntimeEnvironment.setQualifiers("night")
        val darkBackground = resources.getColor(R.color.splash_background, theme)

        assertTrue(lightBackground != darkBackground)
        assertTrue(Color.red(darkBackground) < 0x40)
        assertTrue(Color.red(lightBackground) > 0xC0)
    }
}
