/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwgShowParserTest {
    @Test
    fun parsesPeerLinesAndSkipsInterfaceLine() {
        val iface = KeyPair()
        val peer = KeyPair().publicKey
        val dump = listOf(
            "${iface.privateKey.toBase64()}\t${iface.publicKey.toBase64()}\t51820\toff",
            "${peer.toBase64()}\t(none)\t203.0.113.1:51820\t0.0.0.0/0\t1700000000\t1024\t2048\t25",
        ).joinToString("\n")

        val stats = AwgShowParser.parseDump(dump)

        assertEquals(1, stats.size)
        assertEquals(Statistics.PeerStats(1024, 2048, 1_700_000_000_000), stats[peer])
        assertNull(stats[iface.publicKey])
    }

    @Test
    fun parsesInterfaceList() {
        assertEquals(setOf("wg0", "awg1"), AwgShowParser.parseInterfaces("wg0 awg1\n"))
        assertEquals(emptySet<String>(), AwgShowParser.parseInterfaces("\n"))
    }
}
