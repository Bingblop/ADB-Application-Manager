package com.bloatware.bingblop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgBase = Color(0xFF0A0F1D)
val BgSurface = Color(0xFF131B2E)
val BgCard = Color(0xFF19233C)
val BorderGlass = Color(0x3338BDF8)
val AccentCyan = Color(0xFF00E5FF)
val AccentCyanGlow = Color(0x4000E5FF)
val CleanGreen = Color(0xFF10B981)
val DangerRed = Color(0xFFEF4444)
val SecondaryPurple = Color(0xFFA855F7)
val StatusRunning = Color(0xFF38BDF8)
val TextMain = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val TextDim = Color(0xFF64748B)

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = CleanGreen,
    tertiary = SecondaryPurple,
    background = BgBase,
    surface = BgSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = TextMain,
    onSurface = TextMain,
    surfaceVariant = BgCard,
    outline = BorderGlass
)

@Composable
fun ADBTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
