/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.amnezia.awg.backend.Tunnel
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Desktop shortcuts: `.desktop` files on the user's Desktop that connect or
 * disconnect one tunnel. Each runs the installed launcher with
 * `--toggle <name>`, which asks the running instance over D-Bus, or starts
 * the app in the tray and switches the tunnel once it has loaded.
 */
object DesktopShortcuts {
    /** Where the `.deb` package installs the launcher. */
    private const val INSTALLED_LAUNCHER = "/opt/amnezia-awg/bin/AmneziaWG"

    private val home: File get() = File(System.getProperty("user.home"))

    /** The user's Desktop folder: `XDG_DESKTOP_DIR` from `~/.config/user-dirs.dirs`, or `~/Desktop`. */
    val desktopDir: File
        get() = desktopDir(home, File(home, ".config/user-dirs.dirs").takeIf { it.isFile }?.readText())

    internal fun desktopDir(home: File, userDirs: String?): File {
        val value = userDirs?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XDG_DESKTOP_DIR=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
            ?.replace("\$HOME", home.path)
        return if (value.isNullOrEmpty()) File(home, "Desktop") else File(value)
    }

    /**
     * The launcher a shortcut runs: this process's jpackage launcher when the
     * app was installed from the package, else the package's usual location
     * (when running from Gradle), or null when neither exists.
     */
    val launcher: File?
        get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.canExecute() }
            ?: File(INSTALLED_LAUNCHER).takeIf { it.canExecute() }

    /** Writes the shortcut for [tunnelName] and returns it. */
    fun create(tunnelName: String, launcher: File = this.launcher!!, dir: File = desktopDir): File {
        require(!Tunnel.isNameInvalid(tunnelName)) { tunnelName }
        dir.mkdirs()
        val file = File(dir, "${AppPaths.APP_DIR_NAME}-$tunnelName.desktop")
        file.writeText(entry(tunnelName, launcher))
        // Desktops only launch .desktop files they consider trusted: KDE wants
        // the executable bit, GNOME its own "trusted" attribute.
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        markTrusted(file)
        return file
    }

    internal fun entry(tunnelName: String, launcher: File): String = buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        appendLine("Version=1.5")
        appendLine("Name=$tunnelName")
        // The comment in every language, like the launcher's entry.
        for (language in I18n.languages) {
            val comment = String.format(language.locale, I18n.load(language)["shortcut_comment"], tunnelName)
            val suffix = if (language.tag == "en") "" else "[${language.tag.replace('-', '_')}]"
            appendLine("Comment$suffix=$comment")
        }
        appendLine("Exec=${quoteExecArg(launcher.absolutePath)} --toggle ${quoteExecArg(tunnelName)}")
        // jpackage puts the icon next to the launcher: <app>/bin/AmneziaWG, <app>/lib/AmneziaWG.png.
        val icon = launcher.absoluteFile.parentFile?.parentFile?.let { File(it, "lib/${launcher.name}.png") }
        appendLine("Icon=${if (icon?.isFile == true) icon.path else "network-vpn"}")
        appendLine("Terminal=false")
        appendLine("Categories=Network;")
        appendLine("StartupNotify=false")
        appendLine("StartupWMClass=${Strings.APP_NAME}")
    }

    /**
     * Quotes an `Exec` argument as the desktop entry specification requires:
     * double quotes, with `"`, `` ` ``, `$` and `\` escaped; the backslash of
     * each escape is itself doubled because the string escape rule of the
     * file format is applied first.
     */
    internal fun quoteExecArg(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\\\\\")
                '"', '`', '$' -> append("\\\\").append(c)
                else -> append(c)
            }
        }
        append('"')
    }

    /** GNOME's "Allow Launching", stored as a file attribute by `gio`; best effort. */
    private fun markTrusted(file: File) {
        try {
            val process = ProcessBuilder("gio", "set", file.absolutePath, "metadata::trusted", "true")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (_: Exception) {
            // No gio: KDE and most other desktops go by the executable bit alone.
        }
    }
}
