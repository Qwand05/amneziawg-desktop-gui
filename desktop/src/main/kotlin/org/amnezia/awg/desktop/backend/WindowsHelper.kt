/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.backend

import org.amnezia.awg.desktop.util.FilePermissions
import org.amnezia.awg.desktop.util.Strings
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/*
 * One UAC prompt per session on Windows, the counterpart of the Linux
 * RootHelper: the first time a tunnel is switched, the app starts a copy of
 * itself as administrator (WindowsHelperMain) and hands it every privileged
 * request from then on; it exits when the app does.
 *
 * The helper listens on a random localhost port and answers only requests
 * carrying a random token, which it reads from a file in the user's profile
 * and then deletes. It does nothing but these, checking every argument:
 *  - install: the tunnel service for a .conf in the app's config folder
 *  - uninstall: a tunnel service, by tunnel name
 *  - dump: a running tunnel's statistics (awg show <name> dump)
 *  - log: the client's log (amneziawg.exe /dumplog)
 *
 * Wire format: one request line "<token>\t<command>[\t<argument>]", answered
 * with "<exit code>\n" and the command's output, then the connection closes.
 */
internal object WindowsHelperProtocol {
    const val INSTALL = "install"
    const val UNINSTALL = "uninstall"
    const val DUMP = "dump"
    const val LOG = "log"
    const val PING = "ping"
    const val QUIT = "quit"

    /** Exit code for a refused request. */
    const val REFUSED = 125

    val TUNNEL_NAME = Regex("[a-zA-Z0-9_=+.-]{1,32}")
}

/** The app's side of the helper. */
internal class WindowsHelper private constructor(private val port: Int, private val pid: Long, private val token: String) : AutoCloseable {
    class Result(val exitCode: Int, val output: String)

    /** Whether the helper process is still there (an unelevated process may check an elevated one). */
    val isAlive: Boolean get() = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    fun exec(command: String, argument: String? = null, timeoutSeconds: Long = 120): Result {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000)
            socket.soTimeout = TimeUnit.SECONDS.toMillis(timeoutSeconds).toInt()
            val request = listOfNotNull(token, command, argument).joinToString("\t") + "\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            val response = String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            val code = response.substringBefore('\n').trim().toIntOrNull() ?: throw IOException("The elevated helper did not answer")
            return Result(code, response.substringAfter('\n', ""))
        }
    }

    override fun close() {
        runCatching { exec(WindowsHelperProtocol.QUIT, timeoutSeconds = 5) }
    }

    companion object {
        private const val START_TIMEOUT_SECONDS = 60L

        /**
         * Starts the helper as administrator, which shows the UAC prompt, and
         * waits until it listens. Throws with [Strings.AUTH_FAILED] when the
         * prompt is declined.
         */
        fun start(stateDir: File, configDir: File, client: File, awg: File?): WindowsHelper {
            stateDir.mkdirs()
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val tokenFile = File(stateDir, "helper.token")
            val portFile = File(stateDir, "helper.port")
            tokenFile.delete()
            portFile.delete()
            Files.createFile(tokenFile.toPath(), *FilePermissions.ownerOnly)
            tokenFile.writeText(token)

            val arguments = listOfNotNull(
                "-cp", classPath(), WindowsHelperMain::class.java.name,
                tokenFile.path, portFile.path, ProcessHandle.current().pid().toString(),
                configDir.path, client.path, awg?.path,
            )
            val launcher = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
                WindowsCommands.elevatedCommand(javaw(), arguments, wait = false),
            ).redirectErrorStream(true).start()
            launcher.inputStream.readAllBytes()
            launcher.waitFor(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            when (launcher.exitValue()) {
                0 -> {}
                WindowsCommands.EXIT_CANCELLED -> { tokenFile.delete(); throw IllegalStateException(Strings.AUTH_FAILED) }
                else -> { tokenFile.delete(); throw IOException("Could not start the elevated helper (exit code ${launcher.exitValue()})") }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                connect(portFile, token)?.let { return it }
                Thread.sleep(200)
            }
            tokenFile.delete()
            throw IOException("The elevated helper did not start")
        }

        /** The helper that wrote [portFile] ("<port> <pid>"), or null while it has not. */
        internal fun connect(portFile: File, token: String): WindowsHelper? {
            val parts = runCatching { portFile.readText().trim().split(' ') }.getOrNull() ?: return null
            val port = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val pid = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return WindowsHelper(port, pid, token)
        }

        /** javaw.exe next to this JVM's executable, so the helper has no console window. */
        private fun javaw(): String {
            val java = File(ProcessHandle.current().info().command().orElse("javaw.exe"))
            return File(java.parentFile, "javaw.exe").takeIf { it.isFile }?.path ?: java.path
        }

        /** This app's class path, shortened to "<folder>\*" when every jar is in one folder (as in lib\). */
        private fun classPath(): String {
            val entries = System.getProperty("java.class.path").split(File.pathSeparatorChar).filter { it.isNotEmpty() }
            val folders = entries.map { File(it).absoluteFile.parentFile }.distinct()
            return if (folders.size == 1 && entries.all { it.endsWith(".jar") }) File(folders.single(), "*").path
            else entries.joinToString(File.pathSeparator)
        }
    }
}

