/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Reading configs out of archives with the system's tools (each test is skipped when its tool is missing). */
class ArchivesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun tool(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    private fun run(dir: File, vararg command: String) {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.joinToString(" ")}: $output" }
    }

    /** A folder with two configs (one in a subfolder) and a file that is not one. */
    private fun content(): File {
        val dir = temp.newFolder("content")
        File(dir, "office.conf").writeText("[Interface]\nPrivateKey = a\n")
        File(dir, "more").mkdirs()
        File(dir, "more/home.conf").writeText("[Interface]\nPrivateKey = b\n")
        File(dir, "readme.txt").writeText("not a config")
        return dir
    }

    private fun assertConfigs(read: List<Pair<String, ByteArray>>) {
        assertEquals(
            mapOf("office.conf" to "[Interface]\nPrivateKey = a\n", "home.conf" to "[Interface]\nPrivateKey = b\n"),
            read.associate { (name, bytes) -> name to String(bytes) },
        )
    }

    @Test
    fun readsSevenZipArchives() {
        val sevenZip = tool("7zz") ?: tool("7z") ?: tool("7za")
        assumeTrue("no 7-Zip", sevenZip != null)
        val dir = content()
        val archive = File(temp.root, "tunnels.7z")
        run(dir, sevenZip!!, "a", archive.path, "office.conf", "more", "readme.txt")
        assertConfigs(Archives.readConfs(Archives.Tool(Archives.Kind.SEVEN_ZIP, sevenZip), archive))
    }

    @Test
    fun readsCompressedTarArchives() {
        val tar = tool("tar")
        assumeTrue("no tar", tar != null)
        val dir = content()
        for (name in listOf("tunnels.tar.gz", "tunnels.tar.xz", "tunnels.tar")) {
            val archive = File(temp.root, name)
            run(dir, tar!!, "-caf", archive.path, "office.conf", "more", "readme.txt")
            assertConfigs(Archives.readConfs(Archives.Tool(Archives.Kind.GNU_TAR, tar), archive))
        }
    }

    @Test
    fun aPasswordProtectedArchiveFailsInsteadOfWaiting() {
        val sevenZip = tool("7zz") ?: tool("7z") ?: tool("7za")
        assumeTrue("no 7-Zip", sevenZip != null)
        val dir = content()
        val archive = File(temp.root, "secret.7z")
        run(dir, sevenZip!!, "a", "-psecret", archive.path, "office.conf")
        val started = System.nanoTime()
        try {
            Archives.readConfs(Archives.Tool(Archives.Kind.SEVEN_ZIP, sevenZip), archive)
            throw AssertionError("read a password-protected archive")
        } catch (_: IOException) {
        }
        assertTrue("took too long", System.nanoTime() - started < 20_000_000_000L)
    }

    @Test
    fun parsesSevenZipListings() {
        val listing = """
            7-Zip 26.00 (x64)

            Listing archive: tunnels.7z

            --
            Path = tunnels.7z
            Type = 7z
            Physical Size = 312

            ----------
            Path = more
            Size = 0
            Attributes = D drwxrwxr-x

            Path = more/home.conf
            Size = 28
            Attributes = A -rw-rw-r--

            Path = office.conf
            Folder = -
            Size = 28
        """.trimIndent().replace("\n", "\r\n")
        assertEquals(listOf("more/home.conf", "office.conf"), Archives.parseSevenZipListing(listing))
    }

    @Test
    fun knowsWhichNamesAreArchives() {
        for (name in listOf("a.7z", "A.RAR", "a.tar", "a.tar.gz", "a.tgz", "a.tar.xz", "a.tar.bz2")) assertTrue(name, Archives.isArchive(name))
        for (name in listOf("a.conf", "a.zip", "a.gz", "a.txt")) assertFalse(name, Archives.isArchive(name))
    }

    @Test
    fun buildsTheWindowsDialogFilter() {
        assertEquals("*.conf;*.zip;*.7z;*.tar.gz", FileDialogs.windowsFilter(listOf(".conf", ".zip", ".7z", ".tar.gz", ".zip")))
    }
}
