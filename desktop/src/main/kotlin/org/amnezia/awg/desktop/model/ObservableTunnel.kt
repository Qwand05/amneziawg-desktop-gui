/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.backend.Statistics

/**
 * Encapsulates the volatile and nonvolatile state of an AmneziaWG tunnel.
 *
 * The Android version observes itself through data binding; here the state is
 * held in Compose snapshot state so that the UI recomposes automatically.
 */
class ObservableTunnel internal constructor(
    private val manager: TunnelManager,
    name: String,
    config: Config?,
    state: Tunnel.State,
) : Tunnel {

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    private var nameState: String by mutableStateOf(name)

    override fun getName(): String = nameState

    var state: Tunnel.State by mutableStateOf(state)
        private set

    var connectionStatus: ConnectionStatus by mutableStateOf(ConnectionStatus.DISCONNECTED)
        private set

    var config: Config? by mutableStateOf(config)
        private set

    var statistics: Statistics? by mutableStateOf(null)

    /** Most recent error message produced while operating on this tunnel. */
    var lastError: String? by mutableStateOf(null)
        internal set

    /** True while a state change is in flight, to drive progress indicators. */
    var isBusy: Boolean by mutableStateOf(false)
        internal set

    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED

    override fun onStateChange(newState: Tunnel.State) {
        onStateChanged(newState)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.UP) {
            statistics = null
            connectionStatus = ConnectionStatus.DISCONNECTED
        } else if (connectionStatus == ConnectionStatus.DISCONNECTED) {
            // When the state changes to UP, report CONNECTING until a handshake confirms.
            connectionStatus = ConnectionStatus.CONNECTING
        }
        this.state = state
        return state
    }

    fun onConnectionStatusChanged(status: ConnectionStatus): ConnectionStatus {
        if (state != Tunnel.State.UP) return connectionStatus
        connectionStatus = status
        return status
    }

    fun onConfigChanged(config: Config?): Config? {
        this.config = config
        return config
    }

    fun onStatisticsChanged(statistics: Statistics?): Statistics? {
        this.statistics = statistics
        return statistics
    }

    suspend fun setStateAsync(state: Tunnel.State): Tunnel.State =
        if (state != this.state) manager.setTunnelState(this, state) else this.state

    suspend fun getConfigAsync(): Config =
        config ?: manager.getTunnelConfig(this)

    suspend fun setConfigAsync(config: Config): Config =
        if (config != this.config) manager.setTunnelConfig(this, config) else config

    suspend fun setNameAsync(name: String): String =
        if (name != this.name) manager.setTunnelName(this, name) else this.name

    suspend fun deleteAsync() = manager.delete(this)

    fun onNameChanged(name: String): String {
        nameState = name
        return name
    }
}
