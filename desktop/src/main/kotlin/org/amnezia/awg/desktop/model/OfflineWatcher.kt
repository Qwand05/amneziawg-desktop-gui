/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.desktop.backend.Backend
import org.amnezia.awg.desktop.backend.Connectivity
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.Prefs
import org.amnezia.awg.desktop.util.Strings

/**
 * When to act on the network connection: only once it has stayed lost (or
 * back) for [settleMillis], so a short drop, such as roaming between Wi-Fi
 * access points, changes nothing.
 */
class OfflineGuard(private val settleMillis: Long = SETTLE_MILLIS) {
    enum class Action { NONE, TAKE_DOWN, BRING_BACK }

    private var candidate: Boolean? = null
    private var candidateSince = 0L

    /** Whether the connection is up, once it has stayed so for [settleMillis]; null before that. */
    var settled: Boolean? = null
        private set

    /**
     * Feed one observation ([online] is null when it could not be told).
     * Tunnels are taken down when the connection settles as lost while
     * [enabled]; tunnels taken down before ([haveTakenDown]) come back
     * once it settles as up.
     */
    fun update(online: Boolean?, nowMillis: Long, enabled: Boolean, haveTakenDown: Boolean): Action {
        if (online == null) {
            candidate = null
            return Action.NONE
        }
        if (online != candidate) {
            candidate = online
            candidateSince = nowMillis
        }
        if (nowMillis - candidateSince < settleMillis) return Action.NONE
        val previous = settled
        settled = online
        return when {
            !online && previous != false && enabled -> Action.TAKE_DOWN
            online && haveTakenDown -> Action.BRING_BACK
            else -> Action.NONE
        }
    }

    /** Forget what was seen, e.g. while the setting is off. */
    fun reset() {
        candidate = null
        settled = null
    }

    companion object {
        const val SETTLE_MILLIS = 5_000L
    }
}

/**
 * "Disconnect tunnels while offline": takes the app's running tunnels down
 * when the computer loses its network connection and brings the same ones
 * back when it returns. Which ones were taken down is kept in the settings,
 * so they also come back after a restart of the app. Turning the setting off
 * still brings back the ones it took down.
 *
 * Only tunnels of this app (in [TunnelManager.tunnels]) are ever switched;
 * every running AmneziaWG interface is left out of the connection check.
 */
class OfflineWatcher(
    private val tunnelManager: TunnelManager,
    private val backend: Backend,
    private val prefs: Prefs,
    private val connectivity: Connectivity,
    private val isEnabled: () -> Boolean,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    settleMillis: Long = OfflineGuard.SETTLE_MILLIS,
) {
    private val guard = OfflineGuard(settleMillis)

    /** Names of the tunnels taken down while offline, in the order they were up. */
    val takenDown: Set<String> get() = prefs.getStringSet(Prefs.KEY_OFFLINE_TUNNELS)

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    step(System.nanoTime() / 1_000_000)
                } catch (e: Throwable) {
                    AppLog.w(TAG, "Checking the network connection failed", e)
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** One check; idle (nothing is asked) while the setting is off and nothing waits to come back. */
    internal suspend fun step(nowMillis: Long) {
        val enabled = isEnabled()
        val takenDown = takenDown
        if (!tunnelManager.loaded || (!enabled && takenDown.isEmpty())) {
            guard.reset()
            return
        }
        val own = tunnelManager.tunnels.map { it.name }.toSet()
        val tunnels = own + withContext(Dispatchers.IO) { runCatching { backend.runningTunnelNames }.getOrDefault(emptySet()) }
        val online = withContext(Dispatchers.IO) { connectivity.isOnline(tunnels) }
        val before = guard.settled
        val action = guard.update(online, nowMillis, enabled, takenDown.isNotEmpty())
        val after = guard.settled
        if (after != before && after != null) AppLog.i(TAG, if (after) "Network connection is up" else "Network connection is down")
        when (action) {
            OfflineGuard.Action.TAKE_DOWN -> takeDown(takenDown)
            OfflineGuard.Action.BRING_BACK -> bringBack(takenDown)
            OfflineGuard.Action.NONE -> {}
        }
    }

    private suspend fun takeDown(already: Set<String>) {
        val targets = tunnelManager.tunnels.filter { it.state == Tunnel.State.UP && !it.isBusy }
        if (targets.isEmpty()) return
        AppLog.i(TAG, "Network connection lost; taking down ${targets.joinToString { it.name }}")
        val down = ArrayList<String>()
        for (tunnel in targets) {
            try {
                tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN)
                down += tunnel.name
            } catch (e: Throwable) {
                AppLog.e(TAG, "Could not take ${tunnel.name} down", e)
                onError(e)
            }
        }
        if (down.isEmpty()) return
        prefs.putStringSet(Prefs.KEY_OFFLINE_TUNNELS, already + down)
        onMessage(Strings.offlineTunnelsDown(down.joinToString(", ")))
    }

    private suspend fun bringBack(names: Set<String>) {
        prefs.putStringSet(Prefs.KEY_OFFLINE_TUNNELS, emptySet())
        val up = tunnelManager.tunnels.filter { it.state == Tunnel.State.UP }
        // One tunnel at a time: one the user connected meanwhile is not replaced.
        if (!backend.multipleTunnels && up.isNotEmpty()) {
            AppLog.i(TAG, "Network connection is back; not reconnecting ${names.joinToString()}: ${up.joinToString { it.name }} is up")
            return
        }
        val targets = names.mapNotNull { name -> tunnelManager.tunnels.firstOrNull { it.name == name } }
            .filter { it.state != Tunnel.State.UP }
        if (targets.isEmpty()) return
        AppLog.i(TAG, "Network connection is back; bringing up ${targets.joinToString { it.name }}")
        val back = ArrayList<String>()
        for (tunnel in targets) {
            try {
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)
                back += tunnel.name
            } catch (e: Throwable) {
                AppLog.e(TAG, "Could not bring ${tunnel.name} back up", e)
                onError(e)
            }
        }
        if (back.isNotEmpty()) onMessage(Strings.offlineTunnelsBack(back.joinToString(", ")))
    }

    private companion object {
        const val TAG = "OfflineWatcher"
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
