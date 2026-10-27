/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityTest {
    private val tunnels = setOf("office", "nikolay-awg")

    private fun online(routes: String) = NetworkStates.hasDefaultRoute(routes.trimIndent(), tunnels)

    @Test
    fun aDefaultRouteThroughAPhysicalInterfaceIsOnline() {
        assertTrue(online("default via 192.168.1.254 dev enp5s0 proto dhcp src 192.168.1.100 metric 100"))
        assertTrue(online("default via fe80::1 dev wlp2s0 proto ra metric 600 pref medium"))
        // IPv4 gone, IPv6 still there.
        assertTrue(online("\ndefault via fe80::1 dev wlp2s0 proto ra metric 600 pref medium"))
    }

    @Test
    fun noDefaultRouteIsOffline() {
        assertFalse(online(""))
        assertFalse(online("\n"))
        // Not routes out.
        assertFalse(online("unreachable default dev lo proto kernel metric 4294967295 pref medium"))
        assertFalse(online("blackhole default proto static"))
    }

    @Test
    fun tunnelsAndLoopbackDoNotCount() {
        assertFalse(online("default dev office scope link"))
        assertFalse(online("default dev nikolay-awg proto static\ndefault dev lo"))
        assertTrue(online("default dev office scope link\ndefault via 10.0.0.1 dev eth0"))
    }

    @Test
    fun routesOnLinksWithoutCarrierDoNotCount() {
        assertFalse(online("default via 192.168.1.1 dev eth0 proto static metric 100 linkdown"))
        assertTrue(online("default via 192.168.1.1 dev eth0 proto static metric 100 linkdown\ndefault via 192.168.2.1 dev wlan0 metric 600"))
    }

    @Test
    fun multipathRoutesCountTheirLiveNextHops() {
        // `ip -o` puts the next hops on the route's line, separated by backslashes.
        assertTrue(online("default proto static metric 100 \\\tnexthop via 10.0.0.1 dev eth0 weight 1 dead linkdown \\\tnexthop via 10.0.1.1 dev eth1 weight 1"))
        assertFalse(online("default proto static metric 100 \\\tnexthop via 10.0.0.1 dev eth0 weight 1 dead linkdown \\\tnexthop dev office weight 1"))
    }

    @Test
    fun networkManagerStates() {
        assertNull(NetworkStates.networkManagerOnline(0)) // unknown
        for (state in listOf(10L, 20L, 30L, 40L, 50L)) assertEquals("state $state", false, NetworkStates.networkManagerOnline(state))
        // CONNECTED_SITE: NetworkManager's own internet check failed, which a full tunnel can cause.
        assertEquals(true, NetworkStates.networkManagerOnline(60))
        assertEquals(true, NetworkStates.networkManagerOnline(70))
    }

    @Test
    fun windowsRoutes() {
        assertEquals(listOf("Ethernet", "Беспроводная сеть", "office"), NetworkStates.parseWindowsRoutes("route:Ethernet\r\nroute:Беспроводная сеть\r\nroute:office\r\nroute:Ethernet\r\ndone\r\n"))
        assertEquals(emptyList<String>(), NetworkStates.parseWindowsRoutes("done\r\n"))
        // Cut short (PowerShell failed half way): no answer rather than "offline".
        assertNull(NetworkStates.parseWindowsRoutes("route:Ethernet\r\n"))
        assertNull(NetworkStates.parseWindowsRoutes(NetworkStates.WINDOWS_CMDLETS_MISSING))
    }

    @Test
    fun windowsScriptIsOneLineWithoutDoubleQuotes() {
        // Passed as one command line argument.
        assertFalse(NetworkStates.WINDOWS_ROUTES_SCRIPT.contains('\n'))
        assertFalse(NetworkStates.WINDOWS_ROUTES_SCRIPT.contains('"'))
        assertTrue(NetworkStates.WINDOWS_ROUTES_SCRIPT.contains("\$_.InterfaceAlias"))
    }
}
