/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.App
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.theme.AmneziaContent
import org.amnezia.awg.desktop.theme.ThemeColors
import org.amnezia.awg.desktop.util.AppPaths
import org.amnezia.awg.desktop.util.I18n
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
@OptIn(ExperimentalComposeUiApi::class)
class ScreensRenderTest {
    @Test
    fun rendersAllScreens() {
        check(AppPaths.configDir.absolutePath.contains("build")) { "Refusing to run outside the test config dir" }
        AppPaths.configDir.listFiles()?.forEach { it.delete() }
        File(AppPaths.configDir, "sample.conf").writeText(SAMPLE)
        File(AppPaths.configDir, "office2.conf").writeText(SAMPLE)
        // Enough tunnels for the list to scroll; they sort after the two above.
        for (i in 1..12) File(AppPaths.configDir, "tunnel$i.conf").writeText(SAMPLE)
        // Too many AllowedIPs for a QR code; sorts last so the other screenshots keep their rows.
        File(AppPaths.configDir, "zz-huge.conf").writeText(HUGE)
        seedContributors()

        val app = onEdt { AppState().also { it.start() } }
        waitUntil { onEdt { app.tunnelManager.loaded } }
        // Screenshots must not depend on the machine's locale.
        fun language(tag: String) = I18n.languages.first { it.tag == tag }
        onEdt { app.updateLanguage(language("en")) }
        val out = File("build/screens").apply { mkdirs() }

        fun shot(
            name: String,
            width: Int = 960,
            height: Int = 720,
            dark: Boolean = false,
            clicks: List<Click> = emptyList(),
        ) {
            ImageComposeScene(width, height, Density(1f)) {
                AmneziaContent(darkTheme = dark, colors = app.themeColors) { App(app) }
            }.use { scene ->
                // A few frames so that effects (config loading) can settle.
                repeat(5) { i ->
                    scene.render(i * 100_000_000L)
                    Thread.sleep(150)
                    onEdt { }
                    if (i == 1) {
                        clicks.forEachIndexed { n, click ->
                            val t = i * 100_000_000L + n * 100_000_000L
                            click.send(scene, t)
                            onEdt { }
                        }
                    }
                }
                val image = scene.render(10_000_000_000L)
                val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "$name.png").writeBytes(png)
            }
        }

        shot("list")
        // The add button of the list pane: the camera entry is disabled when
        // no camera is present (as on CI and this test's sandbox).
        shot("add-menu", clicks = listOf(Click(Offset(316f, 676f))))
        // Rows: "office2" around y = 92, "sample" around y = 150.
        shot("context-menu", clicks = listOf(Click(Offset(150f, 150f), secondary = true)))
        shot(
            "context-menu-selection",
            clicks = listOf(
                Click(Offset(150f, 92f), ctrl = true),
                Click(Offset(150f, 150f), ctrl = true),
                Click(Offset(150f, 150f), secondary = true),
            ),
        )
        val tunnel = onEdt { app.tunnelManager.tunnels.first { it.name == "sample" } }
        onEdt { app.openTunnel(tunnel) }
        shot("detail")
        shot("detail-narrow", width = 480)
        // The QR button of the detail pane's top bar, on a config too large for a QR code.
        onEdt { app.openTunnel(app.tunnelManager.tunnels.first { it.name == "zz-huge" }) }
        shot("qr-too-large", clicks = listOf(Click(Offset(736f, 32f))))
        onEdt { app.openTunnel(tunnel) }
        onEdt { app.navigate(Screen.TunnelEditor(tunnel)) }
        shot("editor")
        onEdt { app.popBackStack(); app.navigate(Screen.Settings) }
        shot("settings", dark = true)
        // Hovering the first avatar of the "Based on" row (the original project's authors) shows the name.
        shot("settings-contributor-hover", clicks = listOf(Click(Offset(866f, 234f), hover = true)))
        onEdt { app.updateThemeColors(ThemeColors(accent = Color(0xFF1E88E5), status = Color(0xFFEF6C00))) }
        shot("settings-custom-colors")
        onEdt { app.updateThemeColors(ThemeColors()) }
        // A few translations, including plurals and a CJK script.
        for (tag in listOf("ru", "de", "pt-BR", "ja")) {
            onEdt { app.updateLanguage(language(tag)); app.popBackStack(); app.popBackStack() }
            shot("lang-$tag-list", clicks = listOf(Click(Offset(316f, 676f))))
            onEdt { app.navigate(Screen.Settings) }
            shot("lang-$tag-settings")
        }
        onEdt { app.updateLanguage(language("en")); app.popBackStack() }
        onEdt { app.navigate(Screen.LogViewer) }
        shot("log-app")
        // Second tab of the tab row under the 64dp top bar.
        shot("log-system", clicks = listOf(Click(Offset(720f, 88f))))

        onEdt { app.updateLanguage(null); app.close() }
        assertTrue(File(out, "settings.png").length() > 0)
    }

    /** A mouse click as the window would deliver it: move, press, release. */
    private class Click(val at: Offset, val secondary: Boolean = false, val ctrl: Boolean = false, val hover: Boolean = false) {
        fun send(scene: ImageComposeScene, t: Long) {
            val ms = t / 1_000_000
            if (hover) {
                scene.sendPointerEvent(PointerEventType.Enter, at, timeMillis = ms)
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms + 10)
                scene.render(t + 20_000_000)
                return
            }
            val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
            val modifiers = PointerKeyboardModifiers(isCtrlPressed = ctrl)
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms, keyboardModifiers = modifiers)
            scene.render(t + 10_000_000)
            scene.sendPointerEvent(
                PointerEventType.Press, at, timeMillis = ms + 20,
                buttons = PointerButtons(isPrimaryPressed = !secondary, isSecondaryPressed = secondary),
                keyboardModifiers = modifiers, button = button,
            )
            scene.render(t + 30_000_000)
            scene.sendPointerEvent(
                PointerEventType.Release, at, timeMillis = ms + 60,
                keyboardModifiers = modifiers, button = button,
            )
            scene.render(t + 70_000_000)
        }
    }

    /**
     * Cached credits, as Settings would have them after fetching from GitHub
     * (the tests never go online): one author of this fork, three of the
     * original project, one of them without an avatar.
     */
    private fun seedContributors() {
        val dir = File(AppPaths.stateDir, "contributors").apply { deleteRecursively(); mkdirs() }
        File(dir, "credits.tsv").writeText(
            "fork\tQwand05\t\thttps://github.com/Qwand05\n" +
                "upstream\talbexk\tAlex Example\thttps://github.com/albexk\n" +
                "upstream\tvkamn\t\thttps://github.com/vkamn\n" +
                "upstream\tNickVs2015\t\thttps://github.com/NickVs2015\n"
        )
        for ((login, rgb) in listOf("Qwand05" to 0x8E24AA, "albexk" to 0x1E88E5, "vkamn" to 0x43A047)) {
            val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = java.awt.Color(rgb)
                fillRect(0, 0, 64, 64)
                color = java.awt.Color.WHITE
                fillOval(20, 12, 24, 24)
                fillOval(10, 40, 44, 36)
                dispose()
            }
            javax.imageio.ImageIO.write(image, "png", File(dir, "$login.img"))
        }
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
        /** [SAMPLE] with 2,090 AllowedIPs, about 30 KB: far past what a QR code holds. */
        val HUGE: String
            get() = SAMPLE.replace(
                "AllowedIPs = 0.0.0.0/0",
                "AllowedIPs = " + (0 until 2090).joinToString(", ") { "10.${it / 256}.${it % 256}.0/24" },
            ).also { check(it != SAMPLE) }

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
