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

/** Who Settings credits: this fork's own authors, and the authors of the project it was forked from. */
data class Credits(val fork: List<Contributor>, val upstream: List<Contributor>) {
    companion object {
        val NONE = Credits(emptyList(), emptyList())
    }
}

/**
 * Credits from GitHub's public API. The fork's own authors are those of the
 * commits the fork has and the original project does not (the `compare`
 * API), so history the fork merely inherited does not count, while someone
 * from the original project who commits to the fork does. The original's
 * authors get their own row, from its contributors list.
 *
 * Fetched only when Settings is opened, at most once a day, and cached with
 * the avatars in the state directory, so they show offline too. Without a
 * cache and a connection there are none.
 */
object Contributors {
    private const val API = "https://api.github.com"
    private const val MAX = 12
    private val REFRESH_AFTER_MILLIS = TimeUnit.HOURS.toMillis(24)
    private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val REPOSITORY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val BRANCH = Regex("[A-Za-z0-9._/-]+")

    /** Set (as in the tests) to never contact GitHub. */
    private val offline: Boolean get() = System.getProperty("amnezia.awg.offline") == "true"

    private val dir: File get() = File(AppPaths.stateDir, "contributors")

    /** One "group, login, name, profile URL" line per person, tab separated; the group is "fork" or "upstream". */
    private val listFile: File get() = File(dir, "credits.tsv")

    /** "Qwand05/amneziawg-desktop-gui" */
    private val repository: String get() = BuildInfo.REPOSITORY_URL.removePrefix("https://github.com/").trimEnd('/')

    /** The cached credits; none without a cache. */
    fun cached(): Credits {
        val file = listFile
        if (!file.isFile) return Credits.NONE
        val fork = ArrayList<Contributor>()
        val upstream = ArrayList<Contributor>()
        for (line in file.readLines(StandardCharsets.UTF_8)) {
            val parts = line.split('\t')
            if (parts.size < 4 || !LOGIN.matches(parts[1])) continue
            val contributor = Contributor(parts[1], parts[2].ifEmpty { null }, parts[3], File(dir, "${parts[1]}.img").takeIf { it.isFile })
            when (parts[0]) {
                "fork" -> fork.add(contributor)
                "upstream" -> upstream.add(contributor)
            }
        }
        return Credits(fork, upstream)
    }

    val isStale: Boolean get() = !listFile.isFile || System.currentTimeMillis() - listFile.lastModified() > REFRESH_AFTER_MILLIS

