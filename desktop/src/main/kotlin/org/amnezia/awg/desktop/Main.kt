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
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.desktop.tray.SingleInstance
import org.amnezia.awg.desktop.tray.StatusNotifierTray
import org.amnezia.awg.desktop.tray.TrayMenuEntry
import org.amnezia.awg.desktop.tray.TrayState
import org.amnezia.awg.desktop.ui.createDesktopShortcut
import org.amnezia.awg.desktop.ui.disconnectAll
import org.amnezia.awg.desktop.ui.toggleTunnel
import org.amnezia.awg.desktop.ui.toggleTunnelNamed
import org.amnezia.awg.desktop.ui.tunnelStatusText
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.BuildInfo
import org.amnezia.awg.desktop.util.QuantityFormatter
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
import java.awt.Toolkit

/** Width from which the list and the selected tunnel are shown side by side (Android's sw600dp layout). */
private val TWO_PANE_MIN_WIDTH = 760.dp

/**
 * Command line flags:
 *  - `--hidden`: start in the tray without showing the window (for autostart).
 *  - `--toggle <name>`: connect the tunnel if it is down, disconnect it otherwise
 *    (desktop shortcuts). A running instance does it; otherwise the app starts
 *    in the tray and switches the tunnel once the list has loaded.
 */
fun main(args: Array<String>) {
    // Crashes end up in the log file, which outlives the process.
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        AppLog.e("App", "Uncaught exception in thread ${thread.name}", e)
    }
    AppLog.i(
        "App",
        "AmneziaWG for Linux ${BuildInfo.version} starting; Java ${System.getProperty("java.version")}, " +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")}, " +
            "desktop ${System.getenv("XDG_CURRENT_DESKTOP") ?: "unknown"} (${System.getenv("XDG_SESSION_TYPE") ?: "unknown"})",
    )

    setWindowClass(Strings.APP_NAME)

    val windowVisible = mutableStateOf(true)
    val showWindow = { windowVisible.value = true }
    val toggle = toggleArgument(args)
    // Tunnels to toggle: the command line's, then those forwarded by later launches.
    val toggleRequests = Channel<String>(Channel.BUFFERED)
    if (toggle != null) toggleRequests.trySend(toggle)

    // A second launch only asks the running instance to show its window (or to toggle a tunnel).
    val instance = when (
        val result = SingleInstance.acquire(
            onShow = showWindow,
            onToggleTunnel = { toggleRequests.trySend(it) },
            forward = { remote -> if (toggle != null) remote.ToggleTunnel(toggle) else remote.Show() },
        )
    ) {
        is SingleInstance.Result.Acquired -> result.instance
        SingleInstance.Result.AlreadyRunning -> {
            AppLog.i("App", "Already running; asked the running instance to ${if (toggle != null) "toggle $toggle" else "show its window"}")
            return
        }
        SingleInstance.Result.NoBus -> null
    }

    val app = AppState()
    val iconImage = StatusNotifierTray.loadIconImage()
    val icon = BitmapPainter(iconImage.toComposeImageBitmap())
    val sniTray = StatusNotifierTray.start { windowVisible.value = !windowVisible.value }
    // AWT's XEmbed tray is only a fallback for desktops without StatusNotifierItem support.
    val awtTray = sniTray == null && SystemTray.isSupported()
    app.trayAvailable = sniTray != null || awtTray
    AppLog.i(
        "App",
        when {
            sniTray != null -> "System tray: StatusNotifierItem"
            awtTray -> "System tray: legacy XEmbed (AWT)"
            else -> "System tray: none available"
        },
    )
    // A shortcut should switch its tunnel from the tray, not pop the window up.
    if (("--hidden" in args || toggle != null) && app.trayAvailable) windowVisible.value = false

    application {
        val windowState = rememberWindowState(size = DpSize(960.dp, 720.dp))
        fun quit() {
            AppLog.i("App", "Quitting")
            app.close()
            sniTray?.close()
            instance?.close()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            app.start()
            if (!app.toolsAvailable)
                app.showSnackbar(app.backend.toolsMissingMessage)
            // Shortcut requests wait for the tunnel list; a failure shows the window with the error.
            snapshotFlow { app.tunnelManager.loaded }.first { it }
            for (name in toggleRequests) app.toggleTunnelNamed(name, onFailure = showWindow)
        }

        val trayState by remember {
            derivedStateOf {
                trayState(
                    app,
                    windowVisible.value,
                    onToggleWindow = { windowVisible.value = !windowVisible.value },
                    onShowWindow = showWindow,
                    onQuit = ::quit,
                )
            }
        }
        LaunchedEffect(sniTray) {
            if (sniTray != null) snapshotFlow { trayState }.collect { sniTray.update(it) }
        }
        if (awtTray) {
            Tray(icon = icon, tooltip = trayState.tooltip, onAction = { windowVisible.value = true }) {
                TrayMenuEntries(trayState.menu)
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

/** The tunnel named by `--toggle <name>` or `--toggle=<name>`, if any. */
private fun toggleArgument(args: Array<String>): String? {
    args.forEachIndexed { i, arg ->
        if (arg == "--toggle") return args.getOrNull(i + 1)?.takeIf { !Tunnel.isNameInvalid(it) }
        if (arg.startsWith("--toggle=")) return arg.substringAfter('=').takeIf { !Tunnel.isNameInvalid(it) }
    }
    return null
}

/**
 * Tray icon contents: show/hide; a submenu per tunnel, titled with its status,
 * holding its traffic, connect/disconnect, details and a desktop shortcut;
 * disconnect all; settings and the log; quit.
 */
private fun trayState(
    app: AppState,
    windowVisible: Boolean,
    onToggleWindow: () -> Unit,
    onShowWindow: () -> Unit,
    onQuit: () -> Unit,
): TrayState {
    val tunnels = app.tunnelManager.tunnels.toList()
    val up = tunnels.filter { it.state == Tunnel.State.UP }
    fun open(screen: Screen) {
        onShowWindow()
        if (app.currentScreen != screen) app.navigate(screen)
    }
    // Keys keep each entry's dbusmenu id stable while labels change and
    // entries come and go (the traffic line, tunnels added or removed).
    val menu = buildList {
        add(TrayMenuEntry.Action(if (windowVisible) Strings.TRAY_HIDE else Strings.TRAY_SHOW, key = "window", onClick = onToggleWindow))
        add(TrayMenuEntry.Separator)
        if (tunnels.isEmpty()) add(TrayMenuEntry.Action(Strings.TRAY_NO_TUNNELS, enabled = false, key = "none") {})
        tunnels.forEach { tunnel ->
            val isUp = tunnel.state == Tunnel.State.UP
            val status = tunnelStatusText(tunnel) ?: Strings.TUNNEL_STATUS_DISCONNECTED
            val children = buildList {
                // Traffic so far, once the first statistics have been read.
                tunnel.statistics?.takeIf { isUp }?.let { stats ->
                    val transfer = Strings.transferRxTx(QuantityFormatter.formatBytes(stats.totalRx()), QuantityFormatter.formatBytes(stats.totalTx()))
                    add(TrayMenuEntry.Action(transfer, enabled = false, key = "traffic") {})
                    add(TrayMenuEntry.Separator)
                }
                add(TrayMenuEntry.Action(if (isUp) Strings.DISCONNECT else Strings.CONNECT, enabled = !tunnel.isBusy && app.toolsAvailable, key = "toggle") {
                    app.toggleTunnel(tunnel, !isUp)
                })
                add(TrayMenuEntry.Action(Strings.SHOW_DETAILS, key = "details") { onShowWindow(); app.openTunnel(tunnel) })
                add(TrayMenuEntry.Action(Strings.CREATE_SHORTCUT, key = "shortcut") { app.createDesktopShortcut(tunnel) })
            }
            add(TrayMenuEntry.Submenu("${tunnel.name} — $status", key = "tunnel:${tunnel.name}", children = children))
        }
        add(TrayMenuEntry.Separator)
        add(TrayMenuEntry.Action(Strings.TRAY_DISCONNECT_ALL, enabled = up.isNotEmpty(), key = "disconnect-all") { app.disconnectAll() })
        add(TrayMenuEntry.Separator)
        add(TrayMenuEntry.Action(Strings.SETTINGS, key = "settings") { open(Screen.Settings) })
        add(TrayMenuEntry.Action(Strings.LOG_VIEWER_PREF_TITLE, key = "log") { open(Screen.LogViewer) })
        add(TrayMenuEntry.Separator)
        add(TrayMenuEntry.Action(Strings.TRAY_QUIT, key = "quit", onClick = onQuit))
    }
    val tooltip = if (up.isEmpty()) Strings.TRAY_DISCONNECTED else Strings.trayConnected(up.map { it.name })
    return TrayState(tooltip, up.isNotEmpty(), menu)
}

/** The legacy AWT tray's menu, built from the same entries (submenus included). */
@Composable
private fun MenuScope.TrayMenuEntries(entries: List<TrayMenuEntry>) {
    entries.forEach { entry ->
        when (entry) {
            is TrayMenuEntry.Action -> Item(entry.label, enabled = entry.enabled, onClick = entry.onClick)
            is TrayMenuEntry.Toggle ->
                CheckboxItem(entry.label, checked = entry.checked, enabled = entry.enabled) { entry.onClick() }
            is TrayMenuEntry.Submenu -> Menu(entry.label, enabled = entry.enabled) { TrayMenuEntries(entry.children) }
            TrayMenuEntry.Separator -> Separator()
        }
    }
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

/**
 * Sets the X11 window class (WM_CLASS) so desktops match the window to the
 * launcher's `StartupWMClass` for its taskbar name and icon. Java would use
 * the class of the thread that creates the window ("java-lang-Thread"). Needs
 * `--add-opens java.desktop/sun.awt.X11` (see build.gradle.kts); without it the
 * default is kept.
 */
private fun setWindowClass(name: String) {
    try {
        val toolkit = Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
            isAccessible = true
            set(null, name)
        }
    } catch (e: Exception) {
        AppLog.w("App", "Could not set the window class: $e")
    }
}
