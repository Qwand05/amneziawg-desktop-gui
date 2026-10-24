/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.amnezia.awg.desktop.backend.AwgQuickBackend
import org.amnezia.awg.desktop.configStore.FileConfigStore
import org.amnezia.awg.desktop.theme.ThemeColors
import org.amnezia.awg.desktop.theme.parseHexColor
import org.amnezia.awg.desktop.theme.toHex
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.AppPaths
import org.amnezia.awg.desktop.util.ErrorMessages
import org.amnezia.awg.desktop.util.Prefs

/** Navigation destinations, mirroring the Android activities and fragments. */
sealed class Screen {
    data object TunnelList : Screen()
    data class TunnelDetail(val tunnel: ObservableTunnel) : Screen()

    /** Editor for [tunnel], or for a new tunnel when it is null. */
    data class TunnelEditor(val tunnel: ObservableTunnel?) : Screen()
    data object Settings : Screen()
    data object LogViewer : Screen()
}

/**
 * Application-wide state: the backend, the [TunnelManager], user settings,
 * navigation and snackbar messages. Equivalent of the Android `Application`
 * singleton plus `MainActivity`'s back stack.
 */
class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val prefs: Prefs = Prefs.load()
    val backend = AwgQuickBackend(AppPaths.configDir).apply {
        multipleTunnels = prefs.getBoolean(Prefs.KEY_MULTIPLE_TUNNELS, false)
    }
    val tunnelManager = TunnelManager(FileConfigStore(AppPaths.configDir), backend, prefs) {
        AppLog.e("TunnelManager", ErrorMessages[it], it)
        showSnackbar(ErrorMessages[it])
    }

    var darkTheme: Boolean by mutableStateOf(prefs.getBoolean(Prefs.KEY_DARK_THEME, false))
        private set

    fun updateDarkTheme(value: Boolean) {
        darkTheme = value
        prefs.putBoolean(Prefs.KEY_DARK_THEME, value)
    }

    var themeColors: ThemeColors by mutableStateOf(
        ThemeColors(
            accent = prefs.getString(Prefs.KEY_ACCENT_COLOR, null)?.let(::parseHexColor) ?: ThemeColors.DEFAULT_ACCENT,
            status = prefs.getString(Prefs.KEY_STATUS_COLOR, null)?.let(::parseHexColor) ?: ThemeColors.DEFAULT_STATUS,
        )
    )
        private set

    fun updateThemeColors(colors: ThemeColors) {
        themeColors = colors
        // Defaults are not written, so a future change of defaults applies.
        prefs.putString(Prefs.KEY_ACCENT_COLOR, colors.accent.takeIf { it != ThemeColors.DEFAULT_ACCENT }?.toHex())
        prefs.putString(Prefs.KEY_STATUS_COLOR, colors.status.takeIf { it != ThemeColors.DEFAULT_STATUS }?.toHex())
    }

    /** Whether a system tray icon is shown; set by `Main.kt`. */
    var trayAvailable: Boolean by mutableStateOf(false)

    var closeToTray: Boolean by mutableStateOf(prefs.getBoolean(Prefs.KEY_CLOSE_TO_TRAY, true))
        private set

    fun updateCloseToTray(value: Boolean) {
        closeToTray = value
        prefs.putBoolean(Prefs.KEY_CLOSE_TO_TRAY, value)
    }

    private val backStack = mutableStateListOf<Screen>(Screen.TunnelList)

    val currentScreen: Screen get() = backStack.last()

    val canGoBack: Boolean get() = backStack.size > 1

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-shot messages shown in a snackbar, like the Android Snackbars/Toasts. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun showSnackbar(message: String) {
        _messages.tryEmit(message)
    }

    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    /** Replace the current screen, e.g. editor -> detail after saving. */
    fun replace(screen: Screen) {
        backStack[backStack.lastIndex] = screen
    }

    fun popBackStack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Show [tunnel]'s details; in the two-pane layout this replaces the previous selection. */
    fun openTunnel(tunnel: ObservableTunnel) {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(Screen.TunnelDetail(tunnel))
    }

    /** Open the editor for [tunnel], or for a new tunnel when it is null. */
    fun openEditor(tunnel: ObservableTunnel?) {
        if (tunnel == null) while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        backStack.add(Screen.TunnelEditor(tunnel))
    }

    /** The tunnel the current screen is about, highlighted in the list. */
    val selectedTunnel: ObservableTunnel?
        get() = when (val screen = currentScreen) {
            is Screen.TunnelDetail -> screen.tunnel
            is Screen.TunnelEditor -> screen.tunnel
            else -> null
        }

    /** Drop screens that refer to a tunnel which no longer exists. */
    fun forgetTunnel(tunnel: ObservableTunnel) {
        backStack.removeAll {
            (it is Screen.TunnelDetail && it.tunnel === tunnel) ||
                (it is Screen.TunnelEditor && it.tunnel === tunnel)
        }
        if (backStack.isEmpty()) backStack.add(Screen.TunnelList)
    }

    fun start() {
        tunnelManager.start()
    }

    fun close() {
        tunnelManager.close()
        backend.close()
        scope.cancel()
    }
}
