/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.util

import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Port of `util/QuantityFormatter.kt`: human readable byte counts and
 * "x ago" timestamps.
 */
object QuantityFormatter {

    /** Formats a byte count as B / KiB / MiB / GiB / TiB. */
    fun formatBytes(bytes: Long): String = when {
        bytes < KIB -> "$bytes B"
        // Divide as doubles: "%.2f" throws for integer arguments.
        bytes < MIB -> "%.2f KiB".format(bytes / KIB.toDouble())
        bytes < GIB -> "%.2f MiB".format(bytes / MIB.toDouble())
        bytes < TIB -> "%.2f GiB".format(bytes / GIB.toDouble())
        else -> "%.2f TiB".format(bytes / TIB.toDouble())
    }

    /**
     * Formats how long ago [epochMillis] was. Returns "now" for timestamps in
     * the future or the present, matching the Android ICU based formatter that
     * only emits the non-zero units.
     */
    fun formatEpochAgo(epochMillis: Long): String {
        if (epochMillis <= 0) return "now"
        val elapsed = System.currentTimeMillis() - epochMillis
        if (elapsed <= 0) return "now"

        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed)
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        val secs = seconds % 60

        val parts = ArrayList<String>(4)
        if (days > 0) parts.add(unit(days, "day"))
        if (hours > 0) parts.add(unit(hours, "hour"))
        if (minutes > 0) parts.add(unit(minutes, "minute"))
        if (secs > 0 || parts.isEmpty()) parts.add(unit(secs, "second"))
        return parts.joinToString(", ")
    }

    /** Whether [epochMillis] describes a handshake recent enough to be "connected". */
    fun isHandshakeRecent(epochMillis: Long, maxAgeMillis: Long = 120_000): Boolean {
        if (epochMillis <= 0) return false
        return abs(System.currentTimeMillis() - epochMillis) <= maxAgeMillis
    }

    private fun unit(value: Long, name: String) = "$value ${if (value == 1L) name else "${name}s"}"

    private const val KIB = 1024L
    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * 1024 * 1024
    private const val TIB = 1024L * 1024 * 1024 * 1024
}
