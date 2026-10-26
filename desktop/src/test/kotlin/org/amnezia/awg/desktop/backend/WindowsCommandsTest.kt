/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Windows backend's parsing and quoting; the commands themselves only run on Windows. */
class WindowsCommandsTest {
    @Test
    fun findsTunnelsByServiceDisplayName() {
        // `sc query type= service state= active`, abridged; tunnel services are
        // named by the client's services package, displayed as "AmneziaWG Tunnel: <name>".
        val output = """

            SERVICE_NAME: AmneziaWGManager
            DISPLAY_NAME: AmneziaWG Manager
                    TYPE               : 10  WIN32_OWN_PROCESS
                    STATE              : 4  RUNNING
                                            (STOPPABLE, NOT_PAUSABLE, ACCEPTS_SHUTDOWN)

            SERVICE_NAME: AmneziaWGTunnel${'$'}office-2
            DISPLAY_NAME: AmneziaWG Tunnel: office-2
                    TYPE               : 10  WIN32_OWN_PROCESS
                    STATE              : 4  RUNNING

            SERVICE_NAME: AmneziaWGTunnel${'$'}home
            DISPLAY_NAME: AmneziaWG Tunnel: home
                    STATE              : 2  START_PENDING

            SERVICE_NAME: WireGuardTunnel${'$'}other
            DISPLAY_NAME: WireGuard Tunnel: other

            SERVICE_NAME: Dnscache
            DISPLAY_NAME: DNS Client
        """.trimIndent().replace("\n", "\r\n")
        assertEquals(setOf("office-2", "home"), WindowsCommands.parseRunningTunnels(output))
        assertEquals(emptySet<String>(), WindowsCommands.parseRunningTunnels(""))
    }

    @Test
    fun findsTunnelsInNetStartOutput() {
        // `net start` on Russian Windows: translated header and footer, display names indented.
        val output = """
            Запущены следующие службы Windows:

               AmneziaWG Manager
               AmneziaWG Tunnel: Germany
               AmneziaWG Tunnel: USATexas
               DHCP-клиент
               DNS-клиент

            Команда выполнена успешно.

        """.trimIndent().replace("\n", "\r\n")
        assertEquals(setOf("Germany", "USATexas"), WindowsCommands.parseRunningTunnels(output))
    }

    @Test
    fun findsTheConsoleCodePage() {
        assertEquals("IBM866", WindowsCommands.charsetOfChcp("Текущая кодовая страница: 866\r\n")?.name())
        assertEquals("IBM437", WindowsCommands.charsetOfChcp("Active code page: 437\r\n")?.name())
        assertEquals("UTF-8", WindowsCommands.charsetOfChcp("Active code page: 65001")?.name())
        assertEquals(null, WindowsCommands.charsetOfChcp("no number here"))
        // What decoding sc's Russian message as 1251 instead of 866 looked like.
        val bytes = "Ошибка".toByteArray(charset("IBM866"))
        assertEquals("Ошибка", String(bytes, WindowsCommands.charsetOfChcp("866")!!))
        assertTrue(String(bytes, charset("windows-1251")) != "Ошибка")
    }

    @Test
    fun recognisesElevationByIntegritySid() {
        val high = "Mandatory Label\\High Mandatory Level   Label   S-1-16-12288"
        val medium = "Mandatory Label\\Medium Mandatory Level Label   S-1-16-8192"
        // Translated Windows: the label text changes, the SID does not.
        val highRu = "Обязательная метка\\Высокий обязательный уровень Метка S-1-16-12288"
        assertTrue(WindowsCommands.isElevated(high))
        assertTrue(WindowsCommands.isElevated(highRu))
        assertTrue(WindowsCommands.isElevated("S-1-16-16384"))
        assertFalse(WindowsCommands.isElevated(medium))
    }

    @Test
    fun quotesWindowsArguments() {
        assertEquals("/installtunnelservice", WindowsCommands.winQuote("/installtunnelservice"))
        assertEquals("office", WindowsCommands.winQuote("office"))
        assertEquals("\"C:\\Users\\Jane Doe\\AppData\\Roaming\\amnezia-awg\\office.conf\"",
            WindowsCommands.winQuote("C:\\Users\\Jane Doe\\AppData\\Roaming\\amnezia-awg\\office.conf"))
        // Backslashes before the closing quote are doubled, embedded quotes escaped.
        assertEquals("\"C:\\dir with space\\\\\"", WindowsCommands.winQuote("C:\\dir with space\\"))
        assertEquals("\"say \\\"hi\\\"\"", WindowsCommands.winQuote("say \"hi\""))
        assertEquals("\"\"", WindowsCommands.winQuote(""))
    }

    @Test
    fun quotesPowerShellLiterals() {
        assertEquals("'C:\\Program Files\\AmneziaWG\\amneziawg.exe'", WindowsCommands.psQuote("C:\\Program Files\\AmneziaWG\\amneziawg.exe"))
        assertEquals("'C:\\Users\\O''Brien'", WindowsCommands.psQuote("C:\\Users\\O'Brien"))
    }

    @Test
    fun buildsTheElevatedCommand() {
        val command = WindowsCommands.elevatedCommand(
            "C:\\Program Files\\AmneziaWG\\amneziawg.exe",
            listOf("/installtunnelservice", "C:\\Users\\O'Brien\\AppData\\Roaming\\amnezia-awg\\home.conf"),
        )
        assertTrue(command, command.contains("Start-Process -FilePath 'C:\\Program Files\\AmneziaWG\\amneziawg.exe'"))
        assertTrue(command, command.contains("-ArgumentList '/installtunnelservice C:\\Users\\O''Brien\\AppData\\Roaming\\amnezia-awg\\home.conf'"))
        assertTrue(command, command.contains("-Verb RunAs -Wait -PassThru"))
        assertTrue(command, command.contains("exit \$p.ExitCode"))
        assertTrue(command, command.contains("exit ${WindowsCommands.EXIT_CANCELLED}"))
    }
}
