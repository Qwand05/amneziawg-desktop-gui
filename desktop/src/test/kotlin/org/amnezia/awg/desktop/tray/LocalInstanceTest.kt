/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The single-instance check used where there is no D-Bus (Windows). */
class LocalInstanceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aSecondLaunchForwardsItsRequestToTheFirst() {
        val dir = temp.newFolder("state")
        val received = LinkedBlockingQueue<String>()
        val first = LocalInstance.acquire(null, onShow = { received.add("show") }, onToggleTunnel = { received.add("toggle $it") }, dir = dir)
        assertTrue(first is SingleInstance.Result.Acquired)
        val portFile = File(dir, "instance.port")
        assertTrue(portFile.isFile)

        // A shortcut's launch, then a plain one.
        val second = LocalInstance.acquire("office-2", onShow = {}, onToggleTunnel = {}, dir = dir)
        assertEquals(SingleInstance.Result.AlreadyRunning, second)
        assertEquals("toggle office-2", received.poll(5, TimeUnit.SECONDS))
        assertEquals(SingleInstance.Result.AlreadyRunning, LocalInstance.acquire(null, onShow = {}, onToggleTunnel = {}, dir = dir))
        assertEquals("show", received.poll(5, TimeUnit.SECONDS))

        // After the first one exits, the next launch becomes the instance.
        (first as SingleInstance.Result.Acquired).instance.close()
        assertFalse(portFile.exists())
        val third = LocalInstance.acquire(null, onShow = {}, onToggleTunnel = {}, dir = dir)
        assertTrue(third is SingleInstance.Result.Acquired)
        (third as SingleInstance.Result.Acquired).instance.close()
    }

    @Test
    fun requestsNeedTheToken() {
        assertEquals(LocalInstance.Request.Show, LocalInstance.parse("abc show", "abc"))
        assertEquals(LocalInstance.Request.Toggle("home"), LocalInstance.parse("abc toggle home\r", "abc"))
        assertNull(LocalInstance.parse("wrong show", "abc"))
        assertNull(LocalInstance.parse("show", "abc"))
        assertNull(LocalInstance.parse("abc toggle", "abc"))
        assertNull(LocalInstance.parse("abc delete home", "abc"))
    }
}
