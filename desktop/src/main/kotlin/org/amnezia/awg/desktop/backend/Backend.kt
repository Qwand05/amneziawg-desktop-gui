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
 * Android's VpnService: [AwgQuickBackend] on Linux, [WindowsBackend] on
 * Windows (see [Backend.forThisPlatform]).
 */
interface Backend {
    /** Names of the tunnels that are currently running. */
    val runningTunnelNames: Set<String>

    /** Whether privileged queries (statistics) can run without prompting. */
    val hasPrivileges: Boolean

    /** Whether tunnels can be switched at all: the platform's AmneziaWG tools are installed. */
    val toolsAvailable: Boolean

    /** Shown once at startup when [toolsAvailable] is false. */
    val toolsMissingMessage: String

    /** What runs the tunnels, for the version line in Settings ("Kernel module", "Windows client"). */
    val typeName: String

    /** How administrator rights are obtained, for Settings. */
    val privilegeDescription: String

    /** Whether [requestPrivileges] can grant [hasPrivileges] from within the app. */
    val canRequestPrivileges: Boolean

    /** Whether more than one tunnel may be up at a time. */
    var multipleTunnels: Boolean

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

    /** Version of the command line tools (`awg`), for Settings. */
    fun getToolsVersion(): String

    /** Obtain administrator rights now (prompting), so that statistics can be read. */
    fun requestPrivileges()

    /** The platform's AmneziaWG log, oldest first: the system journal on Linux, the client's log on Windows. */
    fun systemLog(lines: Int = 500): String

    fun close()

    companion object {
        fun forThisPlatform(configDir: java.io.File): Backend =
            if (org.amnezia.awg.desktop.util.AppPaths.isWindows) WindowsBackend(configDir) else AwgQuickBackend(configDir)
    }
}
