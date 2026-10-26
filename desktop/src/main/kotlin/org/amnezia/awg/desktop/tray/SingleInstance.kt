/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("FunctionName")

package org.amnezia.awg.desktop.tray

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.amnezia.awg.desktop.util.AppLog
import org.amnezia.awg.desktop.util.AppPaths
import org.amnezia.awg.desktop.util.FilePermissions
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.swing.SwingUtilities

@DBusInterfaceName("org.amnezia.awg.Desktop")
interface DesktopAppInterface : DBusInterface {
    /** Bring the running instance's window to the front. */
    fun Show()

    /** Connect the tunnel called [name] if it is down, disconnect it otherwise (desktop shortcuts). */
    fun ToggleTunnel(name: String)
}

/**
 * Keeps the application single-instance: with "close to tray", launching it
 * again from the menu must show the hidden window rather than start a second
 * copy with a second tray icon. A desktop shortcut's `--toggle` is forwarded
 * the same way.
 *
 * On Linux this goes over the D-Bus session bus. Where there is none
 * (Windows), [LocalInstance] does the same with a lock file and a localhost
 * socket.
 */
class SingleInstance private constructor(private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()

    companion object {
        private const val BUS_NAME = "org.amnezia.awg.Desktop"
        private const val PATH = "/org/amnezia/awg/Desktop"

        /**
         * Claim the instance. When another instance is already running, it is
         * asked to toggle [toggle] (or, without one, to show its window) and
         * [Result.AlreadyRunning] is returned, so the caller should exit.
         */
        fun acquire(toggle: String?, onShow: () -> Unit, onToggleTunnel: (String) -> Unit): Result =
            acquireOnBus(toggle, onShow, onToggleTunnel)
                ?: LocalInstance.acquire(toggle, onShow, onToggleTunnel)
                ?: Result.Unavailable

        /** Over D-Bus; null when there is no session bus. */
        private fun acquireOnBus(toggle: String?, onShow: () -> Unit, onToggleTunnel: (String) -> Unit): Result? {
            val connection = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build()
            } catch (_: Exception) {
                return null
            } catch (e: LinkageError) {
                // A JDK module missing from a trimmed runtime must not take the app down.
                AppLog.w("SingleInstance", "D-Bus single-instance check unavailable: $e")
                return null
            }
            return try {
                connection.requestBusName(BUS_NAME)
                connection.exportObject(PATH, object : DesktopAppInterface {
                    override fun getObjectPath() = PATH
                    override fun Show() = SwingUtilities.invokeLater(onShow)
                    override fun ToggleTunnel(name: String) = SwingUtilities.invokeLater { onToggleTunnel(name) }
                })
                AppLog.i("SingleInstance", "Single instance: D-Bus")
                Result.Acquired(SingleInstance {
                    try {
                        connection.releaseBusName(BUS_NAME)
                    } catch (_: Exception) {
                    }
                    connection.close()
                })
            } catch (_: Exception) {
                try {
                    val remote = connection.getRemoteObject(BUS_NAME, PATH, DesktopAppInterface::class.java, false)
                    if (toggle != null) remote.ToggleTunnel(toggle) else remote.Show()
                } catch (e: Exception) {
                    AppLog.w("SingleInstance", "Could not reach the running instance: $e")
                }
                connection.close()
                Result.AlreadyRunning
            }
        }

        internal fun wrap(onClose: () -> Unit) = SingleInstance(onClose)
    }

    sealed class Result {
        class Acquired(val instance: SingleInstance) : Result()
        data object AlreadyRunning : Result()

        /** Neither mechanism works; the app runs without the check. */
        data object Unavailable : Result()
    }
}

/**
 * Single instance without D-Bus: the running instance holds a lock on
 * `instance.lock` in the state directory and listens on a random localhost
 * port, written with a random token to `instance.port` (in the user's
 * profile, so only they can read it). A later launch finds the lock taken,
 * sends "<token> show" or "<token> toggle <name>" to that port and exits.
 */
internal object LocalInstance {
    private const val TAG = "SingleInstance"

    fun acquire(
        toggle: String?,
        onShow: () -> Unit,
        onToggleTunnel: (String) -> Unit,
        dir: File = AppPaths.stateDir,
    ): SingleInstance.Result? {
        return try {
            dir.mkdirs()
            val lockFile = File(dir, "instance.lock")
            val portFile = File(dir, "instance.port")
            val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                forward(portFile, if (toggle != null) "toggle $toggle" else "show")
                return SingleInstance.Result.AlreadyRunning
            }
            val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
            val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            portFile.delete()
            Files.createFile(portFile.toPath(), *FilePermissions.ownerOnly)
            portFile.writeText("${server.localPort} $token\n")
            Thread({ serve(server, token, onShow, onToggleTunnel) }, "single-instance").apply { isDaemon = true }.start()
            AppLog.i(TAG, "Single instance: local socket")
            SingleInstance.Result.Acquired(SingleInstance.wrap {
                server.close()
                portFile.delete()
                lock.release()
                channel.close()
            })
        } catch (e: Exception) {
            AppLog.w(TAG, "Local single-instance check unavailable: $e")
            null
        }
    }

    private fun serve(server: ServerSocket, token: String, onShow: () -> Unit, onToggleTunnel: (String) -> Unit) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            try {
                socket.use {
                    it.soTimeout = 2000
                    val line = it.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine() ?: return@use
                    when (val request = parse(line, token)) {
                        is Request.Show -> SwingUtilities.invokeLater(onShow)
                        is Request.Toggle -> SwingUtilities.invokeLater { onToggleTunnel(request.name) }
                        null -> AppLog.w(TAG, "Ignored a request without the right token")
                    }
                }
            } catch (_: Exception) {
                // A client that went away; keep serving.
            }
        }
    }

    /**
     * Sends [request] to the running instance. Its port file may not be
     * written yet when both start at once, hence the retries.
     */
    private fun forward(portFile: File, request: String) {
        repeat(20) {
            try {
                val (port, token) = portFile.readText().trim().split(' ', limit = 2)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), 2000)
                    socket.getOutputStream().write("$token $request\n".toByteArray(StandardCharsets.UTF_8))
                }
                return
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        AppLog.w(TAG, "Could not reach the running instance")
    }

    sealed class Request {
        data object Show : Request()
        data class Toggle(val name: String) : Request()
    }

    /** "<token> show" or "<token> toggle <name>"; null for anything else or a wrong token. */
    fun parse(line: String, token: String): Request? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != token) return null
        return when {
            parts[1] == "show" && parts.size == 2 -> Request.Show
            parts[1] == "toggle" && parts.size == 3 && parts[2].isNotEmpty() -> Request.Toggle(parts[2])
            else -> null
        }
    }
}
