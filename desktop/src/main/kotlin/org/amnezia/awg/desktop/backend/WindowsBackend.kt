/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.FilePermissions
import org.amnezia.awg.desktop.util.Strings
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Backend driving the official AmneziaWG for Windows client
 * (amneziawg-windows-client), the Windows counterpart of [AwgQuickBackend].
 *
 * Each running tunnel is a Windows service created by
 * `amneziawg.exe /installtunnelservice <file.conf>` and removed by
 * `amneziawg.exe /uninstalltunnelservice <name>`; both need administrator
 * rights. When this app runs elevated they are run directly, otherwise each
 * one asks for approval through UAC. Running tunnels are recognised by the
 * display name the client gives their services, "AmneziaWG Tunnel: <name>",
 * which `sc.exe` lists without administrator rights.
 *
 * Statistics (`awg.exe show <name> dump`) and the client's log
 * (`amneziawg.exe /dumplog`) need administrator rights as well, so they are
 * only available when the app itself runs elevated.
 *
 * Tunnel services start automatically with Windows, so a tunnel left up
 * comes back after a reboot even without this app running.
 */
class WindowsBackend(private val configDir: File) : Backend {

    private val client: File? = locate("amneziawg.exe")
    private val awg: File? = locate("awg.exe")

    /** Whether this process runs with administrator rights (High integrity level). */
    private val elevated: Boolean by lazy {
        try {
            WindowsCommands.isElevated(run("whoami", "/groups"))
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not determine the integrity level: $e")
            false
        }
    }

    @Volatile
    override var multipleTunnels: Boolean = true

    override val toolsAvailable: Boolean get() = client != null

    override val toolsMissingMessage: String get() = Strings.WINDOWS_CLIENT_MISSING

    override val typeName: String get() = Strings.TYPE_NAME_WINDOWS_CLIENT

    override val hasPrivileges: Boolean get() = elevated && awg != null

    override val privilegeDescription: String
        get() = if (elevated) Strings.PRIVILEGES_WINDOWS_ELEVATED else Strings.PRIVILEGES_WINDOWS_UAC

    /** A running process cannot elevate itself; the user has to start the app as administrator. */
    override val canRequestPrivileges: Boolean get() = false

    override val runningTunnelNames: Set<String>
        get() = if (client == null) emptySet()
        else WindowsCommands.parseRunningTunnels(run(*WindowsCommands.QUERY_ACTIVE_SERVICES))

    override fun getState(tunnel: Tunnel): Tunnel.State = Tunnel.State.of(runningTunnelNames.contains(tunnel.name))

    override fun getLastHandshake(tunnel: Tunnel): Long {
        if (getState(tunnel) != Tunnel.State.UP) return -3
        if (!hasPrivileges) return -2
        val statistics = getStatistics(tunnel)
        return statistics.peers().maxOfOrNull { statistics.peer(it)!!.latestHandshakeEpochMillis / 1000 } ?: 0
    }

    override fun getStatistics(tunnel: Tunnel): Statistics {
        val awg = awg
        if (!hasPrivileges || awg == null) return Statistics.empty()
        return try {
            Statistics.of(AwgShowParser.parseDump(run(awg.path, "show", tunnel.name, "dump")))
        } catch (_: Exception) {
            Statistics.empty()
        }
    }

