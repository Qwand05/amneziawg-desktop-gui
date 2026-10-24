/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.crypto.Key

/**
 * Transfer statistics for a tunnel, mirroring the Android `Statistics` class.
 *
 * The desktop port cannot use the Android implementation because it relies on
 * `android.os.SystemClock`, so this is a standalone equivalent.
 */
class Statistics private constructor(
    private val stats: Map<Key, PeerStats>,
    private val lastTouchedNanos: Long,
) {
    data class PeerStats(
        val rxBytes: Long,
        val txBytes: Long,
        val latestHandshakeEpochMillis: Long,
    )

    /** Check if the statistics are stale, indicating a refresh is needed. */
    fun isStale(): Boolean = System.nanoTime() - lastTouchedNanos > STALE_NANOS

    /** Statistics for a single peer, or null if the peer is unknown. */
    fun peer(key: Key): PeerStats? = stats[key]

    /** All peers tracked by this instance. */
    fun peers(): Array<Key> = stats.keys.toTypedArray()

    /** Total bytes received across all peers. */
    fun totalRx(): Long = stats.values.sumOf { it.rxBytes }

    /** Total bytes transmitted across all peers. */
    fun totalTx(): Long = stats.values.sumOf { it.txBytes }

    companion object {
        private const val STALE_NANOS = 900_000_000L

        /** An empty statistics object, used when a tunnel is down. */
        fun empty(): Statistics = Statistics(emptyMap(), System.nanoTime())

        fun of(entries: Map<Key, PeerStats>): Statistics =
            Statistics(entries, System.nanoTime())
    }
}
