/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    @Test
    fun versionComesFromTheBuild() {
        // Generated from `appVersion` in desktop/build.gradle.kts, never the fallback.
        assertTrue(BuildInfo.version, Regex("""\d+\.\d+(\.\d+)?""").matches(BuildInfo.version))
    }
}
