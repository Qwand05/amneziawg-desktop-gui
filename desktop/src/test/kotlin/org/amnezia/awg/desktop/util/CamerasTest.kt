/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CamerasTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun node(sysfs: File, dev: File, name: String, index: Int?, label: String, withDevice: Boolean = true) {
        File(sysfs, name).apply {
            mkdirs()
            File(this, "name").writeText("$label\n")
            if (index != null) File(this, "index").writeText("$index\n")
        }
        if (withDevice) File(dev, name).createNewFile()
    }

    @Test
    fun detectsOnlyReadableCaptureNodes() {
        val sysfs = tmp.newFolder("sysfs")
        val dev = tmp.newFolder("dev")
        node(sysfs, dev, "video0", 0, "Integrated Camera")
        node(sysfs, dev, "video1", 1, "Integrated Camera") // metadata node
        node(sysfs, dev, "video2", 0, "USB Camera", withDevice = false) // unplugged
        node(sysfs, dev, "video10", null, "Loopback") // old kernels have no index
        File(sysfs, "v4l-subdev0").mkdirs()

        val cameras = Cameras.detect(sysfs, dev)

        assertEquals(listOf("video0", "video10"), cameras.map { it.device.name })
        assertEquals("Integrated Camera", cameras.first().name)
    }

    @Test
    fun noCamerasWithoutVideo4Linux() {
        assertEquals(emptyList<CameraDevice>(), Cameras.detect(File(tmp.root, "missing"), tmp.root))
    }
}
