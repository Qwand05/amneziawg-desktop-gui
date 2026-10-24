/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config

/**
 * Desktop equivalent of the Android `Backend` interface.
 *
 * Implementations drive the AmneziaWG tools installed on the host instead of
 * Android's VpnService.
 */
interface Backend {
    /** Names of the tunnels that are currently running. */
    val runningTunnelNames: Set<String>

    /** Whether privileged queries (statistics) can run without prompting. */
    val hasPrivileges: Boolean

    /** Current state of [tunnel]. */
    fun getState(tunnel: Tunnel): Tunnel.State

    /**
     * Last handshake time for [tunnel] in seconds since the epoch.
     *
     * @return a positive value for a valid handshake, 0 when no handshake has
     *         happened yet, -1 when the value could not be parsed, -2 on
     *         command failure and -3 when the tunnel is not active.
     */
    fun getLastHandshake(tunnel: Tunnel): Long

    /** Traffic statistics for [tunnel]; empty when the tunnel is down. */
    fun getStatistics(tunnel: Tunnel): Statistics

    /** Version string of the underlying AmneziaWG module/tools. */
    fun getVersion(): String

    /**
     * Bring [tunnel] to [state], applying [config] when going up.
     *
     * @return the state the tunnel ended up in
     */
    fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State
}