    /** The client's file version, e.g. "1.0.3". */
    override fun getVersion(): String {
        val client = client ?: throw BackendException(BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME)
        val version = run(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-Item -LiteralPath ${WindowsCommands.psQuote(client.path)}).VersionInfo.ProductVersion",
        ).trim()
        return version.ifEmpty { throw BackendException(BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME) }
    }

    override fun getToolsVersion(): String {
        val awg = awg ?: return Strings.WINDOWS_CLIENT_MISSING
        return run(awg.path, "--version").trim()
    }

    override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State {
        val client = client ?: throw IllegalStateException(Strings.WINDOWS_CLIENT_MISSING)
        var target = state
        val originalState = getState(tunnel)
        if (target == Tunnel.State.TOGGLE)
            target = if (originalState == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
        if (target == Tunnel.State.UP && originalState == Tunnel.State.UP && config == null)
            return originalState
        if (target == Tunnel.State.DOWN && originalState == Tunnel.State.DOWN)
            return originalState

        // One tunnel at a time unless "multiple tunnels" is on, like on Linux.
        // Only tunnels of this app (with a config here) are taken down.
        if (target == Tunnel.State.UP && !multipleTunnels && originalState == Tunnel.State.DOWN) {
            for (name in runningTunnelNames) {
                if (name == tunnel.name || !configFile(name).isFile) continue
                try {
                    AppLog.i(TAG, "Taking $name down: only one tunnel may be up at a time")
                    down(client, name)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Could not take $name down", e)
                }
            }
        }

        if (target == Tunnel.State.UP) {
            if (config == null) throw BackendException(BackendException.Reason.TUNNEL_MISSING_CONFIG)
            // A changed config is applied by reinstalling the service.
            if (originalState == Tunnel.State.UP) down(client, tunnel.name)
            val file = writeConfig(tunnel.name, config)
            AppLog.i(TAG, "Bringing ${tunnel.name} up")
            runAsAdministrator(client, "/installtunnelservice", file.absolutePath)
            if (!waitFor(tunnel.name, up = true))
                throw IllegalStateException(Strings.windowsTunnelNotStarted(tunnel.name))
            AppLog.i(TAG, "${tunnel.name} is up")
        } else {
            down(client, tunnel.name)
        }
        tunnel.onStateChange(target)
        return target
    }

    private fun down(client: File, name: String) {
        AppLog.i(TAG, "Taking $name down")
        runAsAdministrator(client, "/uninstalltunnelservice", name)
        if (!waitFor(name, up = false)) AppLog.w(TAG, "$name still listed as running after uninstalling its service")
        else AppLog.i(TAG, "$name is down")
    }

    /** Waits until the tunnel's service is (or is no longer) active. */
    private fun waitFor(name: String, up: Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SERVICE_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if ((name in runningTunnelNames) == up) return true
            Thread.sleep(300)
        }
        return false
    }

    /**
     * Runs the client with administrator rights: directly when this app is
     * elevated, otherwise through PowerShell's `Start-Process -Verb RunAs`,
     * which shows the UAC prompt. The client reports its own errors in a
     * message box; here only the exit code is seen.
     */
    private fun runAsAdministrator(client: File, vararg args: String) {
        val command = if (elevated) listOf(client.path, *args)
        else listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", WindowsCommands.elevatedCommand(client.path, args.toList()))
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(consoleCharset).use { it.readText() }.trim()
        if (!process.waitFor(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("amneziawg.exe ${args.first()} timed out")
        }
        when (val code = process.exitValue()) {
            0 -> {}
            WindowsCommands.EXIT_CANCELLED -> throw IllegalStateException(Strings.AUTH_FAILED)
            else -> {
                AppLog.e(TAG, "amneziawg.exe ${args.joinToString(" ")} exited with $code${if (output.isEmpty()) "" else "\n$output"}")
                throw IllegalStateException(Strings.windowsClientFailed(code.toString()))
            }
        }
    }

    /** Same as on Linux: the service reads the config from the store at start. */
    private fun writeConfig(name: String, config: Config): File {
        val file = configFile(name)
        configDir.mkdirs()
        val tmp = Files.createTempFile(configDir.toPath(), ".$name", ".tmp", *FilePermissions.ownerOnly)
        Files.write(tmp, config.toAwgQuickString().toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    private fun configFile(name: String): File = File(configDir, "$name.conf")

    /** The client's tunnel service log; it lives in the client's protected data folder. */
    override fun systemLog(lines: Int): String {
        val client = client ?: return ""
        if (!elevated) return ""
        return run(client.path, "/dumplog").lines().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
    }

    override fun requestPrivileges() {
        throw IllegalStateException(Strings.STATISTICS_NEED_ADMIN_WINDOWS)
    }

    override fun close() {}

    companion object {
        private const val TAG = "Backend"

        /** Installing a service and bringing the adapter up normally takes a few seconds. */
        private const val SERVICE_TIMEOUT_SECONDS = 60L

        /** Console programs (sc, whoami, awg) write in the OEM code page. */
        private val consoleCharset: Charset by lazy {
            runCatching { Charset.forName(System.getProperty("sun.stdout.encoding") ?: System.getProperty("native.encoding")) }
                .getOrDefault(Charset.defaultCharset())
        }

        /**
         * Where the client is looked for: an `amneziawg\` folder next to this
         * app (a client built with its build.bat), the default install folder,
         * then the PATH.
         */
        private fun locate(exe: String): File? {
            val appDir = runCatching {
                File(WindowsBackend::class.java.protectionDomain.codeSource.location.toURI()).parentFile?.parentFile
            }.getOrNull()
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val candidates = listOfNotNull(
                appDir?.let { File(it, "amneziawg\\$exe") },
                File(programFiles, "AmneziaWG\\$exe"),
            ) + System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, exe) }
            return candidates.firstOrNull { it.isFile }
        }

        private fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(consoleCharset).use { it.readText() }
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

/** The text handling of [WindowsBackend], kept apart so it can be tested on Linux. */
internal object WindowsCommands {
    /** Display name the client gives tunnel services (manager/install.go). */
    const val TUNNEL_DISPLAY_NAME_PREFIX = "AmneziaWG Tunnel: "

    /** ERROR_CANCELLED: the UAC prompt was declined. */
    const val EXIT_CANCELLED = 1223

    /** Active (running or pending) services; a large buffer so sc lists them all in one go. */
    val QUERY_ACTIVE_SERVICES = arrayOf("sc.exe", "query", "type=", "service", "state=", "active", "bufsize=", "262144")

    /** Tunnel names from `sc query` output, by the services' display names. */
    fun parseRunningTunnels(scOutput: String): Set<String> =
        scOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("DISPLAY_NAME") }
            .map { it.substringAfter(':').trim() }
            .filter { it.startsWith(TUNNEL_DISPLAY_NAME_PREFIX) }
            .map { it.removePrefix(TUNNEL_DISPLAY_NAME_PREFIX).trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** `whoami /groups` lists the integrity level by SID, which is not translated: High or System. */
    fun isElevated(whoamiGroups: String): Boolean =
        "S-1-16-12288" in whoamiGroups || "S-1-16-16384" in whoamiGroups

    /** A PowerShell single-quoted string literal. */
    fun psQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    /** One Windows command line argument, quoted when needed (CommandLineToArgvW rules). */
    fun winQuote(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return arg
        val out = StringBuilder("\"")
        var backslashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> backslashes++
                '"' -> { out.append("\\".repeat(backslashes * 2 + 1)).append('"'); backslashes = 0 }
                else -> { out.append("\\".repeat(backslashes)).append(c); backslashes = 0 }
            }
        }
        out.append("\\".repeat(backslashes * 2)).append('"')
        return out.toString()
    }

    /**
     * PowerShell that starts [exe] elevated with [args], waits and exits
     * with its exit code; a declined UAC prompt exits with [EXIT_CANCELLED].
     */
    fun elevatedCommand(exe: String, args: List<String>): String {
        val argumentList = psQuote(args.joinToString(" ") { winQuote(it) })
        return "try { " +
            "\$p = Start-Process -FilePath ${psQuote(exe)} -ArgumentList $argumentList -Verb RunAs -Wait -PassThru -ErrorAction Stop; " +
            "exit \$p.ExitCode " +
            "} catch { [Console]::Error.WriteLine(\$_.Exception.Message); exit $EXIT_CANCELLED }"
    }
}
