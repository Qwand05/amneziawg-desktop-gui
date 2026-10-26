/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Reads the `.conf` files out of archives other than zip (which
 * [TunnelImporter] reads itself) with the system's archive tool, streaming
 * each one to memory; nothing is unpacked to disk.
 *
 *  - Windows: its own tar.exe, which is bsdtar (libarchive), built in since
 *    Windows 10 1803: 7z, RAR (including RAR5) and tar archives.
 *  - Linux: bsdtar or 7-Zip for 7z and RAR when installed, bsdtar or GNU tar
 *    for tar archives.
 */
object Archives {
    private val SEVEN_ZIP_AND_RAR = listOf(".7z", ".rar")
    private val TAR = listOf(".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz2", ".tbz")
    private const val MAX_ENTRIES = 200
    private const val MAX_CONF_BYTES = 1 shl 20

    internal enum class Kind { BSDTAR, SEVEN_ZIP, GNU_TAR }

    internal data class Tool(val kind: Kind, val command: String)

    private val bsdtar: Tool? by lazy {
        if (AppPaths.isWindows) {
            File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\tar.exe").takeIf { it.isFile }?.let { Tool(Kind.BSDTAR, it.path) }
        } else {
            onPath("bsdtar")?.let { Tool(Kind.BSDTAR, it) }
        }
    }

    private val sevenZip: Tool? by lazy {
        val installed = if (AppPaths.isWindows) {
            File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "7-Zip\\7z.exe").takeIf { it.isFile }?.path ?: onPath("7z.exe")
        } else {
            onPath("7zz") ?: onPath("7z") ?: onPath("7za")
        }
        installed?.let { Tool(Kind.SEVEN_ZIP, it) }
    }

    /** GNU tar reads (compressed) tar archives; on Windows, tar.exe is bsdtar instead. */
    private val gnuTar: Tool? by lazy { if (AppPaths.isWindows) null else onPath("tar")?.let { Tool(Kind.GNU_TAR, it) } }

    private fun onPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path

    private fun toolFor(name: String): Tool? {
        val lower = name.lowercase()
        return when {
            SEVEN_ZIP_AND_RAR.any { lower.endsWith(it) } -> bsdtar ?: sevenZip
            TAR.any { lower.endsWith(it) } -> bsdtar ?: gnuTar
            else -> null
        }
    }

    /** Archive suffixes besides .zip that this system can read, for the import dialog's filter. */
    val readableExtensions: List<String>
        get() = (if (bsdtar != null || sevenZip != null) SEVEN_ZIP_AND_RAR else emptyList()) +
            (if (bsdtar != null || gnuTar != null) TAR else emptyList())

    /** Whether [name] is an archive this class handles (whether or not a tool is installed). */
    fun isArchive(name: String): Boolean = (SEVEN_ZIP_AND_RAR + TAR).any { name.lowercase().endsWith(it) }

    /** The `.conf` files in [file], as (file name without folders, contents). */
    fun readConfs(file: File): List<Pair<String, ByteArray>> {
        val tool = toolFor(file.name) ?: throw IllegalStateException(Strings.archiveToolMissing(file.name.substringAfterLast('.')))
        return readConfs(tool, file)
    }

    internal fun readConfs(tool: Tool, file: File): List<Pair<String, ByteArray>> {
        val listing = String(run(listCommand(tool, file), 4 shl 20), StandardCharsets.UTF_8)
        val members = (if (tool.kind == Kind.SEVEN_ZIP) parseSevenZipListing(listing) else listing.lines())
            .map { it.trimEnd('\r') }
            .filter { it.lowercase().endsWith(".conf") }
            .take(MAX_ENTRIES)
        return members.map { member ->
            member.substringAfterLast('/').substringAfterLast('\\') to run(extractCommand(tool, file, member), MAX_CONF_BYTES)
        }
    }

    private fun listCommand(tool: Tool, file: File): List<String> = when (tool.kind) {
        Kind.BSDTAR, Kind.GNU_TAR -> listOf(tool.command, "-tf", file.path)
        Kind.SEVEN_ZIP -> listOf(tool.command, "l", "-slt", file.path)
    }

    /** Writes one member to standard output; -spd: 7-Zip must not read the name as a wildcard. */
    private fun extractCommand(tool: Tool, file: File, member: String): List<String> = when (tool.kind) {
        Kind.BSDTAR, Kind.GNU_TAR -> listOf(tool.command, "-xOf", file.path, member)
        Kind.SEVEN_ZIP -> listOf(tool.command, "e", "-so", "-spd", file.path, member)
    }

    /**
     * Member paths from `7z l -slt`: one "Path = …" per entry after the
     * "----------" line (the ones before it describe the archive itself),
     * folders left out.
     */
    internal fun parseSevenZipListing(output: String): List<String> {
        val entries = output.lines().map { it.trimEnd('\r') }.dropWhile { it != "----------" }.drop(1)
        val paths = ArrayList<String>()
        var path: String? = null
        var folder = false
        fun flush() {
            path?.let { if (!folder) paths.add(it) }
            path = null
            folder = false
        }
        for (line in entries) {
            when {
                line.startsWith("Path = ") -> { flush(); path = line.removePrefix("Path = ") }
                line == "Folder = +" || (line.startsWith("Attributes = ") && line.removePrefix("Attributes = ").startsWith("D")) -> folder = true
            }
        }
        flush()
        return paths
    }

    /**
     * Runs [command] and returns its standard output, at most [maxBytes].
     * Standard input is closed at once, so a password-protected archive
     * fails instead of waiting for a password.
     */
    private fun run(command: List<String>, maxBytes: Int): ByteArray {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        val stderr = StringBuilder()
        val errorReader = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        val output = process.inputStream.use { it.readNBytes(maxBytes + 1) }
        if (output.size > maxBytes) {
            process.destroyForcibly()
            throw IOException("${File(command.first()).name}: output too large")
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("${File(command.first()).name} timed out")
        }
        errorReader.join(2000)
        if (process.exitValue() != 0) {
            val message = stderr.toString().lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            throw IOException(message ?: "${File(command.first()).name} failed (exit code ${process.exitValue()})")
        }
        return output
    }
}
