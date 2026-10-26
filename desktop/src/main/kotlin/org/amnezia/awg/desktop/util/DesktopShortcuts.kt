/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.desktop.backend.WindowsCommands
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Desktop shortcuts that connect or disconnect one tunnel: `.desktop` files
 * on Linux, `.lnk` files on Windows. Each runs the app's launcher with
 * `--toggle <name>`, which asks the running instance (see `SingleInstance`),
 * or starts the app in the tray and switches the tunnel once it has loaded.
 */
object DesktopShortcuts {
    /** Where the `.deb` and `.rpm` packages install the launcher. */
    private const val INSTALLED_LAUNCHER = "/opt/amnezia-awg/bin/AmneziaWG"

    /** The Windows launcher, next to lib\ in the portable folder. */
    private const val WINDOWS_LAUNCHER = "AmneziaWG.exe"

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
     * The launcher a shortcut runs. Linux: this process's jpackage launcher
     * (a package or the portable tar.gz), else the packages' usual location
     * (when running from Gradle). Windows: AmneziaWG.exe of the portable
     * folder this app runs from. Null when there is none.
     */
    val launcher: File?
        get() = if (AppPaths.isWindows) {
            runCatching { File(DesktopShortcuts::class.java.protectionDomain.codeSource.location.toURI()).parentFile?.parentFile }
                .getOrNull()?.let { File(it, WINDOWS_LAUNCHER) }?.takeIf { it.isFile }
        } else {
            System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.canExecute() }
                ?: File(INSTALLED_LAUNCHER).takeIf { it.canExecute() }
        }

    /** Writes the shortcut for [tunnelName] and returns it ([dir] is only used on Linux). */
    fun create(tunnelName: String, launcher: File = this.launcher!!, dir: File? = null): File {
        require(!Tunnel.isNameInvalid(tunnelName)) { tunnelName }
        return if (AppPaths.isWindows) createWindows(tunnelName, launcher) else createLinux(tunnelName, launcher, dir ?: desktopDir)
    }

    private fun createLinux(tunnelName: String, launcher: File, dir: File): File {
        dir.mkdirs()
        val file = File(dir, "${AppPaths.APP_DIR_NAME}-$tunnelName.desktop")
        file.writeText(entry(tunnelName, launcher))
        // Desktops only launch .desktop files they consider trusted: KDE wants
        // the executable bit, GNOME its own "trusted" attribute.
        FilePermissions.setExecutable(file.toPath())
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

    /**
     * Windows: "AmneziaWG - <name>.lnk" on the Desktop, created through
     * WScript.Shell. Windows knows where the Desktop is (it may be moved
     * into OneDrive), so PowerShell resolves it and prints the path.
     */
    private fun createWindows(tunnelName: String, launcher: File): File {
        val script = windowsScript(tunnelName, launcher, Strings.shortcutComment(tunnelName))
        // -EncodedCommand takes UTF-16LE base64, which sidesteps all quoting.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("powershell.exe timed out")
        }
        if (process.exitValue() != 0) throw IllegalStateException(output.trim().ifEmpty { "powershell.exe failed" })
        val path = output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: throw IllegalStateException("powershell.exe printed no path")
        return File(path)
    }

    internal fun windowsScript(tunnelName: String, launcher: File, comment: String): String {
        val q = WindowsCommands::psQuote
        val exe = launcher.absolutePath
        return listOf(
            "\$ErrorActionPreference = 'Stop'",
            "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding \$false",
            "\$path = Join-Path ([Environment]::GetFolderPath('Desktop')) ${q("${Strings.APP_NAME} - $tunnelName.lnk")}",
            "\$link = (New-Object -ComObject WScript.Shell).CreateShortcut(\$path)",
            "\$link.TargetPath = ${q(exe)}",
            "\$link.Arguments = ${q("--toggle " + WindowsCommands.winQuote(tunnelName))}",
            "\$link.WorkingDirectory = ${q(launcher.absoluteFile.parent ?: "")}",
            "\$link.IconLocation = ${q("$exe,0")}",
            "\$link.Description = ${q(comment)}",
            "\$link.Save()",
            "Write-Output \$path",
        ).joinToString("\n")
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
