package org.amnezia.awg.desktop.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

// WireGuard brand colors
private val WireGuardBlue = Color(0xFF8839BF)
private val WireGuardBlueDark = Color(0xFF7B36AA)
private val WireGuardGreen = Color(0xFF1F8E54)
private val WireGuardRed = Color(0xFFD64545)

private val LightColors = lightColorScheme(
    primary = WireGuardBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEDDFF),
    onPrimaryContainer = Color(0xFF25005A),
    secondary = WireGuardGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC6F2D6),
    onSecondaryContainer = Color(0xFF002110),
    error = WireGuardRed,
    onError = Color.White,
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF79747E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD3BBFF),
    onPrimary = Color(0xFF3E0075),
    primaryContainer = Color(0xFF551890),
    onPrimaryContainer = Color(0xFFEEDDFF),
    secondary = Color(0xFFABD6BB),
    onSecondary = Color(0xFF00391F),
    secondaryContainer = Color(0xFF005231),
    onSecondaryContainer = Color(0xFFC6F2D6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE8E0EA),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE8E0EA),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
    outline = Color(0xFF938F99),
)

/**
 * User selectable colors. [accent] drives buttons, switches and headings;
 * [status] is used for the "Connected" status and selection highlights.
 */
@Immutable
data class ThemeColors(val accent: Color = DEFAULT_ACCENT, val status: Color = DEFAULT_STATUS) {
    val isDefault: Boolean get() = this == ThemeColors()

    companion object {
        val DEFAULT_ACCENT = WireGuardBlue
        val DEFAULT_STATUS = WireGuardGreen

        /** Swatches offered by the color picker. */
        val PRESETS = listOf(
            WireGuardBlue, Color(0xFF3F51B5), Color(0xFF1E88E5), Color(0xFF00838F),
            WireGuardGreen, Color(0xFF558B2F), Color(0xFFF9A825), Color(0xFFEF6C00),
            WireGuardRed, Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF546E7A),
        )
    }
}

/** `#RRGGBB` representation used in the settings file and the hex input field. */
fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** Parse `#RRGGBB` or `RRGGBB`; returns null for anything else. */
fun parseHexColor(text: String): Color? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return Color(0xFF000000 or hex.toLong(16))
}

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.4f) Color(0xFF1D1B20) else Color.White

/** Derive a full scheme from the custom colors, keeping the neutral surfaces. */
fun colorScheme(colors: ThemeColors, darkTheme: Boolean): ColorScheme {
    if (colors.isDefault) return if (darkTheme) DarkColors else LightColors
    val accent = colors.accent
    val status = colors.status
    return if (darkTheme) {
        // Dark themes need light, desaturated tones to keep enough contrast.
        val primary = lerp(accent, Color.White, 0.45f)
        val secondary = lerp(status, Color.White, 0.45f)
        DarkColors.copy(
            primary = primary,
            onPrimary = lerp(accent, Color.Black, 0.7f),
            primaryContainer = lerp(accent, Color.Black, 0.45f),
            onPrimaryContainer = lerp(accent, Color.White, 0.85f),
            secondary = secondary,
            onSecondary = lerp(status, Color.Black, 0.7f),
            secondaryContainer = lerp(status, Color.Black, 0.5f),
            onSecondaryContainer = lerp(status, Color.White, 0.85f),
            surfaceTint = primary,
        )
    } else {
        LightColors.copy(
            primary = accent,
            onPrimary = contentColorFor(accent),
            primaryContainer = lerp(accent, Color.White, 0.82f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.65f),
            secondary = status,
            onSecondary = contentColorFor(status),
            secondaryContainer = lerp(status, Color.White, 0.8f),
            onSecondaryContainer = lerp(status, Color.Black, 0.7f),
            surfaceTint = accent,
        )
    }
}

@Composable
fun AmneziaContent(
    darkTheme: Boolean = false,
    colors: ThemeColors = ThemeColors(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(colors, darkTheme) { colorScheme(colors, darkTheme) }
    MaterialTheme(colorScheme = scheme) {
        val scrollbarStyle = ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 200,
            unhoverColor = scheme.onSurface.copy(alpha = 0.25f),
            hoverColor = scheme.onSurface.copy(alpha = 0.55f),
        )
        CompositionLocalProvider(LocalScrollbarStyle provides scrollbarStyle, content = content)
    }
}
