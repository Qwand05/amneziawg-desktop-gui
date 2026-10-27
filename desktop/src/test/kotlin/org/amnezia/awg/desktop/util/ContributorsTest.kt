/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContributorsTest {
    @Test
    fun readsContributorsSkippingAnonymousOnes() {
        // GET /repos/{repo}/contributors?anon=1, abridged.
        val json = """
            [
              {"login": "albexk", "id": 1, "avatar_url": "https://avatars.githubusercontent.com/u/1?v=4",
               "html_url": "https://github.com/albexk", "type": "User", "site_admin": false, "contributions": 18},
              {"email": "someone@example.com", "name": "Qwand05", "type": "Anonymous", "contributions": 17},
              {"login": "Qwand05", "avatar_url": "https://avatars.githubusercontent.com/u/2?v=4",
               "html_url": "https://github.com/Qwand05", "type": "User", "contributions": 2},
              {"login": "bad login", "avatar_url": "https://example.com/a.png", "html_url": "x"},
              {"login": "nohttps", "avatar_url": "http://example.com/a.png", "html_url": "x"}
            ]
        """.trimIndent()
        assertEquals(
            listOf(
                Contributors.User("albexk", "https://github.com/albexk", "https://avatars.githubusercontent.com/u/1?v=4"),
                Contributors.User("Qwand05", "https://github.com/Qwand05", "https://avatars.githubusercontent.com/u/2?v=4"),
            ),
            Contributors.parseContributors(json),
        )
        assertEquals(emptyList<Contributors.User>(), Contributors.parseContributors("""{"message": "API rate limit exceeded"}"""))
    }

    @Test
    fun readsTheForkAndItsOrigin() {
        val fork = """
            {"full_name": "Qwand05/amneziawg-desktop-gui", "fork": true, "default_branch": "master",
             "parent": {"full_name": "amnezia-vpn/amneziawg-android", "default_branch": "master", "fork": false}}
        """.trimIndent()
        assertEquals(Contributors.Repository("master", "amnezia-vpn/amneziawg-android", "master"), Contributors.parseRepository(fork))
        assertEquals(Contributors.Repository("main", null, null), Contributors.parseRepository("""{"default_branch": "main", "fork": false}"""))
        // Anything odd in the parent is ignored rather than put into a URL.
        assertEquals(
            Contributors.Repository("master", null, null),
            Contributors.parseRepository("""{"default_branch": "master", "parent": {"full_name": "a/b?x=1", "default_branch": "master"}}"""),
        )
    }

    @Test
    fun countsOnlyTheForksOwnCommits() {
        // GET /repos/amnezia-vpn/amneziawg-android/compare/master...Qwand05:amneziawg-desktop-gui:master, abridged:
        // commits with an unlinked email have "author": null.
        val user = """{"login": "Qwand05", "avatar_url": "https://avatars.githubusercontent.com/u/2?v=4", "html_url": "https://github.com/Qwand05"}"""
        val other = """{"login": "albexk", "avatar_url": "https://avatars.githubusercontent.com/u/1?v=4", "html_url": "https://github.com/albexk"}"""
        val json = """
            {"status": "ahead", "ahead_by": 5, "behind_by": 0, "commits": [
              {"sha": "1", "author": $other, "commit": {"author": {"name": "albexk"}}},
              {"sha": "2", "author": $user},
              {"sha": "3", "author": null, "commit": {"author": {"name": "Qwand05", "email": "unlinked@example.com"}}},
              {"sha": "4", "author": $user},
              {"sha": "5", "author": null}
            ]}
        """.trimIndent()
        val compared = Contributors.parseCompare(json)
        assertEquals(5, compared.commitCount)
        assertEquals(listOf("albexk", "Qwand05", "Qwand05"), compared.authors.map { it.login })
        // Most commits first.
        assertEquals(listOf("Qwand05", "albexk"), Contributors.rankAuthors(compared.authors).map { it.login })
        assertEquals(0, Contributors.parseCompare("""{"message": "Not Found"}""").commitCount)
    }

    @Test
    fun readsTheRealName() {
        assertEquals("Mykola Baibuz", Contributors.parseUserName("""{"login": "mbaibuz", "name": "Mykola Baibuz", "bio": null}"""))
        assertEquals("Николай", Contributors.parseUserName("""{"name": "Николай"}"""))
        assertNull(Contributors.parseUserName("""{"login": "x", "name": null}"""))
        assertNull(Contributors.parseUserName("""{"login": "x", "name": "  "}"""))
    }

    @Test
    fun labelsShowTheNameAndLogin() {
        assertEquals("Mykola Baibuz (mbaibuz)", Contributor("mbaibuz", "Mykola Baibuz", "", null).label)
        assertEquals("albexk", Contributor("albexk", null, "", null).label)
        assertEquals("vkamn", Contributor("vkamn", "vkamn", "", null).label)
    }

    @Test
    fun parsesJson() {
        val value = MiniJson.parse("""{"a": [1, -2.5e1, true, false, null, "x\"y\\z\n"], "b": {}, "c": []}""") as Map<*, *>
        assertEquals(listOf(1.0, -25.0, true, false, null, "x\"y\\z\n"), value["a"])
        assertEquals(emptyMap<String, Any?>(), value["b"])
        assertEquals(emptyList<Any?>(), value["c"])
        for (bad in listOf("", "[1,", "{\"a\" 1}", "[1] x", "\"open")) {
            try {
                MiniJson.parse(bad)
                throw AssertionError("parsed: $bad")
            } catch (_: IllegalArgumentException) {
            } catch (_: StringIndexOutOfBoundsException) {
                throw AssertionError("crashed on: $bad")
            }
        }
    }
}
