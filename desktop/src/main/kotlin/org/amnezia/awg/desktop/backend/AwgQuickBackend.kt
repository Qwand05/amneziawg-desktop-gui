/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.Strings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Backend that uses the AmneziaWG kernel module together with `awg-quick` to
 * provide tunnels, the desktop counterpart of the Android `AwgQuickBackend`.
 *
 * Read-only queries that work unprivileged (`awg show interfaces`, the module
 * version) run directly. Everything else goes through [RootHelper], which is
 * started lazily the first time root is actually needed so that merely
 * opening the application never triggers a polkit prompt.
 */
class AwgQuickBackend(private val configDir: File) : Backend {

    @Volatile
    private var helperInstance: RootHelper? = null

    /** Whether more than one tunnel may be up at a time. */
    @Volatile
    var multipleTunnels: Boolean = true

    /** Whether the privileged helper is already running (no prompt needed). */
    override val hasPrivileges: Boolean
        get() = helperInstance?.isAlive == true

    /** Human readable description of how root is reached, for the settings UI. */
    val privilegeDescription: String
        get() = helperInstance?.takeIf { it.isAlive }?.description ?: Strings.PRIVILEGES_NOT_STARTED

    /** Start the privileged helper (prompting for authentication) if needed. */
    @Synchronized
    fun helper(): RootHelper {
        helperInstance?.let { if (it.isAlive) return it }
        return RootHelper.start().also { helperInstance = it }
    }

    override val runningTunnelNames: Set<String>
        get() = AwgShowParser.parseInterfaces(runUnprivileged("awg", "show", "interfaces"))

    override fun getState(tunnel: Tunnel): Tunnel.State =
        Tunnel.State.of(runningTunnelNames.contains(tunnel.name))

