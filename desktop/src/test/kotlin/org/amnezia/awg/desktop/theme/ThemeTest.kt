/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeTest {
    @Test
    fun hexRoundTrip() {
        assertEquals("#8839BF", ThemeColors.DEFAULT_ACCENT.toHex())
        assertEquals(Color(0xFF1E88E5), parseHexColor("#1e88e5"))
        assertEquals(Color(0xFF1E88E5), parseHexColor("1E88E5"))
        assertNull(parseHexColor("#1E88E"))
        assertNull(parseHexColor("#GGGGGG"))
    }

    @Test
    fun defaultsKeepTheOriginalSchemes() {
        assertEquals(ThemeColors.DEFAULT_ACCENT, colorScheme(ThemeColors(), darkTheme = false).primary)
        assertEquals(Color(0xFFD3BBFF), colorScheme(ThemeColors(), darkTheme = true).primary)
    }

    @Test
    fun customColorsDriveTheScheme() {
        val colors = ThemeColors(accent = Color(0xFFF9A825), status = Color(0xFF1E88E5))
        val light = colorScheme(colors, darkTheme = false)
        assertEquals(colors.accent, light.primary)
        assertEquals(colors.status, light.secondary)
        // A light accent gets dark content for contrast.
        assertNotEquals(Color.White, light.onPrimary)
        assertNotEquals(colors.accent, colorScheme(colors, darkTheme = true).primary)
    }
}
