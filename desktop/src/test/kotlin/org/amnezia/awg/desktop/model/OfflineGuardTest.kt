/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import org.amnezia.awg.desktop.model.OfflineGuard.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineGuardTest {
    private val guard = OfflineGuard(settleMillis = 5_000)

    private fun at(seconds: Int, online: Boolean?, enabled: Boolean = true, haveTakenDown: Boolean = false) =
        guard.update(online, seconds * 1_000L, enabled, haveTakenDown)

    @Test
    fun takesDownOnceTheConnectionHasStayedLost() {
        assertEquals(Action.NONE, at(0, true))
        assertEquals(Action.NONE, at(5, true))
        assertEquals(Action.NONE, at(6, false))
        assertEquals(Action.NONE, at(10, false))
        assertEquals(Action.TAKE_DOWN, at(11, false))
        // Only once per outage.
        assertEquals(Action.NONE, at(13, false))
        assertEquals(Action.NONE, at(60, false))
    }

    @Test
    fun shortDropsChangeNothing() {
        at(0, true)
        at(5, true)
        for (t in 6..30 step 2) assertEquals(Action.NONE, at(t, t % 4 == 0))
        assertEquals(true, guard.settled)
    }

    @Test
    fun bringsBackOnceTheConnectionHasStayedUp() {
        at(0, false)
        assertEquals(Action.TAKE_DOWN, at(5, false))
        assertEquals(Action.NONE, at(7, true, haveTakenDown = true))
        assertEquals(Action.NONE, at(11, true, haveTakenDown = true))
        assertEquals(Action.BRING_BACK, at(12, true, haveTakenDown = true))
        assertEquals(Action.NONE, at(14, true, haveTakenDown = false))
    }

    @Test
    fun startingOfflineTakesDown() {
        assertEquals(Action.NONE, at(0, false))
        assertEquals(Action.TAKE_DOWN, at(5, false))
    }

    @Test
    fun disabledNeverTakesDownButStillBringsBack() {
        at(0, false, enabled = false)
        assertEquals(Action.NONE, at(5, false, enabled = false))
        at(6, true, enabled = false, haveTakenDown = true)
        assertEquals(Action.BRING_BACK, at(11, true, enabled = false, haveTakenDown = true))
    }

    @Test
    fun unknownStatesAreWaitedOut() {
        at(0, true)
        at(5, true)
        at(6, false)
        // An unknown answer restarts the wait instead of counting either way.
        assertEquals(Action.NONE, at(9, null))
        assertEquals(Action.NONE, at(10, false))
        assertEquals(Action.NONE, at(14, false))
        assertEquals(Action.TAKE_DOWN, at(15, false))
    }

    @Test
    fun resetForgetsTheSettledState() {
        at(0, false)
        at(5, false)
        guard.reset()
        at(10, false)
        // Offline again after the reset (the setting was turned on while offline).
        assertEquals(Action.TAKE_DOWN, at(15, false))
    }
}
