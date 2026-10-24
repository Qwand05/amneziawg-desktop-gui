/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.crypto.Key

/**
 * Parsers for the tab separated output of `awg show ... dump`.
 *
 * `wg show <interface> dump` (and therefore `awg show`) prints:
 *
 *  - one interface line: private key, public key, listen port, fwmark
 *  - one line per peer: public key, preshared key, endpoint, allowed ips,
 *    latest handshake, transfer rx, transfer tx, persistent keepalive
 *
 * The interface line has four fields, peer lines have eight, which is exactly
 * how the Android backend distinguishes them.
 */
object AwgShowParser {
    private const val PEER_FIELD_COUNT = 8

    /**
     * Parse the output of `awg show <name> dump` into per-peer statistics.
     *
     * @return a map keyed by peer public key
     */
    fun parseDump(dump: String): Map<Key, Statistics.PeerStats> {
        val result = LinkedHashMap<Key, Statistics.PeerStats>()
        for (line in dump.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != PEER_FIELD_COUNT) continue
            try {
                val key = Key.fromBase64(parts[0])
                val handshakeSeconds = parts[4].toLongOrNull() ?: continue
                val rx = parts[5].toLongOrNull() ?: continue
                val tx = parts[6].toLongOrNull() ?: continue
                result[key] = Statistics.PeerStats(rx, tx, handshakeSeconds * 1000)
            } catch (_: Exception) {
                // Skip lines we cannot make sense of, like the Android backend does.
            }
        }
        return result
    }

    /**
     * Parse the output of `awg show interfaces` (all interface names on a
     * single whitespace separated line) into a set of running tunnels.
     */
    fun parseInterfaces(output: String): Set<String> =
        output.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