    /** Fetches the credits again when the cache is older than a day; null when nothing changed or GitHub is unreachable. */
    fun refreshIfStale(): Credits? {
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

    private fun refresh(): Credits {
        dir.mkdirs()
        val repo = parseRepository(getText("$API/repos/$repository"))
        val forkUsers: List<User>
        val upstreamUsers: List<User>
        if (repo.parent == null) {
            // Not a fork: everyone in the history is this project's.
            forkUsers = parseContributors(getText("$API/repos/$repository/contributors?per_page=$MAX"))
            upstreamUsers = emptyList()
        } else {
            val head = repository.replace('/', ':') + ":" + repo.branch
            val authors = ArrayList<User>()
            // The commits only the fork has, 100 per page; GitHub lists at most 250.
            for (page in 1..3) {
                val compared = parseCompare(getText("$API/repos/${repo.parent}/compare/${repo.parentBranch}...$head?per_page=100&page=$page", 4 shl 20))
                authors += compared.authors
                if (compared.commitCount < 100) break
            }
            forkUsers = rankAuthors(authors).take(MAX)
            upstreamUsers = parseContributors(getText("$API/repos/${repo.parent}/contributors?per_page=$MAX"))
        }

        val people = (forkUsers + upstreamUsers).distinctBy { it.login }.associate { user -> user.login to fetchPerson(user) }
        val credits = Credits(forkUsers.map { people.getValue(it.login) }, upstreamUsers.map { people.getValue(it.login) })
        val lines = credits.fork.map { "fork" to it } + credits.upstream.map { "upstream" to it }
        writeAtomically(listFile, lines.joinToString("") { (group, c) ->
            listOf(group, c.login, c.name.orEmpty(), c.profileUrl).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n"
        }.toByteArray(StandardCharsets.UTF_8))
        // Avatars of people no longer listed, and the cache of version 1.2.2.
        dir.listFiles { f -> f.name.endsWith(".img") && f.name.removeSuffix(".img") !in people }?.forEach { it.delete() }
        File(dir, "contributors.tsv").delete()
        AppLog.i(TAG, "Fetched ${credits.fork.size} contributor(s) of this fork and ${credits.upstream.size} of ${repo.parent ?: "nothing"} from GitHub")
        return credits
    }

    /** The real name (one more request; the login does without it) and the avatar. */
    private fun fetchPerson(user: User): Contributor {
        val name = runCatching { parseUserName(getText("$API/users/${user.login}", 1 shl 18)) }.getOrNull()
        val avatar = File(dir, "${user.login}.img")
        runCatching {
            val separator = if ('?' in user.avatarUrl) '&' else '?'
            writeAtomically(avatar, get("${user.avatarUrl}${separator}s=64", 1 shl 19))
        }.onFailure { AppLog.w(TAG, "No avatar for ${user.login}: $it") }
        return Contributor(user.login, name, user.profileUrl, avatar.takeIf { it.isFile })
    }

    /** A GitHub account: from the contributors list, or the author of a commit. */
    internal data class User(val login: String, val profileUrl: String, val avatarUrl: String)

    /** From `GET /repos/{repo}`: its default branch and, for a fork, the original and its default branch. */
    internal data class Repository(val branch: String, val parent: String?, val parentBranch: String?)

    internal fun parseRepository(json: String): Repository {
        val repo = MiniJson.parse(json) as? Map<*, *> ?: throw IOException("Unexpected repository data")
        val branch = (repo["default_branch"] as? String)?.takeIf { BRANCH.matches(it) } ?: throw IOException("No default branch")
        val parent = repo["parent"] as? Map<*, *>
        val parentName = (parent?.get("full_name") as? String)?.takeIf { REPOSITORY.matches(it) }
        val parentBranch = (parent?.get("default_branch") as? String)?.takeIf { BRANCH.matches(it) }
        return if (parentName != null && parentBranch != null) Repository(branch, parentName, parentBranch) else Repository(branch, null, null)
    }

    /** From `GET /repos/{base}/compare/{base}...{head}`: one entry per commit whose author has a GitHub account. */
    internal data class Compared(val authors: List<User>, val commitCount: Int)

    internal fun parseCompare(json: String): Compared {
        val commits = ((MiniJson.parse(json) as? Map<*, *>)?.get("commits") as? List<*>).orEmpty()
        // A commit made with an email no GitHub account has has a null "author".
        return Compared(commits.mapNotNull { (it as? Map<*, *>)?.get("author") as? Map<*, *> }.mapNotNull(::userOf), commits.size)
    }

    /** Each author once, most commits first; ties keep the order of the first commit. */
    internal fun rankAuthors(authors: List<User>): List<User> =
        authors.groupBy { it.login }.values.sortedByDescending { it.size }.map { it.first() }

    /** Accounts from `GET /repos/{repo}/contributors`, most commits first; anonymous entries have no account and are skipped. */
    internal fun parseContributors(json: String): List<User> =
        (MiniJson.parse(json) as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::userOf) }

    private fun userOf(entry: Map<*, *>): User? {
        val login = entry["login"] as? String ?: return null
        val avatar = entry["avatar_url"] as? String ?: return null
        if (!LOGIN.matches(login) || !avatar.startsWith("https://")) return null
        return User(login, entry["html_url"] as? String ?: "https://github.com/$login", avatar)
    }

    /** The "name" of `GET /users/{login}`, if set. */
    internal fun parseUserName(json: String): String? =
        ((MiniJson.parse(json) as? Map<*, *>)?.get("name") as? String)?.trim()?.ifEmpty { null }

    private fun getText(url: String, maxBytes: Int = 1 shl 20): String = String(get(url, maxBytes), StandardCharsets.UTF_8)

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
