/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.App
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.theme.AmneziaContent
import org.amnezia.awg.desktop.theme.ThemeColors
import org.amnezia.awg.desktop.util.AppPaths
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.swing.SwingUtilities

/**
 * Renders every screen offscreen and writes PNGs to `build/screens`. Runs
 * against the throwaway config directory the Gradle test task points
 * `XDG_CONFIG_HOME` at; the sample tunnel is never brought up.
 */
class ScreensRenderTest {
    @Test
    fun rendersAllScreens() {
        check(AppPaths.configDir.absolutePath.contains("build")) { "Refusing to run outside the test config dir" }
        AppPaths.configDir.listFiles()?.forEach { it.delete() }
        File(AppPaths.configDir, "sample.conf").writeText(SAMPLE)
        File(AppPaths.configDir, "office2.conf").writeText(SAMPLE)

        val app = onEdt { AppState().also { it.start() } }
        waitUntil { onEdt { app.tunnelManager.loaded } }
        val out = File("build/screens").apply { mkdirs() }

        fun shot(
            name: String,
            width: Int = 960,
            height: Int = 720,
            dark: Boolean = false,
            clickAt: Offset? = null,
        ) {
            ImageComposeScene(width, height, Density(1f)) {
                AmneziaContent(darkTheme = dark, colors = app.themeColors) { App(app) }
            }.use { scene ->
                // A few frames so that effects (config loading) can settle.
                repeat(5) { i ->
                    scene.render(i * 100_000_000L)
                    Thread.sleep(150)
                    onEdt { }
                    if (i == 1 && clickAt != null) {
                        val t = i * 100_000_000L
                        scene.sendPointerEvent(PointerEventType.Move, clickAt, timeMillis = t / 1_000_000)
                        scene.render(t + 10_000_000)
                        scene.sendPointerEvent(PointerEventType.Press, clickAt, timeMillis = t / 1_000_000 + 20)
                        scene.render(t + 30_000_000)
                        scene.sendPointerEvent(PointerEventType.Release, clickAt, timeMillis = t / 1_000_000 + 60)
                        scene.render(t + 70_000_000)
                        onEdt { }
                    }
                }
                val image = scene.render(1_000_000_000L)
                val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "$name.png").writeBytes(png)
            }
        }

        shot("list")
        // The add button of the list pane: the camera entry is disabled when
        // no camera is present (as on CI and this test's sandbox).
        shot("add-menu", clickAt = Offset(316f, 676f))
        val tunnel = onEdt { app.tunnelManager.tunnels.first { it.name == "sample" } }
        onEdt { app.openTunnel(tunnel) }
        shot("detail")
        shot("detail-narrow", width = 480)
        onEdt { app.navigate(Screen.TunnelEditor(tunnel)) }
        shot("editor")
        onEdt { app.popBackStack(); app.navigate(Screen.Settings) }
        shot("settings", dark = true)
        onEdt { app.updateThemeColors(ThemeColors(accent = Color(0xFF1E88E5), status = Color(0xFFEF6C00))) }
        shot("settings-custom-colors")
        onEdt { app.updateThemeColors(ThemeColors()) }

        onEdt { app.close() }
        assertTrue(File(out, "settings.png").length() > 0)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(50)
        }
    }

    private companion object {
        val SAMPLE = """
            [Interface]
            PrivateKey = ${org.amnezia.awg.crypto.KeyPair().privateKey.toBase64()}
            Address = 10.8.0.2/32
            DNS = 1.1.1.1
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 68
            H1 = 1234
            H2 = 5678
            H3 = 9012
            H4 = 3456

            [Peer]
            PublicKey = ${org.amnezia.awg.crypto.KeyPair().publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 203.0.113.10:51820
            PersistentKeepalive = 25
        """.trimIndent()

        init {
            // Make sure the sample parses before rendering anything.
            Config.parse(SAMPLE.byteInputStream())
        }
    }
}
