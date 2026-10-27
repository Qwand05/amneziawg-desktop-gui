/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.AppPaths
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import java.io.File
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Whether the computer has a network connection of its own, for
 * "Disconnect tunnels while offline".
 *
 * Asking whether the internet can be reached would be wrong while a full
 * tunnel is up: the answer then depends on the tunnel, and a broken tunnel
 * looks like a lost connection. Instead the system is asked whether an
 * interface other than a tunnel has a default route.
 */
interface Connectivity {
    /**
     * Whether an interface that is up, other than [tunnels], has a default
     * route; null when that cannot be told.
     */
    fun isOnline(tunnels: Set<String>): Boolean?

    fun close() {}

    companion object {
        fun forThisPlatform(): Connectivity = if (AppPaths.isWindows) WindowsConnectivity() else LinuxConnectivity()
    }
}

/**
 * NetworkManager's overall state over the system D-Bus when it runs (it does
 * not manage the app's interfaces, so they do not count), else the kernel's
 * default routes from `ip route`.
 */
class LinuxConnectivity : Connectivity {
    private var bus: DBusConnection? = null

    /** NetworkManager is asked again this long after it could not be reached. */
    private var networkManagerRetryAt = 0L

    /** The source of the last answer, logged when it changes. */
    private var source: String? = null

    override fun isOnline(tunnels: Set<String>): Boolean? {
        networkManagerState()?.let { state ->
            NetworkStates.networkManagerOnline(state)?.let {
                using("NetworkManager")
                return it
            }
        }
        return try {
            val ip = IP
            val routes = runCommand(ip, "-o", "-4", "route", "show", "default") + "\n" +
                runCommand(ip, "-o", "-6", "route", "show", "default")
            using("ip route")
            NetworkStates.hasDefaultRoute(routes, tunnels)
        } catch (e: Exception) {
            if (source != null) AppLog.w(TAG, "Could not read the default routes: $e")
            using(null)
            null
        }
    }

    private fun using(value: String?) {
        if (value != source && value != null) AppLog.i(TAG, "Network connection state from $value")
        source = value
    }

    /** NetworkManager's `State` (NMState), or null without NetworkManager. */
    private fun networkManagerState(): Long? {
        if (System.currentTimeMillis() < networkManagerRetryAt) return null
        return try {
            val connection = bus ?: DBusConnectionBuilder.forSystemBus().withShared(false).build().also { bus = it }
            val properties = connection.getRemoteObject(NM_BUS_NAME, NM_PATH, Properties::class.java)
            properties.Get<Number>(NM_BUS_NAME, "State").toLong()
        } catch (e: Exception) {
            networkManagerUnavailable(e)
        } catch (e: LinkageError) {
            networkManagerUnavailable(e)
        }
    }

    private fun networkManagerUnavailable(e: Throwable): Long? {
        if (source == "NetworkManager" || source == null) AppLog.i(TAG, "NetworkManager unavailable: $e")
        closeBus()
        networkManagerRetryAt = System.currentTimeMillis() + RETRY_MILLIS
        return null
    }

    private fun closeBus() {
        try {
            bus?.close()
        } catch (_: Exception) {
        }
        bus = null
    }

    override fun close() = closeBus()

    private companion object {
        const val TAG = "Connectivity"
        const val NM_BUS_NAME = "org.freedesktop.NetworkManager"
        const val NM_PATH = "/org/freedesktop/NetworkManager"
        val RETRY_MILLIS = TimeUnit.MINUTES.toMillis(1)

        /** `ip` is in /usr/sbin on some distributions, which is not always on a user's PATH. */
        val IP: String by lazy {
            listOf("/usr/bin/ip", "/bin/ip", "/usr/sbin/ip", "/sbin/ip").firstOrNull { File(it).canExecute() } ?: "ip"
        }

        fun runCommand(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("${command.first()} timed out")
            }
            if (process.exitValue() != 0) throw IllegalStateException(output.trim().ifEmpty { "${command.first()} failed" })
            return output
        }
    }
}

/**
 * The default routes of connected interfaces from PowerShell's NetTCPIP
 * cmdlets. PowerShell takes a moment to start, so it only runs again when
 * Java sees the interfaces change (or after a minute); interfaces as Java
 * sees them are the fallback when the cmdlets are missing.
 */
class WindowsConnectivity : Connectivity {
    private var lastFingerprint: String? = null
    private var lastQueryAt = 0L
    private var lastRoutes: List<String>? = null
    private var cmdletsMissing = false

