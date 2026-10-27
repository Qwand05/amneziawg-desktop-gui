/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.AppPaths
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
 * rights. When this app runs elevated they are run directly; otherwise the
 * first one starts a [WindowsHelper] as administrator (one UAC prompt per
 * session) that runs them from then on. Running tunnels are recognised by the
 * display name the client gives their services, "AmneziaWG Tunnel: <name>",
 * which `net start` (or, failing that, `sc query`) lists without
 * administrator rights.
 *
 * Statistics (`awg.exe show <name> dump`) and the client's log
 * (`amneziawg.exe /dumplog`) need administrator rights as well: they are
 * read directly when elevated, through the helper once it runs, and not at
 * all before (like on Linux, reading them never prompts by itself).
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

    @Volatile
    private var helperInstance: WindowsHelper? = null

    private val helperRunning: Boolean get() = helperInstance?.isAlive == true

    /** The elevated helper, started (with its UAC prompt) when there is none yet. */
    @Synchronized
    private fun helper(): WindowsHelper {
        helperInstance?.takeIf { it.isAlive }?.let { return it }
        val client = client ?: throw IllegalStateException(Strings.WINDOWS_CLIENT_MISSING)
        AppLog.i(TAG, "Starting the elevated helper (UAC)")
        return WindowsHelper.start(AppPaths.stateDir, configDir, client, awg).also {
            helperInstance = it
            AppLog.i(TAG, "Elevated helper running")
        }
    }

    override val hasPrivileges: Boolean get() = (elevated || helperRunning) && awg != null

    override val privilegeDescription: String
        get() = when {
            elevated -> Strings.PRIVILEGES_WINDOWS_ELEVATED
            helperRunning -> Strings.PRIVILEGES_WINDOWS_HELPER
            else -> Strings.PRIVILEGES_WINDOWS_UAC
        }

    /** The helper can be started from the app; when elevated, there is nothing to ask for. */
    override val canRequestPrivileges: Boolean get() = !elevated

    /** Which of the two queries works here; `net start` unless it has failed once. */
    @Volatile
    private var useServiceControl = false

    override val runningTunnelNames: Set<String>
        get() {
            if (client == null) return emptySet()
            if (!useServiceControl) {
                try {
                    return WindowsCommands.parseRunningTunnels(run(*WindowsCommands.LIST_STARTED_SERVICES))
                } catch (e: Exception) {
                    AppLog.w(TAG, "net start failed, using sc query from now on: ${e.message}")
                    useServiceControl = true
                }
            }
            return WindowsCommands.parseRunningTunnels(run(*WindowsCommands.QUERY_ACTIVE_SERVICES))
        }

    override fun getState(tunnel: Tunnel): Tunnel.State = Tunnel.State.of(runningTunnelNames.contains(tunnel.name))

    override fun getLastHandshake(tunnel: Tunnel): Long {
        if (getState(tunnel) != Tunnel.State.UP) return -3
        if (!hasPrivileges) return -2
        val statistics = getStatistics(tunnel)
        return statistics.peers().maxOfOrNull { statistics.peer(it)!!.latestHandshakeEpochMillis / 1000 } ?: 0
    }

    /** Never starts the helper by itself, so polling statistics cannot prompt. */
    override fun getStatistics(tunnel: Tunnel): Statistics {
        val awg = awg
        if (!hasPrivileges || awg == null) return Statistics.empty()
        return try {
            val dump = if (elevated) run(awg.path, "show", tunnel.name, "dump")
            else helperInstance!!.exec(WindowsHelperProtocol.DUMP, tunnel.name).takeIf { it.exitCode == 0 }?.output ?: return Statistics.empty()
            Statistics.of(AwgShowParser.parseDump(dump))
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
            runAsAdministrator(client, WindowsHelperProtocol.INSTALL, file.absolutePath)
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
        runAsAdministrator(client, WindowsHelperProtocol.UNINSTALL, name)
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
     * Installs ([WindowsHelperProtocol.INSTALL], a config file) or uninstalls
     * ([WindowsHelperProtocol.UNINSTALL], a tunnel name) a tunnel service with
     * administrator rights: directly when this app is elevated, otherwise
     * through the helper, started with a UAC prompt the first time. The
     * client reports its own errors in a message box; here only the exit
     * code is seen.
     */
    private fun runAsAdministrator(client: File, command: String, argument: String) {
        val flag = if (command == WindowsHelperProtocol.INSTALL) "/installtunnelservice" else "/uninstalltunnelservice"
        val (code, output) = if (elevated) {
            val process = ProcessBuilder(client.path, flag, argument).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(consoleCharset).use { it.readText() }.trim()
            if (!process.waitFor(SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("amneziawg.exe $flag timed out")
            }
            process.exitValue() to output
        } else {
            val result = helper().exec(command, argument)
            result.exitCode to result.output.trim()
        }
        if (code != 0) {
            AppLog.e(TAG, "amneziawg.exe $flag $argument exited with $code${if (output.isEmpty()) "" else "\n$output"}")
            throw IllegalStateException(
                if (code == WindowsHelperProtocol.REFUSED) output else Strings.windowsClientFailed(code.toString())
            )
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
        val log = when {
            elevated -> run(client.path, "/dumplog")
            helperRunning -> helperInstance!!.exec(WindowsHelperProtocol.LOG).takeIf { it.exitCode == 0 }?.output ?: return ""
            else -> return ""
        }
        return log.lines().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
    }

    /** "Show live statistics": starts the helper (one UAC prompt) unless the app is elevated. */
    override fun requestPrivileges() {
        if (!elevated) helper()
    }

    override fun close() {
        helperInstance?.close()
        helperInstance = null
    }

    companion object {
        private const val TAG = "Backend"

        /** Installing a service and bringing the adapter up normally takes a few seconds. */
        private const val SERVICE_TIMEOUT_SECONDS = 60L

        /**
         * Console programs (net, sc, whoami, awg) write in the console's OEM
         * code page (e.g. 866 on Russian Windows), not in the ANSI one Java
         * uses by default (1251), which turned their messages into garbage.
         * `chcp` reports it; its text is translated, the number is not.
         */
        internal val consoleCharset: Charset by lazy {
            try {
                val process = ProcessBuilder("cmd.exe", "/c", "chcp").redirectErrorStream(true).start()
                val output = process.inputStream.readBytes().toString(StandardCharsets.ISO_8859_1)
                process.waitFor(10, TimeUnit.SECONDS)
                WindowsCommands.charsetOfChcp(output)
            } catch (e: Exception) {
                AppLog.w(TAG, "Could not read the console code page: $e")
                null
            } ?: Charset.defaultCharset()
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

        internal fun run(vararg command: String): String {
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

    /** Display names of the running services, one per line (the header and footer are translated). */
    val LIST_STARTED_SERVICES = arrayOf("net.exe", "start")

    /** Active (running or pending) services; a large buffer so sc lists them all in one go. */
    val QUERY_ACTIVE_SERVICES = arrayOf("sc.exe", "query", "type=", "service", "state=", "active", "bufsize=", "65536")

    /**
     * Tunnel names from `net start` or `sc query` output: the text after
     * "AmneziaWG Tunnel: " wherever it appears, so that neither the
     * translated parts of the output nor sc's labels matter. Tunnel names
     * are ASCII, so a wrongly decoded code page cannot garble them.
     */
    fun parseRunningTunnels(output: String): Set<String> =
        output.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf(TUNNEL_DISPLAY_NAME_PREFIX)
                if (at < 0) null else line.substring(at + TUNNEL_DISPLAY_NAME_PREFIX.length).trim()
            }
            .filter { it.isNotEmpty() && !it.contains(' ') }
            .toSet()

    /** The charset of `chcp` output ("Active code page: 866"): the last number in it, as "cp<number>". */
    fun charsetOfChcp(output: String): Charset? {
        val number = Regex("[0-9]+").findAll(output).lastOrNull()?.value ?: return null
        val name = if (number == "65001") "UTF-8" else "cp$number"
        return runCatching { Charset.forName(name) }.getOrNull()
    }

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
    fun elevatedCommand(exe: String, args: List<String>, wait: Boolean = true): String {
        val argumentList = psQuote(args.joinToString(" ") { winQuote(it) })
        val start = "Start-Process -FilePath ${psQuote(exe)} -ArgumentList $argumentList -Verb RunAs"
        val run = if (wait) "\$p = $start -Wait -PassThru -ErrorAction Stop; exit \$p.ExitCode " else "$start -ErrorAction Stop; exit 0 "
        return "try { $run} catch { [Console]::Error.WriteLine(\$_.Exception.Message); exit $EXIT_CANCELLED }"
    }
}
