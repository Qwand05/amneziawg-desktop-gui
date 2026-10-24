/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.backend.Backend
import org.amnezia.awg.desktop.backend.Statistics
import org.amnezia.awg.desktop.configStore.ConfigStore
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.Prefs
import org.amnezia.awg.desktop.util.QuantityFormatter
import org.amnezia.awg.desktop.util.Strings

/**
 * Maintains and mediates changes to the set of available AmneziaWG tunnels.
 *
 * Port of the Android `TunnelManager`: data binding is replaced by Compose
 * snapshot state, `Context` by [prefs]/[configStore] and the Android backend
 * by the desktop [Backend].
 */
class TunnelManager(
    private val configStore: ConfigStore,
    private val backend: Backend,
    private val prefs: Prefs,
    private val onError: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Tunnels in natural sort order. */
    val tunnels: SnapshotStateList<ObservableTunnel> = mutableStateListOf()

    var loaded: Boolean by mutableStateOf(false)
        private set

    var lastUsedTunnel: ObservableTunnel? by mutableStateOf(null)
        private set

    /** The backend is only contacted for privileged operations. */
    val isReady: Boolean get() = loaded

    fun start() {
        scope.launch {
            try {
                val present = withContext(Dispatchers.IO) { configStore.enumerate() }
                val running = withContext(Dispatchers.IO) { backend.runningTunnelNames }
                onTunnelsLoaded(present, running)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        AppLog.i(TAG, "Loaded ${present.count()} tunnel(s); running interfaces: ${running.sorted().joinToString().ifEmpty { "none" }}")
        val sorted = present.sortedWith(TunnelComparator)
        for (name in sorted) {
            tunnels.add(ObservableTunnel(this, name, null, Tunnel.State.of(running.contains(name))))
        }
        loaded = true
        val lastUsedName = prefs.getString(Prefs.KEY_LAST_USED_TUNNEL, null)
        if (lastUsedName != null) {
            lastUsedTunnel = tunnels.firstOrNull { it.name == lastUsedName }
        }
        // Unlike Android, the desktop app is not restarted by the system, and
        // tunnels outlive it; only restore when the user asked for it.
        scope.launch {
            try {
                restoreState(force = false)
            } catch (e: Throwable) {
                onError(e)
            }
        }
        startPolling()
    }

    /**
     * Keep tunnel states in sync with the system (tunnels may be changed with
     * `awg-quick` behind our back) and derive the connection status from the
     * latest handshakes, like the Android `TunnelManager` does.
     */
    private fun startPolling() {
        scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                try {
                    pollOnce()
                } catch (_: Throwable) {
                    // Transient failures are retried on the next tick.
                }
            }
        }
    }

    private suspend fun pollOnce() {
        val running = withContext(Dispatchers.IO) { backend.runningTunnelNames }
        for (tunnel in tunnels.toList()) {
            if (tunnel.isBusy) continue
            val state = Tunnel.State.of(running.contains(tunnel.name))
            if (state != tunnel.state) {
                AppLog.i(TAG, "${tunnel.name} is now ${if (state == Tunnel.State.UP) "up" else "down"} (changed outside the app)")
                tunnel.onStateChanged(state)
            }
            if (state != Tunnel.State.UP) continue
            if (!backend.hasPrivileges) {
                // Without root we cannot see handshakes; trust the interface.
                tunnel.onConnectionStatusChanged(ObservableTunnel.ConnectionStatus.CONNECTED)
                continue
            }
            val statistics = withContext(Dispatchers.IO) { backend.getStatistics(tunnel) }
            tunnel.onStatisticsChanged(statistics)
            val connected = statistics.peers().any {
                QuantityFormatter.isHandshakeRecent(statistics.peer(it)!!.latestHandshakeEpochMillis, HANDSHAKE_TIMEOUT_MILLIS)
            }
            tunnel.onConnectionStatusChanged(
                if (connected) ObservableTunnel.ConnectionStatus.CONNECTED
                else ObservableTunnel.ConnectionStatus.CONNECTING
            )
        }
    }

    /**
     * Refuse names of interfaces that are already up but not managed by this
     * application (for example a tunnel started with `awg-quick up` from
     * /etc/amnezia/amneziawg), so the GUI can never take one of them down.
     */
    private suspend fun requireNameAvailable(name: String) {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(Strings.TUNNEL_ERROR_INVALID_NAME)
        if (tunnels.any { it.name == name })
            throw IllegalArgumentException(Strings.tunnelErrorAlreadyExists(name))
        val running = withContext(Dispatchers.IO) { backend.runningTunnelNames }
        if (running.contains(name))
            throw IllegalArgumentException(Strings.interfaceAlreadyRunning(name))
    }

    @Throws(Exception::class)
    suspend fun create(name: String, config: Config): ObservableTunnel = withContext(Dispatchers.Main) {
        requireNameAvailable(name)
        val saved = withContext(Dispatchers.IO) { configStore.create(name, config) }
        AppLog.i(TAG, "Created tunnel $name")
        val tunnel = ObservableTunnel(this@TunnelManager, name, saved, Tunnel.State.DOWN)
        insertSorted(tunnel)
        tunnel
    }

    @Throws(Exception::class)
    suspend fun delete(tunnel: ObservableTunnel) = withContext(Dispatchers.Main) {
        val originalState = tunnel.state
        val wasLastUsed = tunnel === lastUsedTunnel
        if (wasLastUsed) lastUsedTunnel = null
        tunnels.remove(tunnel)
        try {
            if (originalState == Tunnel.State.UP) {
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            }
            try {
                withContext(Dispatchers.IO) { configStore.delete(tunnel.name) }
                AppLog.i(TAG, "Deleted tunnel ${tunnel.name}")
            } catch (e: Throwable) {
                if (originalState == Tunnel.State.UP) {
                    withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, tunnel.config) }
                }
                throw e
            }
        } catch (e: Throwable) {
            // Failure: put the tunnel back.
            insertSorted(tunnel)
            if (wasLastUsed) lastUsedTunnel = tunnel
            throw e
        }
    }

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config = withContext(Dispatchers.Main) {
        val config = withContext(Dispatchers.IO) { configStore.load(tunnel.name) }
        tunnel.onConfigChanged(config)!!
    }

    @Throws(Exception::class)
    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config =
        withContext(Dispatchers.Main) {
            val saved = withContext(Dispatchers.IO) {
                backend.setState(tunnel, tunnel.state, config)
                configStore.save(tunnel.name, config)
            }
            AppLog.i(TAG, "Saved the configuration of ${tunnel.name}")
            tunnel.onConfigChanged(saved)!!
        }

    @Throws(Exception::class)
    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String = withContext(Dispatchers.Main) {
        requireNameAvailable(name)
        val originalState = tunnel.state
        val wasLastUsed = tunnel === lastUsedTunnel
        if (wasLastUsed) lastUsedTunnel = null
        tunnels.remove(tunnel)
        var throwable: Throwable? = null
        var newName: String? = null
        try {
            if (originalState == Tunnel.State.UP) {
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            }
            withContext(Dispatchers.IO) { configStore.rename(tunnel.name, name) }
            AppLog.i(TAG, "Renamed tunnel ${tunnel.name} to $name")
            newName = tunnel.onNameChanged(name)
            if (originalState == Tunnel.State.UP) {
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.UP, tunnel.config) }
            }
        } catch (e: Throwable) {
            throwable = e
            // On failure we do not know the state; ask the backend to fix it.
            try {
                getTunnelState(tunnel)
            } catch (_: Throwable) {
            }
        }
        insertSorted(tunnel)
        if (wasLastUsed) lastUsedTunnel = tunnel
        if (throwable != null) throw throwable
        newName!!
    }

    @Throws(Exception::class)
    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State =
        withContext(Dispatchers.Main) {
            var newState = tunnel.state
            var throwable: Throwable? = null
            tunnel.isBusy = true
            tunnel.lastError = null
            try {
                val config = tunnel.getConfigAsync()
                newState = withContext(Dispatchers.IO) { backend.setState(tunnel, state, config) }
                if (newState == Tunnel.State.UP) {
                    lastUsedTunnel = tunnel
                    prefs.putString(Prefs.KEY_LAST_USED_TUNNEL, tunnel.name)
                }
            } catch (e: Throwable) {
                throwable = e
                newState = try {
                    withContext(Dispatchers.IO) { backend.getState(tunnel) }
                } catch (_: Throwable) {
                    tunnel.state
                }
            } finally {
                tunnel.isBusy = false
            }
            tunnel.onStateChanged(newState)
            saveState()
            if (throwable != null) {
                tunnel.lastError = throwable.message
                throw throwable
            }
            newState
        }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State = withContext(Dispatchers.Main) {
        tunnel.onStateChanged(withContext(Dispatchers.IO) { backend.getState(tunnel) })
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics = withContext(Dispatchers.Main) {
        tunnel.onStatisticsChanged(withContext(Dispatchers.IO) { backend.getStatistics(tunnel) })!!
    }

    /** Re-read the running state of every tunnel from the backend. */
    fun refreshTunnelStates() {
        scope.launch {
            try {
                val running = withContext(Dispatchers.IO) { backend.runningTunnelNames }
                for (tunnel in tunnels.toList()) {
                    tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN)
                }
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    /** Bring tunnels back up that were running when the application last exited. */
    suspend fun restoreState(force: Boolean) {
        if (!loaded || (!force && !prefs.getBoolean(Prefs.KEY_RESTORE_ON_BOOT, false))) return
        val previouslyRunning = prefs.getStringSet(Prefs.KEY_RUNNING_TUNNELS)
        if (previouslyRunning.isEmpty()) return
        val targets = tunnels.filter { previouslyRunning.contains(it.name) && it.state != Tunnel.State.UP }
        // Sequential on purpose: the first one may prompt for authentication.
        for (tunnel in targets) {
            try {
                setTunnelState(tunnel, Tunnel.State.UP)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    suspend fun saveState() {
        prefs.putStringSet(
            Prefs.KEY_RUNNING_TUNNELS,
            tunnels.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet(),
        )
    }

    fun close() {
        scope.cancel()
    }

    private fun insertSorted(tunnel: ObservableTunnel) {
        val index = tunnels.indexOfFirst { TunnelComparator.compare(it.name, tunnel.name) > 0 }
        if (index == -1) tunnels.add(tunnel) else tunnels.add(index, tunnel)
    }

    private companion object {
        const val TAG = "TunnelManager"
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 180_000L
    }
}
