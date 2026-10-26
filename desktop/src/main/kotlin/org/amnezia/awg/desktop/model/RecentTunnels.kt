/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

/** Which tunnels the tray menu lists. */
object RecentTunnels {
    /** How many tunnels the tray lists. */
    const val TRAY_LIMIT = 5

    /**
     * The [limit] most recently used of [items], most recent first. Tunnels
     * that are up are always included, so they can be disconnected from the
     * tray (there can be more than [limit] only when more are up). Slots left
     * over when fewer have been used are filled in list order.
     */
    fun <T> pick(items: List<T>, recent: List<String>, limit: Int, name: (T) -> String, isUp: (T) -> Boolean): List<T> {
        val rank = recent.withIndex().associate { (index, value) -> value to index }
        val ordered = items.withIndex()
            .sortedWith(compareBy({ rank[name(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
        val chosen = LinkedHashSet(ordered.filter(isUp))
        for (item in ordered) {
            if (chosen.size >= limit) break
            chosen += item
        }
        return ordered.filter { it in chosen }
    }
}
