/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.model

import kotlinx.coroutines.runBlocking
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.KeyPair
import org.amnezia.awg.desktop.backend.Backend
import org.amnezia.awg.desktop.backend.Connectivity
import org.amnezia.awg.desktop.backend.Statistics
import org.amnezia.awg.desktop.configStore.ConfigStore
import org.amnezia.awg.desktop.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The watcher against a fake system: the app has the tunnels "office" and
 * "home"; "nikolay-awg" is an interface it did not create.
 */
class OfflineWatcherTest {
    private val settings = Files.createTempFile("offline-watcher", ".properties").toFile()
    private val prefs = Prefs.load(settings)
    private val backend = FakeBackend(running = mutableSetOf("office", "nikolay-awg"))
    private val connectivity = FakeConnectivity()
    private val manager = TunnelManager(FakeStore(setOf("office", "home")), backend, prefs)
    private val messages = ArrayList<String>()
    private val errors = ArrayList<Throwable>()
    private var enabled = true
    private val watcher = OfflineWatcher(
        manager, backend, prefs, connectivity,
        isEnabled = { enabled }, onMessage = { messages += it }, onError = { errors += it }, settleMillis = 5_000,
    )

    init {
        manager.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (!manager.loaded) {
            check(System.currentTimeMillis() < deadline) { "Tunnels did not load" }
            Thread.sleep(20)
        }
    }

    @After
    fun tearDown() {
        manager.close()
        settings.delete()
    }

    private fun step(seconds: Int, online: Boolean?) {
        connectivity.online = online
        runBlocking { watcher.step(seconds * 1_000L) }
    }

    private fun tunnel(name: String) = manager.tunnels.first { it.name == name }

    @Test
    fun takesTheAppsTunnelsDownWhileOfflineAndBringsThemBack() {
        step(0, true)
        step(5, true)
        assertEquals(emptyList<String>(), backend.calls)

        step(6, false)
        step(10, false)
        assertEquals("not before the connection has stayed lost", emptyList<String>(), backend.calls)
        step(11, false)
        assertEquals(listOf("office DOWN"), backend.calls)
        assertEquals(setOf("nikolay-awg"), backend.running)
        assertEquals(setOf("office"), watcher.takenDown)
        assertEquals(1, messages.size)
        assertTrue(messages[0], "office" in messages[0])
        // Every AmneziaWG interface is left out of the connection check, the app's or not.
        assertTrue(connectivity.excluded.containsAll(setOf("office", "home", "nikolay-awg")))

        step(13, false)
        assertEquals("once per outage", listOf("office DOWN"), backend.calls)

        step(14, true)
        step(19, true)
        assertEquals(listOf("office DOWN", "office UP"), backend.calls)
        assertEquals(Tunnel.State.UP, tunnel("office").state)
        assertEquals(emptySet<String>(), watcher.takenDown)
        assertEquals(2, messages.size)
        assertTrue(messages[1], "office" in messages[1])
        assertEquals(emptyList<Throwable>(), errors)
    }

    @Test
    fun aTunnelConnectedMeanwhileIsNotReplaced() {
        step(0, false)
        step(5, false)
        assertEquals(listOf("office DOWN"), backend.calls)
        runBlocking { manager.setTunnelState(tunnel("home"), Tunnel.State.UP) }

        step(6, true)
        step(11, true)
        // One tunnel at a time: "home" stays, "office" is forgotten.
        assertEquals(listOf("office DOWN", "home UP"), backend.calls)
        assertEquals(emptySet<String>(), watcher.takenDown)
    }

    @Test
    fun theTunnelsComeBackAfterARestartOfTheApp() {
        // The app restarts with none of its tunnels up, "home" still remembered, and the setting turned off.
        runBlocking { manager.setTunnelState(tunnel("office"), Tunnel.State.DOWN) }
        backend.calls.clear()
        prefs.putStringSet(Prefs.KEY_OFFLINE_TUNNELS, setOf("home"))
        enabled = false
        step(0, true)
        step(5, true)
        assertEquals(listOf("home UP"), backend.calls)
        assertEquals(setOf("home", "nikolay-awg"), backend.running)
    }

    @Test
    fun offAndNothingToBringBackAsksNothing() {
        enabled = false
        step(0, false)
        step(10, false)
        assertEquals(0, connectivity.queries)
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test
    fun anUnknownConnectionStateChangesNothing() {
        step(0, null)
        step(30, null)
        assertEquals(emptyList<String>(), backend.calls)
    }

    private class FakeConnectivity : Connectivity {
        var online: Boolean? = true
        var excluded: Set<String> = emptySet()
        var queries = 0

        override fun isOnline(tunnels: Set<String>): Boolean? {
            queries++
            excluded = tunnels
            return online
        }
    }

    private class FakeStore(private val names: Set<String>) : ConfigStore {
        override fun create(name: String, config: Config) = config
        override fun delete(name: String) {}
        override fun enumerate() = names
        override fun load(name: String): Config = Config.parse(
            """
            [Interface]
            PrivateKey = ${KeyPair().privateKey.toBase64()}
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = ${KeyPair().publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 192.0.2.1:51820
            """.trimIndent().byteInputStream()
        )
        override fun rename(name: String, replacement: String) {}
        override fun save(name: String, config: Config) = config
    }

    /** Records every switch as "<name> UP|DOWN"; only one tunnel at a time, like the real backends' default. */
    private class FakeBackend(val running: MutableSet<String>) : Backend {
        val calls = ArrayList<String>()
        override val runningTunnelNames: Set<String> get() = synchronized(this) { running.toSet() }
        override val hasPrivileges = false
        override val toolsAvailable = true
        override val toolsMissingMessage = ""
        override val typeName = "Fake"
        override val privilegeDescription = ""
        override val canRequestPrivileges = false
        override var multipleTunnels = false
        override fun getState(tunnel: Tunnel) = Tunnel.State.of(tunnel.name in runningTunnelNames)
        override fun getLastHandshake(tunnel: Tunnel) = -3L
        override fun getStatistics(tunnel: Tunnel): Statistics = Statistics.empty()
        override fun getVersion() = "0"
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State = synchronized(this) {
            calls += "${tunnel.name} $state"
            if (state == Tunnel.State.UP) running += tunnel.name else running -= tunnel.name
            tunnel.onStateChange(state)
            state
        }
        override fun getToolsVersion() = "0"
        override fun requestPrivileges() {}
        override fun systemLog(lines: Int) = ""
        override fun close() {}
    }
}
