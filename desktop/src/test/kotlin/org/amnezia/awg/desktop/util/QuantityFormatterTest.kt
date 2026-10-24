/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class QuantityFormatterTest {
    @Test
    fun formatsEveryUnit() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1023 B", QuantityFormatter.formatBytes(1023))
            assertEquals("1.50 KiB", QuantityFormatter.formatBytes(1536))
            assertEquals("2.00 MiB", QuantityFormatter.formatBytes(2L * 1024 * 1024))
            assertEquals("3.25 GiB", QuantityFormatter.formatBytes(3L * 1024 * 1024 * 1024 + 256L * 1024 * 1024))
            assertEquals("1.00 TiB", QuantityFormatter.formatBytes(1024L * 1024 * 1024 * 1024))
        } finally {
            Locale.setDefault(default)
        }
    }
}
