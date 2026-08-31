package com.ethosprotocol.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

// #a11y-contrast: Material3 dynamic color derives its palette from the user's wallpaper and does
// not guarantee a WCAG AA contrast ratio (4.5:1 for normal text) for every generated palette,
// particularly for status-communicating colors like the expiring-soon warning (error) and the
// offline banner (tertiaryContainer/onTertiaryContainer). These fixed, audited overrides replace
// only those two roles when high-contrast mode is enabled, so a low-contrast wallpaper-derived
// palette can't make a warning unreadable. See docs/manual-qa-checklist.md's contrast-check step
// for the manual audit process across sample dynamic-color palettes.
private val HighContrastLightError = Color(0xFFB00020) // ~7.3:1 against white — WCAG AAA
private val HighContrastLightOnError = Color(0xFFFFFFFF)
private val HighContrastLightTertiaryContainer = Color(0xFF5C3D00) // ~8.5:1 against white text
private val HighContrastLightOnTertiaryContainer = Color(0xFFFFFFFF)

private val HighContrastDarkError = Color(0xFFFFB4A9) // ~8.1:1 against near-black surfaces
private val HighContrastDarkOnError = Color(0xFF000000)
private val HighContrastDarkTertiaryContainer = Color(0xFFFFD8A8)
private val HighContrastDarkOnTertiaryContainer = Color(0xFF000000)

private fun ColorScheme.withHighContrastStatusColors(darkTheme: Boolean): ColorScheme = if (darkTheme) {
    copy(
        error = HighContrastDarkError,
        onError = HighContrastDarkOnError,
        tertiaryContainer = HighContrastDarkTertiaryContainer,
        onTertiaryContainer = HighContrastDarkOnTertiaryContainer,
    )
} else {
    copy(
        error = HighContrastLightError,
        onError = HighContrastLightOnError,
        tertiaryContainer = HighContrastLightTertiaryContainer,
        onTertiaryContainer = HighContrastLightOnTertiaryContainer,
    )
}

@Composable
fun EthosProtocolTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    // Manual override for users whose dynamic-color palette doesn't render status colors
    // (expiring-soon warning, offline banner) with adequate contrast. Surfaced as a Settings
    // toggle; defaults off since most dynamic palettes are compliant.
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    var colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    if (highContrast) {
        colorScheme = colorScheme.withHighContrastStatusColors(darkTheme)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
