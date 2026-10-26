/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * Filesystem locations used by the desktop application.
 *
 * Follows the XDG base directory specification: configurations and settings
 * live in `~/.config/amnezia-awg` unless `XDG_CONFIG_HOME` is set, the log in
 * `~/.local/state/amnezia-awg` unless `XDG_STATE_HOME` is set.
 */
object AppPaths {
    const val APP_DIR_NAME = "amnezia-awg"

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /**
     * The XDG variable, else the Windows one on Windows (`%APPDATA%`,
     * `%LOCALAPPDATA%`), else [fallback] under the home directory.
     */
    private fun base(xdgVariable: String, windowsVariable: String, fallback: String): String =
        System.getenv(xdgVariable)?.takeIf { it.isNotBlank() }
            ?: System.getenv(windowsVariable)?.takeIf { isWindows && it.isNotBlank() }
            ?: File(System.getProperty("user.home"), fallback).absolutePath

    val configDir: File by lazy {
        File(base("XDG_CONFIG_HOME", "APPDATA", ".config"), APP_DIR_NAME).apply { mkdirs() }
    }

    val settingsFile: File get() = File(configDir, "settings.properties")

    /** Logs and other state that may be lost: `$XDG_STATE_HOME/amnezia-awg`. */
    val stateDir: File by lazy {
        File(base("XDG_STATE_HOME", "LOCALAPPDATA", ".local/state"), APP_DIR_NAME).apply { mkdirs() }
    }

    /** Directory holding exported archives and QR images by default. */
    val defaultExportDir: File
        get() = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))
}

/**
 * Simple persisted key/value settings backing the desktop settings screen.
 *
 * The Android application uses Jetpack DataStore; a `Properties` file keeps the
 * desktop port dependency free while preserving the same keys where possible.
 */
class Prefs private constructor(private val file: File) {
    private val properties = Properties()
    private val lock = Any()

    init {
        if (file.isFile) {
            try {
                FileInputStream(file).use { properties.load(it) }
            } catch (_: IOException) {
                // Unreadable settings are not fatal; defaults are used instead.
            }
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        synchronized(lock) { properties.getProperty(key)?.toBooleanStrictOrNull() ?: default }

    fun getString(key: String, default: String?): String? =
        synchronized(lock) { properties.getProperty(key, default) }

    fun getStringSet(key: String): Set<String> =
        synchronized(lock) { properties.getProperty(key)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }

    fun putBoolean(key: String, value: Boolean) = put(key, value.toString())

    fun putString(key: String, value: String?) {
        synchronized(lock) {
            if (value == null) properties.remove(key) else properties.setProperty(key, value)
            store()
        }
    }

    fun putStringSet(key: String, values: Set<String>) =
        putString(key, if (values.isEmpty()) null else values.joinToString(","))

    private fun put(key: String, value: String) {
        synchronized(lock) {
            properties.setProperty(key, value)
            store()
        }
    }

    private fun store() {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { properties.store(it, "AmneziaWG desktop settings") }
        } catch (_: IOException) {
            // Losing a setting is preferable to crashing the UI.
        }
    }

    companion object {
        /** Preference keys, kept identical to the Android `UserKnobs`. */
        const val KEY_ENABLE_KERNEL_MODULE = "enable_kernel_module"
        const val KEY_MULTIPLE_TUNNELS = "multiple_tunnels"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_ACCENT_COLOR = "accent_color"
        const val KEY_CLOSE_TO_TRAY = "close_to_tray"
        const val KEY_LANGUAGE = "language"
        const val KEY_STATUS_COLOR = "status_color"
        const val KEY_RESTORE_ON_BOOT = "restore_on_boot"
        const val KEY_LAST_USED_TUNNEL = "last_used_tunnel"
        const val KEY_RECENT_TUNNELS = "recent_tunnels"
        const val KEY_RUNNING_TUNNELS = "enabled_configs"
        const val KEY_SORT_ORDER = "sort_order"

        fun load(): Prefs = Prefs(AppPaths.settingsFile)
    }
}
