/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The application log, the desktop counterpart of the logcat output the
 * Android log viewer shows.
 *
 * Entries are kept in memory for the log viewer, appended to
 * `$XDG_STATE_HOME/amnezia-awg/app.log` (so they survive a crash; rotated to
 * `app.log.1` at [MAX_FILE_BYTES]) and echoed to stderr. Never log secrets
 * such as keys or whole configurations.
 */
object AppLog {
    enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String) {
        fun format(): String = "${TIME_FORMAT.format(Instant.ofEpochMilli(timeMillis))} ${level.letter}/$tag: $message"
    }

    private const val MAX_ENTRIES = 5000
    private const val MAX_FILE_BYTES = 1L shl 20
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    private val _revision = MutableStateFlow(0)

    /** Changes whenever an entry is added; collect it to refresh a view. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    val file: File by lazy { File(AppPaths.stateDir, "app.log") }

    fun entries(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Level.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)

    fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        val text = if (throwable == null) message else "$message\n${stackTrace(throwable)}"
        val entry = Entry(System.currentTimeMillis(), level, tag, text.trimEnd())
        val line = entry.format()
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            appendToFile(line)
        }
        System.err.println(line)
        _revision.value++
    }

    private fun appendToFile(line: String) {
        try {
            val log = file
            if (log.length() > MAX_FILE_BYTES) log.renameTo(File(log.parentFile, "app.log.1"))
            FileWriter(log, true).use { it.write(line + "\n") }
        } catch (_: IOException) {
            // The in-memory log still works; a full disk must not break the app.
        } catch (_: SecurityException) {
        }
    }

    private fun stackTrace(throwable: Throwable): String =
        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
}
