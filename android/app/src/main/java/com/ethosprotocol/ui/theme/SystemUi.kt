package com.ethosprotocol.ui.theme

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemUi {
    internal fun lightSystemBars(darkTheme: Boolean): Boolean = !darkTheme

    internal fun navigationBarColorFor(darkTheme: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Int =
        when {
            sdkInt >= Build.VERSION_CODES.Q -> Color.TRANSPARENT
            darkTheme -> Color.BLACK
            else -> Color.WHITE
        }

    fun apply(window: Window, darkTheme: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightSystemBars(darkTheme)
        controller.isAppearanceLightNavigationBars = lightSystemBars(darkTheme)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = navigationBarColorFor(darkTheme)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = true
        }
    }
}

@Composable
fun SystemUiAppearance(darkTheme: Boolean = isSystemInDarkTheme()) {
    val context = LocalContext.current
    DisposableEffect(darkTheme) {
        (context as? Activity)?.let { SystemUi.apply(it.window, darkTheme) }
        onDispose { }
    }
}
