/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.desktop.backend.AwgQuickBackend
import org.amnezia.awg.desktop.tray.SingleInstance
import org.amnezia.awg.desktop.tray.StatusNotifierTray
import org.amnezia.awg.desktop.tray.TrayMenuEntry
import org.amnezia.awg.desktop.tray.TrayState
import org.amnezia.awg.desktop.ui.toggleTunnel
import org.amnezia.awg.desktop.model.AppState
import org.amnezia.awg.desktop.model.Screen
import org.amnezia.awg.desktop.theme.AmneziaContent
import org.amnezia.awg.desktop.ui.LogViewerScreen
import org.amnezia.awg.desktop.ui.SettingsScreen
import org.amnezia.awg.desktop.ui.TunnelDetailScreen
import org.amnezia.awg.desktop.ui.TunnelEditorScreen
import org.amnezia.awg.desktop.ui.TunnelListScreen
import org.amnezia.awg.desktop.util.FileDialogs
import org.amnezia.awg.desktop.util.Strings
import java.awt.SystemTray

/** Width from which the list and the selected tunnel are shown side by side (Android's sw600dp layout). */
private val TWO_PANE_MIN_WIDTH = 760.dp

/**
 * Command line flags:
 *  - `--hidden`: start in the tray without showing the window (for autostart).
 */
fun main(args: Array<String>) {
    val windowVisible = mutableStateOf(true)
    val showWindow = { windowVisible.value = true }

    // A second launch only asks the running instance to show its window.
    val instance = when (val result = SingleInstance.acquire(showWindow)) {
        is SingleInstance.Result.Acquired -> result.instance
        SingleInstance.Result.AlreadyRunning -> return
        SingleInstance.Result.NoBus -> null
    }

    val app = AppState()
    val iconImage = StatusNotifierTray.loadIconImage()
    val icon = BitmapPainter(iconImage.toComposeImageBitmap())
    val sniTray = StatusNotifierTray.start { windowVisible.value = !windowVisible.value }
    // AWT's XEmbed tray is only a fallback for desktops without StatusNotifierItem support.
    val awtTray = sniTray == null && SystemTray.isSupported()
    app.trayAvailable = sniTray != null || awtTray
    if ("--hidden" in args && app.trayAvailable) windowVisible.value = false

    application {
        val windowState = rememberWindowState(size = DpSize(960.dp, 720.dp))
        fun quit() {
            app.close()
            sniTray?.close()
            instance?.close()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            app.start()
            if (!AwgQuickBackend.hasTools())
                app.showSnackbar(Strings.TOOLS_MISSING)
        }

        val trayState by remember {
            derivedStateOf {
                trayState(app, windowVisible.value, onToggleWindow = { windowVisible.value = !windowVisible.value }, onQuit = ::quit)
            }
        }
        LaunchedEffect(sniTray) {
            if (sniTray != null) snapshotFlow { trayState }.collect { sniTray.update(it) }
        }
        if (awtTray) {
            Tray(icon = icon, tooltip = trayState.tooltip, onAction = { windowVisible.value = true }) {
                trayState.menu.forEach { entry ->
                    when (entry) {
                        is TrayMenuEntry.Action -> Item(entry.label, enabled = entry.enabled, onClick = entry.onClick)
                        is TrayMenuEntry.Toggle ->
                            CheckboxItem(entry.label, checked = entry.checked, enabled = entry.enabled) { entry.onClick() }
                        TrayMenuEntry.Separator -> Separator()
                    }
                }
            }
        }

        Window(
            onCloseRequest = {
                if (app.trayAvailable && app.closeToTray) windowVisible.value = false else quit()
            },
            visible = windowVisible.value,
            state = windowState,
            title = Strings.APP_NAME,
            icon = icon,
            onPreviewKeyEvent = {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) app.popBackStack() else false
            },
        ) {
            LaunchedEffect(Unit) {
                FileDialogs.parent = window
                window.minimumSize = java.awt.Dimension(420, 480)
            }
            LaunchedEffect(windowVisible.value) {
                if (windowVisible.value) {
                    windowState.isMinimized = false
                    window.toFront()
                }
            }
            AmneziaContent(darkTheme = app.darkTheme, colors = app.themeColors) {
                App(app)
            }
        }
    }
}

/** Tray icon contents: show/hide, one checkable entry per tunnel, quit. */
private fun trayState(app: AppState, windowVisible: Boolean, onToggleWindow: () -> Unit, onQuit: () -> Unit): TrayState {
    val tunnels = app.tunnelManager.tunnels.toList()
    val up = tunnels.filter { it.state == Tunnel.State.UP }
    val menu = buildList {
        add(TrayMenuEntry.Action(if (windowVisible) Strings.TRAY_HIDE else Strings.TRAY_SHOW, onClick = onToggleWindow))
        add(TrayMenuEntry.Separator)
        if (tunnels.isEmpty()) add(TrayMenuEntry.Action(Strings.TRAY_NO_TUNNELS, enabled = false) {})
        tunnels.forEach { tunnel ->
            val checked = tunnel.state == Tunnel.State.UP
            add(TrayMenuEntry.Toggle(tunnel.name, checked, enabled = !tunnel.isBusy) { app.toggleTunnel(tunnel, !checked) })
        }
        add(TrayMenuEntry.Separator)
        add(TrayMenuEntry.Action(Strings.TRAY_QUIT, onClick = onQuit))
    }
    val tooltip = if (up.isEmpty()) Strings.TRAY_DISCONNECTED else Strings.trayConnected(up.map { it.name })
    return TrayState(tooltip, up.isNotEmpty(), menu)
}

@Composable
fun App(app: AppState) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(app) {
        app.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screen = app.currentScreen
                val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
                val isTunnelScreen = screen is Screen.TunnelList || screen is Screen.TunnelDetail || screen is Screen.TunnelEditor
                if (twoPane && isTunnelScreen) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(360.dp).fillMaxHeight()) {
                            TunnelListScreen(app, app.selectedTunnel)
                        }
                        VerticalDivider()
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (screen is Screen.TunnelList) {
                                Text(
                                    Strings.SELECT_TUNNEL_HINT,
                                    Modifier.align(Alignment.Center).padding(32.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                ScreenContent(app, screen, showBack = false)
                            }
                        }
                    }
                } else {
                    ScreenContent(app, screen, showBack = true)
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

@Composable
private fun ScreenContent(app: AppState, screen: Screen, showBack: Boolean) {
    when (screen) {
        is Screen.TunnelList -> TunnelListScreen(app, null)
        is Screen.TunnelDetail -> TunnelDetailScreen(app, screen.tunnel, showBack)
        is Screen.TunnelEditor -> TunnelEditorScreen(app, screen.tunnel)
        is Screen.Settings -> SettingsScreen(app)
        is Screen.LogViewer -> LogViewerScreen(app)
    }
}
