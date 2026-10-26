/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tray's choice of tunnels; names stand in for tunnels, "+" marks one that is up. */
class RecentTunnelsTest {
    private val all = listOf("a", "b", "c", "d", "e", "f", "g", "h")

    private fun pick(tunnels: List<String>, recent: List<String>, limit: Int = 5) =
        RecentTunnels.pick(tunnels.map { it.removeSuffix("+") }, recent, limit, name = { it }, isUp = { "$it+" in tunnels })

    @Test
    fun theFiveMostRecentlyUsedComeFirst() {
        assertEquals(listOf("g", "c", "h", "a", "e"), pick(all, listOf("g", "c", "h", "a", "e", "b")))
    }

    @Test
    fun unusedOnesFillTheRemainingSlotsInListOrder() {
        assertEquals(listOf("f", "a", "b", "c", "d"), pick(all, listOf("f")))
        assertEquals(listOf("a", "b", "c", "d", "e"), pick(all, emptyList()))
        assertEquals(listOf("b", "a"), pick(listOf("a", "b"), listOf("b")))
    }

    @Test
    fun tunnelsThatAreUpAreAlwaysListed() {
        // "h" was used longest ago but is up; it takes the last slot.
        val tunnels = all.map { if (it == "h") "h+" else it }
        assertEquals(listOf("a", "b", "c", "d", "h"), pick(tunnels, listOf("a", "b", "c", "d", "e", "f", "g", "h")))
        // More tunnels up than slots: all of them stay.
        val manyUp = all.map { if (it in "bcdefg") "$it+" else it }
        assertEquals(listOf("b", "c", "d", "e", "f", "g"), pick(manyUp, emptyList()))
    }

    @Test
    fun namesOfDeletedTunnelsAreIgnored() {
        assertEquals(listOf("c", "a", "b"), pick(listOf("a", "b", "c"), listOf("gone", "c", "old")))
    }
}