    override fun getLastHandshake(tunnel: Tunnel): Long {
        if (getState(tunnel) != Tunnel.State.UP) return -3
        if (!hasPrivileges || helperInstance?.isBusy == true) return -2
        val output = try {
            helper().exec("latest", tunnel.name)
        } catch (_: Exception) {
            return -2
        }
        for (line in output.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size >= 2) {
                return parts[1].toLongOrNull() ?: return -1
            }
        }
        return -1
    }

    /**
     * Statistics need root. They are only fetched once the helper is running,
     * so polling the detail screen never prompts by itself.
     */
    override fun getStatistics(tunnel: Tunnel): Statistics {
        // Skipped while a tunnel is being switched; the next poll catches up.
        if (!hasPrivileges || helperInstance?.isBusy == true || getState(tunnel) != Tunnel.State.UP)
            return Statistics.empty()
        val dump = try {
            helper().exec("dump", tunnel.name)
        } catch (_: Exception) {
            return Statistics.empty()
        }
        return Statistics.of(AwgShowParser.parseDump(dump))
    }

    override fun getVersion(): String {
        val sysfs = File("/sys/module/amneziawg/version")
        if (sysfs.canRead()) return sysfs.readText().trim()
        throw BackendException(BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME)
    }

    /** Version string of the installed amneziawg-tools. */
    fun getToolsVersion(): String = runUnprivileged("awg", "--version").trim()

    override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State {
        var target = state
        val originalState = getState(tunnel)
        if (target == Tunnel.State.TOGGLE)
            target = if (originalState == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        if (target == Tunnel.State.UP && originalState == Tunnel.State.UP && config == null)
            return originalState
        if (target == Tunnel.State.DOWN && originalState == Tunnel.State.DOWN)
            return originalState

        // Only allow a single tunnel at a time, like the Android backend does
        // when the "multiple tunnels" knob is off.
        if (target == Tunnel.State.UP && !multipleTunnels && originalState == Tunnel.State.DOWN) {
            for (name in runningTunnelNames) {
                if (name == tunnel.name || !configFile(name).isFile) continue
                try {
                    AppLog.i(TAG, "Taking $name down: only one tunnel may be up at a time")
                    down(name)
                } catch (e: Exception) {
                    // Keep going; the tunnel we care about still has to come up.
                    AppLog.w(TAG, "Could not take $name down", e)
                }
            }
        }

        if (target == Tunnel.State.UP) {
            if (config == null) throw BackendException(BackendException.Reason.TUNNEL_MISSING_CONFIG)
            // Applying a changed config to a running tunnel: restart it, which
            // is what the Android backend does as well.
            if (originalState == Tunnel.State.UP) down(tunnel.name)
            val file = writeConfig(tunnel.name, config)
            AppLog.i(TAG, "Bringing ${tunnel.name} up")
            try {
                val output = helper().exec("up", file.absolutePath, timeoutSeconds = UP_DOWN_TIMEOUT_SECONDS)
                AppLog.i(TAG, "${tunnel.name} is up\n$output")
            } catch (e: RootHelperException) {
                AppLog.e(TAG, "Bringing ${tunnel.name} up failed\n${e.message}")
                throw BackendException(BackendException.Reason.AWG_QUICK_CONFIG_ERROR_CODE, e.message ?: e.code)
            } catch (e: Exception) {
                AppLog.e(TAG, "Bringing ${tunnel.name} up failed", e)
                throw e
            }
        } else {
            down(tunnel.name)
        }
        tunnel.onStateChange(target)
        return target
    }

    private fun down(name: String) {
        val file = configFile(name)
        val target = if (file.isFile) file.absolutePath else name
        AppLog.i(TAG, "Taking $name down")
        try {
            val output = helper().exec("down", target, timeoutSeconds = UP_DOWN_TIMEOUT_SECONDS)
            AppLog.i(TAG, "$name is down\n$output")
        } catch (e: RootHelperException) {
            val message = e.message ?: ""
            // awg-quick already reports the interface as down when it was not
            // running; that is not an error from the user's point of view.
            if (message.contains("is not a WireGuard interface", ignoreCase = true) ||
                message.contains("is not configured", ignoreCase = true) ||
                message.contains("No such device", ignoreCase = true)
            ) {
                AppLog.i(TAG, "$name was already down")
                return
            }
            AppLog.e(TAG, "Taking $name down failed\n$message")
            throw BackendException(BackendException.Reason.AWG_QUICK_CONFIG_ERROR_CODE, message)
        } catch (e: Exception) {
            AppLog.e(TAG, "Taking $name down failed", e)
            throw e
        }
    }

    /**
     * Write [config] to the store so `awg-quick` can read it. The file is
     * created owner-only because it contains the private key, and awg-quick
     * warns about world accessible configurations otherwise.
     */
    private fun writeConfig(name: String, config: Config): File {
        val file = configFile(name)
        configDir.mkdirs()
        val tmp = Files.createTempFile(configDir.toPath(), ".$name", ".tmp", OWNER_ONLY)
        Files.write(tmp, config.toAwgQuickString().toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    private fun configFile(name: String): File = File(configDir, "$name.conf")

    /**
     * AmneziaWG related entries of the system journal: kernel module messages
     * and `awg-quick` output from systemd units (`awg-quick@<name>`), oldest
     * first. The app's own `awg-quick` runs are in [AppLog] instead.
     *
     * Read unprivileged first, which works for members of `adm` or
     * `systemd-journal`; otherwise through the helper if it is running.
     */
    fun systemJournal(lines: Int = 500): String {
        val queries = listOf(
            listOf("-k", "--grep", "amneziawg"),
            listOf("-t", "awg-quick", "-t", "awg"),
            listOf("-u", "awg-quick@*"),
        )
        val entries = sortedSetOf<String>()
        var denied = false
        for (query in queries) {
            val output = try {
                runUnprivileged("journalctl", "--no-pager", "-q", "-o", "short-iso", "-n", lines.toString(), *query.toTypedArray())
            } catch (e: Exception) {
                // journalctl exits non-zero when --grep finds nothing.
                e.message.orEmpty()
            }
            for (line in output.lines()) {
                when {
                    line.isBlank() || line.startsWith("-- ") -> {}
                    "insufficient permissions" in line || "not seeing messages from other users" in line -> denied = true
                    line.first().isDigit() -> entries += line
                }
            }
        }
        if (denied && entries.isEmpty() && hasPrivileges) return helper().exec("journal", lines.toString())
        return entries.toList().takeLast(lines).joinToString("\n")
    }

    fun close() {
        helperInstance?.close()
        helperInstance = null
    }

    companion object {
        private const val TAG = "Backend"

        /**
         * Split tunnels take well under a second (see awg-helper.sh), but
         * awg-quick itself can need minutes to parse a full-tunnel config
         * with thousands of extra AllowedIPs entries.
         */
        private const val UP_DOWN_TIMEOUT_SECONDS = 600L
        private val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        /** Whether the AmneziaWG kernel module is loaded on this host. */
        fun hasKernelSupport(): Boolean = File("/sys/module/amneziawg").exists()

        /** Whether `awg-quick` is on the PATH. */
        fun hasTools(): Boolean =
            System.getenv("PATH").orEmpty().split(':').any { File(it, "awg-quick").canExecute() }

        /** Run a command as the current user and return its standard output. */
        private fun runUnprivileged(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("${command.first()} timed out")
            }
            if (process.exitValue() != 0)
                throw IllegalStateException(output.trim().ifEmpty { "${command.first()} failed" })
            return output
        }
    }
}
