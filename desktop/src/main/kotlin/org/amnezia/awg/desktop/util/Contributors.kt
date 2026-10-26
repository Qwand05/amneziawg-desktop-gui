/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** A contributor of the app's repository, shown as an avatar in Settings. */
data class Contributor(val login: String, val name: String?, val profileUrl: String, val avatar: File?) {
    /** Hover text: "Real Name (login)", or the login alone. */
    val label: String get() = if (name.isNullOrBlank() || name == login) login else "$name ($login)"
}

/**
 * The repository's contributors from GitHub's public API: who they are,
 * their names and their avatars. Fetched only when Settings is opened, at
 * most once a day, and cached in the state directory so the avatars show
 * offline too. Without a cache and a connection, there are none.
 */
object Contributors {
    private const val API = "https://api.github.com"
    private const val MAX = 12
    private val REFRESH_AFTER_MILLIS = TimeUnit.HOURS.toMillis(24)
    private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")

    /** Set (as in the tests) to never contact GitHub. */
    private val offline: Boolean get() = System.getProperty("amnezia.awg.offline") == "true"

    private val dir: File get() = File(AppPaths.stateDir, "contributors")
    private val listFile: File get() = File(dir, "contributors.tsv")

    /** "Qwand05/amneziawg-linux-gui" */
    private val repository: String get() = BuildInfo.REPOSITORY_URL.removePrefix("https://github.com/").trimEnd('/')

    /** The cached list (one "login, name, profile URL" line each, tab separated); empty without one. */
    fun cached(): List<Contributor> {
        val file = listFile
        if (!file.isFile) return emptyList()
        return file.readLines(StandardCharsets.UTF_8).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !LOGIN.matches(parts[0])) return@mapNotNull null
            Contributor(parts[0], parts[1].ifEmpty { null }, parts[2], File(dir, "${parts[0]}.img").takeIf { it.isFile })
        }
    }

    val isStale: Boolean get() = !listFile.isFile || System.currentTimeMillis() - listFile.lastModified() > REFRESH_AFTER_MILLIS

    /** Fetches the list again when the cache is older than a day; null when nothing changed or GitHub is unreachable. */
    fun refreshIfStale(): List<Contributor>? {
        if (offline || !isStale) return null
        return try {
            refresh()
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not fetch the contributors from GitHub: $e")
            // Try again tomorrow rather than on every visit to Settings.
            if (listFile.isFile) listFile.setLastModified(System.currentTimeMillis())
            null
        }
    }

    private fun refresh(): List<Contributor> {
        dir.mkdirs()
        val users = parseContributors(String(get("$API/repos/$repository/contributors?per_page=$MAX", 1 shl 20), StandardCharsets.UTF_8))
        val contributors = users.map { user ->
            // The real name needs one more request per person; the login does without it.
            val name = runCatching { parseUserName(String(get("$API/users/${user.login}", 1 shl 18), StandardCharsets.UTF_8)) }.getOrNull()
            val avatar = File(dir, "${user.login}.img")
            runCatching {
                val separator = if ('?' in user.avatarUrl) '&' else '?'
                writeAtomically(avatar, get("${user.avatarUrl}${separator}s=64", 1 shl 19))
            }.onFailure { AppLog.w(TAG, "No avatar for ${user.login}: $it") }
            Contributor(user.login, name, user.profileUrl, avatar.takeIf { it.isFile })
        }
        writeAtomically(listFile, contributors.joinToString("") { c ->
            listOf(c.login, c.name.orEmpty(), c.profileUrl).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n"
        }.toByteArray(StandardCharsets.UTF_8))
        // Avatars of people no longer listed.
        val logins = contributors.map { it.login }.toSet()
        dir.listFiles { f -> f.name.endsWith(".img") && f.name.removeSuffix(".img") !in logins }?.forEach { it.delete() }
        AppLog.i(TAG, "Fetched ${contributors.size} contributor(s) from GitHub")
        return contributors
    }

    /** One entry of the contributors API. */
    internal data class User(val login: String, val profileUrl: String, val avatarUrl: String)

    /** Accounts from `GET /repos/{repo}/contributors`, most commits first; anonymous entries have no account and are skipped. */
    internal fun parseContributors(json: String): List<User> =
        (MiniJson.parse(json) as? List<*>).orEmpty().mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val login = entry["login"] as? String ?: return@mapNotNull null
            val avatar = entry["avatar_url"] as? String ?: return@mapNotNull null
            if (!LOGIN.matches(login) || !avatar.startsWith("https://")) return@mapNotNull null
            User(login, entry["html_url"] as? String ?: "https://github.com/$login", avatar)
        }

    /** The "name" of `GET /users/{login}`, if set. */
    internal fun parseUserName(json: String): String? =
        ((MiniJson.parse(json) as? Map<*, *>)?.get("name") as? String)?.trim()?.ifEmpty { null }

    private fun get(url: String, maxBytes: Int): ByteArray {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            // GitHub's API rejects requests without a User-Agent.
            connection.setRequestProperty("User-Agent", "AmneziaWG-GUI/${BuildInfo.version}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode} for $url")
            connection.inputStream.use { input ->
                val bytes = input.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) throw IOException("Response too large: $url")
                return bytes
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private const val TAG = "Contributors"
}

/**
 * Just enough JSON for the GitHub API: objects become maps, arrays lists,
 * numbers doubles, plus strings, booleans and null.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipSpace()
        if (!parser.atEnd) throw IllegalArgumentException("Trailing data at ${parser.pos}")
        return value
    }

    private class Parser(private val s: String) {
        var pos = 0
        val atEnd get() = pos >= s.length

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun fail(what: String): Nothing = throw IllegalArgumentException("Expected $what at $pos")

        private fun expect(c: Char) {
            skipSpace()
            if (pos >= s.length || s[pos] != c) fail("'$c'")
            pos++
        }

        fun value(): Any? {
            skipSpace()
            if (atEnd) fail("a value")
            return when (s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail(word)
            pos += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (s.getOrNull(pos) == '}') { pos++; return map }
            while (true) {
                skipSpace()
                val key = string()
                expect(':')
                map[key] = value()
                skipSpace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("',' or '}'")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipSpace()
            if (s.getOrNull(pos) == ']') { pos++; return list }
            while (true) {
                list.add(value())
                skipSpace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("',' or ']'")
                }
            }
        }

        private fun string(): String {
            if (s.getOrNull(pos) != '"') fail("a string")
            pos++
            val out = StringBuilder()
            while (true) {
                if (atEnd) fail("'\"'")
                when (val c = s[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd) fail("an escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("four hex digits")
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("an escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("a number")
        }
    }
}