    override fun isOnline(tunnels: Set<String>): Boolean? {
        val interfaces = try {
            NetworkInterface.networkInterfaces().toList()
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not list the network interfaces: $e")
            return null
        }
        if (cmdletsMissing) return javaFallback(interfaces, tunnels)
        val fingerprint = interfaces.joinToString("|") { nic ->
            "${nic.name}:${runCatching { nic.isUp }.getOrDefault(false)}:${nic.inetAddresses().toList().joinToString(",") { it.hostAddress }}"
        }
        val now = System.currentTimeMillis()
        if (fingerprint != lastFingerprint || now - lastQueryAt > REQUERY_MILLIS || lastRoutes == null) {
            val output = try {
                WindowsBackend.run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", NetworkStates.WINDOWS_ROUTES_SCRIPT)
            } catch (e: Exception) {
                AppLog.w(TAG, "Could not read the default routes: $e")
                ""
            }
            if (NetworkStates.WINDOWS_CMDLETS_MISSING in output) {
                AppLog.i(TAG, "Get-NetRoute is unavailable; judging the connection by the interfaces alone")
                cmdletsMissing = true
                return javaFallback(interfaces, tunnels)
            }
            lastRoutes = NetworkStates.parseWindowsRoutes(output)
            lastFingerprint = fingerprint
            lastQueryAt = now
        }
        val routes = lastRoutes ?: return null
        return routes.any { it !in tunnels }
    }

    /** Any interface that is up with a routable address, other than loopback and the tunnels' adapters. */
    private fun javaFallback(interfaces: List<NetworkInterface>, tunnels: Set<String>): Boolean = interfaces.any { nic ->
        runCatching { nic.isUp && !nic.isLoopback && !nic.isVirtual }.getOrDefault(false) &&
            nic.name !in tunnels && nic.displayName.orEmpty().let { name -> TUNNEL_ADAPTERS.none { name.contains(it, ignoreCase = true) } } &&
            nic.inetAddresses().toList().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    private companion object {
        const val TAG = "Connectivity"
        val REQUERY_MILLIS = TimeUnit.MINUTES.toMillis(1)

        /** Descriptions of the adapters tunnel clients create. */
        val TUNNEL_ADAPTERS = listOf("AmneziaWG", "WireGuard", "Wintun")
    }
}

/** The parsing behind [Connectivity], kept apart so it can be tested on any platform. */
internal object NetworkStates {
    /**
     * NetworkManager's NMState: CONNECTED_SITE (60) and CONNECTED_GLOBAL (70)
     * have a default route; SITE only means its own internet check failed,
     * which a broken full tunnel causes too. UNKNOWN (0) is no answer.
     */
    fun networkManagerOnline(state: Long): Boolean? = when (state) {
        0L -> null
        60L, 70L -> true
        else -> false
    }

    /**
     * Whether `ip -o route show default` output (IPv4 and IPv6) has a usable
     * default route through an interface other than loopback and [tunnels].
     * "unreachable default" and the like are not routes out; a route whose
     * link has no carrier is flagged "linkdown", a dead next hop "dead".
     * With -o the next hops of a multipath route share its line.
     */
    fun hasDefaultRoute(output: String, tunnels: Set<String>): Boolean =
        output.lineSequence().any { line ->
            val tokens = line.replace('\\', ' ').trim().split(Regex("\\s+"))
            if (tokens.firstOrNull() != "default") return@any false
            // The route itself, or each of its next hops.
            val hops = splitAt(tokens, "nexthop").let { if (it.size > 1) it.drop(1) else it }
            hops.any { hop ->
                val dev = hop.zipWithNext().firstOrNull { it.first == "dev" }?.second
                dev != null && dev != "lo" && dev !in tunnels && "linkdown" !in hop && "dead" !in hop
            }
        }

    private fun splitAt(tokens: List<String>, separator: String): List<List<String>> {
        val parts = mutableListOf(mutableListOf<String>())
        for (token in tokens) if (token == separator) parts += mutableListOf<String>() else parts.last() += token
        return parts
    }

    const val WINDOWS_CMDLETS_MISSING = "cmdlets-missing"
    private const val WINDOWS_ROUTE_PREFIX = "route:"
    private const val WINDOWS_DONE = "done"

    /**
     * Interface aliases (the adapter names shown in Windows, the tunnel's
     * name for a tunnel adapter) with a default route in the active store,
     * of interfaces that are connected. Printed as "route:<alias>" lines and
     * a final "done", so that output cut short is not taken for no routes.
     * One line: arguments with line breaks do not survive every quoting.
     */
    val WINDOWS_ROUTES_SCRIPT = listOf(
        "if (-not (Get-Command Get-NetRoute -ErrorAction SilentlyContinue)) { '$WINDOWS_CMDLETS_MISSING'; exit 0 }",
        "\$up = @(Get-NetIPInterface -ConnectionState Connected -ErrorAction Stop | ForEach-Object { \$_.InterfaceIndex })",
        "Get-NetRoute -DestinationPrefix '0.0.0.0/0','::/0' -PolicyStore ActiveStore -ErrorAction SilentlyContinue | " +
            "Where-Object { \$up -contains \$_.InterfaceIndex } | ForEach-Object { '$WINDOWS_ROUTE_PREFIX' + \$_.InterfaceAlias }",
        "'$WINDOWS_DONE'",
    ).joinToString("; ")

    /** The aliases from [WINDOWS_ROUTES_SCRIPT]'s output; null when it did not finish. */
    fun parseWindowsRoutes(output: String): List<String>? {
        val lines = output.lines().map { it.trim() }
        if (WINDOWS_DONE !in lines) return null
        return lines.filter { it.startsWith(WINDOWS_ROUTE_PREFIX) }.map { it.removePrefix(WINDOWS_ROUTE_PREFIX) }.distinct()
    }
}
