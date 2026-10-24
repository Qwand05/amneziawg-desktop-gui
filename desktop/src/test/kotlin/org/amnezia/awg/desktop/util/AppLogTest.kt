/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun keepsEntriesInMemoryAndInTheLogFile() {
        // The Gradle test task points XDG_STATE_HOME into build/.
        assertTrue(AppLog.file.absolutePath, AppLog.file.absolutePath.contains("build"))
        val before = AppLog.revision.value
        val marker = "marker-${System.nanoTime()}"

        AppLog.i("Test", "info $marker")
        AppLog.e("Test", "error $marker", IllegalStateException("boom"))

        assertEquals(before + 2, AppLog.revision.value)
        val entries = AppLog.entries().filter { marker in it.message }
        assertEquals(listOf(AppLog.Level.INFO, AppLog.Level.ERROR), entries.map { it.level })
        assertTrue("stack trace kept", entries[1].message.contains("IllegalStateException: boom"))
        assertTrue(entries[0].format().contains(" I/Test: info $marker"))

        val file = AppLog.file.readText()
        assertTrue(file.contains("I/Test: info $marker"))
        assertTrue(file.contains("E/Test: error $marker"))
    }
}
