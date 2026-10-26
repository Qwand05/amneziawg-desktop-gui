/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The elevated helper's protocol and checks, run for real on Linux: the
 * server in a thread, shell scripts standing in for amneziawg.exe and awg.exe.
 */
class WindowsHelperTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun script(name: String, body: String) = File(temp.root, name).apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    @Test
    fun runsOnlyCheckedRequests() {
        val configDir = temp.newFolder("config")
        val office = File(configDir, "office.conf").apply { writeText("[Interface]\n") }
        val outside = File(temp.root, "evil.conf").apply { writeText("[Interface]\n") }
        val client = script("amneziawg", "echo \"client \$*\"\n[ \"\$2\" = fails ] && exit 3\nexit 0")
        val awg = script("awg", "echo \"awg \$*\"")
        val portFile = File(temp.root, "helper.port")
        val setup = WindowsHelperMain.Setup("token123", portFile, ProcessHandle.current().pid(), configDir, client, awg)
        val server = Thread { WindowsHelperMain.serve(setup) }.apply { isDaemon = true; start() }

        var helper: WindowsHelper? = null
        repeat(50) { if (helper == null) { helper = WindowsHelper.connect(portFile, "token123"); Thread.sleep(100) } }
        val h = helper!!
        assertTrue(h.isAlive)

        fun exec(command: String, argument: String? = null) = h.exec(command, argument).let { it.exitCode to it.output.trim() }
        val refused = WindowsHelperProtocol.REFUSED

        assertEquals(0 to "", exec(WindowsHelperProtocol.PING))
        assertEquals(0 to "client /installtunnelservice ${office.canonicalPath}", exec(WindowsHelperProtocol.INSTALL, office.path))
        // Only configs in the app's own folder, by any spelling of the path.
        assertEquals(refused, exec(WindowsHelperProtocol.INSTALL, outside.path).first)
        assertEquals(refused, exec(WindowsHelperProtocol.INSTALL, "${configDir.path}/../evil.conf").first)
        assertEquals(refused, exec(WindowsHelperProtocol.INSTALL, File(configDir, "missing.conf").path).first)
        assertEquals(refused, exec(WindowsHelperProtocol.INSTALL).first)

        assertEquals(0 to "client /uninstalltunnelservice office", exec(WindowsHelperProtocol.UNINSTALL, "office"))
        assertEquals(refused, exec(WindowsHelperProtocol.UNINSTALL, "office & del *").first)
        assertEquals(3 to "client /uninstalltunnelservice fails", exec(WindowsHelperProtocol.UNINSTALL, "fails"))

        assertEquals(0 to "awg show office dump", exec(WindowsHelperProtocol.DUMP, "office"))
        assertEquals(refused, exec(WindowsHelperProtocol.DUMP, "../x").first)
        assertEquals(0 to "client /dumplog", exec(WindowsHelperProtocol.LOG))
        assertEquals(refused, exec("format", "C:").first)

        // A request without the token gets no answer.
        val stranger = WindowsHelper.connect(portFile, "wrong")!!
        try {
            stranger.exec(WindowsHelperProtocol.PING)
            throw AssertionError("answered without the token")
        } catch (_: IOException) {
        }

        h.close()
        server.join(5000)
        assertFalse(server.isAlive)
        assertFalse(portFile.exists())
    }

    @Test
    fun startsTheHelperElevatedWithoutWaitingForIt() {
        val command = WindowsCommands.elevatedCommand("C:\\app\\runtime\\bin\\javaw.exe", listOf("-cp", "C:\\app\\lib\\*", "Main"), wait = false)
        assertTrue(command, command.contains("Start-Process -FilePath 'C:\\app\\runtime\\bin\\javaw.exe' -ArgumentList '-cp C:\\app\\lib\\* Main' -Verb RunAs -ErrorAction Stop; exit 0"))
        assertFalse(command, command.contains("-Wait"))
        assertTrue(command, command.contains("exit ${WindowsCommands.EXIT_CANCELLED}"))
    }
}
