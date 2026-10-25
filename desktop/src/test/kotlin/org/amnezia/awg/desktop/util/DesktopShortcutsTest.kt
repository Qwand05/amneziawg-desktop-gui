/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class DesktopShortcutsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun desktopDirFollowsUserDirs() {
        val home = File("/home/someone")
        assertEquals(File(home, "Desktop"), DesktopShortcuts.desktopDir(home, null))
        assertEquals(File(home, "Desktop"), DesktopShortcuts.desktopDir(home, "# nothing here\n"))
        val userDirs = """
            # This file is written by xdg-user-dirs-update
            XDG_DESKTOP_DIR="${'$'}HOME/Рабочий стол"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Downloads"
        """.trimIndent()
        assertEquals(File(home, "Рабочий стол"), DesktopShortcuts.desktopDir(home, userDirs))
        assertEquals(File("/data/desk"), DesktopShortcuts.desktopDir(home, "XDG_DESKTOP_DIR=\"/data/desk\""))
    }

    @Test
    fun quotesExecArguments() {
        assertEquals("\"/opt/amnezia-awg/bin/AmneziaWG\"", DesktopShortcuts.quoteExecArg("/opt/amnezia-awg/bin/AmneziaWG"))
        assertEquals("\"a b\"", DesktopShortcuts.quoteExecArg("a b"))
        // Each special character gets a backslash, doubled for the file format.
        assertEquals("\"\\\\\$x\\\\\"\\\\`\\\\\\\\\"", DesktopShortcuts.quoteExecArg("\$x\"`\\"))
    }

    @Test
    fun writesAnExecutableLauncherEntry() {
        // The jpackage layout: <app>/bin/AmneziaWG and <app>/lib/AmneziaWG.png.
        val appDir = temp.newFolder("app")
        val launcher = File(appDir, "bin/AmneziaWG").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
        val icon = File(appDir, "lib/AmneziaWG.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val desktop = File(temp.root, "Desktop")

        val file = DesktopShortcuts.create("office-2", launcher, desktop)

        assertEquals(File(desktop, "amnezia-awg-office-2.desktop"), file)
        val lines = file.readLines()
        assertEquals("[Desktop Entry]", lines.first())
        assertTrue("Type=Application" in lines)
        assertTrue("Name=office-2" in lines)
        assertTrue("Exec=\"${launcher.absolutePath}\" --toggle \"office-2\"" in lines)
        assertTrue("Icon=${icon.path}" in lines)
        assertTrue("StartupWMClass=AmneziaWG" in lines)
        assertTrue("Comment=Connect or disconnect the AmneziaWG tunnel “office-2”" in lines)
        assertTrue("Comment[ru]=Подключить или отключить туннель AmneziaWG “office-2”" in lines)
        assertTrue("Comment[pt_BR]=Conectar ou desconectar o túnel AmneziaWG “office-2”" in lines)
        assertFalse(lines.any { it.startsWith("Comment[en]") })
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(file.toPath()))
    }

    @Test
    fun fallsBackToAThemeIconWithoutThePackagedOne() {
        val launcher = temp.newFile("AmneziaWG")
        val lines = DesktopShortcuts.entry("home", launcher).lines()
        assertTrue("Icon=network-vpn" in lines)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesInvalidTunnelNames() {
        DesktopShortcuts.create("bad name", temp.newFile("AmneziaWG"), temp.newFolder("Desktop"))
    }
}