/**
 * The elevated helper process. Arguments: token file, port file, the app's
 * process id, config folder, amneziawg.exe and, optionally, awg.exe.
 */
object WindowsHelperMain {
    internal class Setup(
        val token: String,
        val portFile: File,
        val parentPid: Long,
        val configDir: File,
        val client: File,
        val awg: File?,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 5) return
        val tokenFile = File(args[0])
        val token = tokenFile.readText().trim()
        tokenFile.delete()
        serve(Setup(token, File(args[1]), args[2].toLong(), File(args[3]), File(args[4]), args.getOrNull(5)?.let(::File)))
    }

    /** Serves requests until told to quit or the app is gone. */
    internal fun serve(setup: Setup) {
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1000
            setup.portFile.writeText("${server.localPort} ${ProcessHandle.current().pid()}")
            try {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        if (!ProcessHandle.of(setup.parentPid).map { it.isAlive }.orElse(false)) return
                        continue
                    }
                    val quit = socket.use { answer(it, setup) }
                    if (quit) return
                }
            } finally {
                setup.portFile.delete()
            }
        }
    }

    /** Answers one connection; true when it asked the helper to quit. */
    private fun answer(socket: Socket, setup: Setup): Boolean {
        socket.soTimeout = 5000
        val line = try {
            socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
        } catch (_: IOException) {
            null
        } ?: return false
        val parts = line.split('\t')
        // Without the right token: no answer at all.
        if (parts.size < 2 || parts[0] != setup.token) return false
        val (code, output) = handle(parts[1], parts.getOrNull(2), setup)
        socket.getOutputStream().write("$code\n$output".toByteArray(StandardCharsets.UTF_8))
        return parts[1] == WindowsHelperProtocol.QUIT
    }

    /** Runs one checked request; anything else is refused. */
    internal fun handle(command: String, argument: String?, setup: Setup): Pair<Int, String> {
        fun refuse(why: String) = WindowsHelperProtocol.REFUSED to "Refused: $why"
        fun tunnelName(): String? = argument?.takeIf { WindowsHelperProtocol.TUNNEL_NAME.matches(it) }
        return when (command) {
            WindowsHelperProtocol.PING, WindowsHelperProtocol.QUIT -> 0 to ""
            WindowsHelperProtocol.INSTALL -> {
                val file = argument?.let { File(it).canonicalFile } ?: return refuse("no file")
                val inStore = file.parentFile == setup.configDir.canonicalFile
                val name = file.name.removeSuffix(".conf")
                if (!inStore || !file.name.endsWith(".conf") || !WindowsHelperProtocol.TUNNEL_NAME.matches(name) || !file.isFile)
                    return refuse("not a tunnel configuration of this app: $argument")
                run(setup.client.path, "/installtunnelservice", file.path)
            }
            WindowsHelperProtocol.UNINSTALL -> run(setup.client.path, "/uninstalltunnelservice", tunnelName() ?: return refuse("bad tunnel name"))
            WindowsHelperProtocol.DUMP -> {
                val awg = setup.awg ?: return refuse("awg.exe not found")
                run(awg.path, "show", tunnelName() ?: return refuse("bad tunnel name"), "dump")
            }
            WindowsHelperProtocol.LOG -> run(setup.client.path, "/dumplog")
            else -> refuse("unknown command $command")
        }
    }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.use { String(it.readNBytes(4 shl 20), StandardCharsets.UTF_8) }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return 1 to "${File(command.first()).name} timed out"
        }
        return process.exitValue() to output
    }
}
