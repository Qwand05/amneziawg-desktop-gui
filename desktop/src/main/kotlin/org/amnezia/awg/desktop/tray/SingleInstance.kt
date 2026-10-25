/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("FunctionName")

package org.amnezia.awg.desktop.tray

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.amnezia.awg.desktop.util.AppLog
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
 */
class SingleInstance private constructor(private val connection: DBusConnection) : AutoCloseable {
    override fun close() {
        try {
            connection.releaseBusName(BUS_NAME)
        } catch (_: Exception) {
        }
        connection.close()
    }

    companion object {
        private const val BUS_NAME = "org.amnezia.awg.Desktop"
        private const val PATH = "/org/amnezia/awg/Desktop"

        /**
         * Claim the instance. When another instance is already running,
         * [forward] is given its remote interface (by default to show its
         * window) and [Result.AlreadyRunning] is returned, so the caller
         * should exit. Without a session bus the check is skipped.
         */
        fun acquire(
            onShow: () -> Unit,
            onToggleTunnel: (String) -> Unit,
            forward: (DesktopAppInterface) -> Unit = { it.Show() },
        ): Result {
            val connection = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build()
            } catch (_: Exception) {
                return Result.NoBus
            } catch (e: LinkageError) {
                // A JDK module missing from a trimmed runtime must not take the app down.
                AppLog.w("SingleInstance", "Single-instance check unavailable: $e")
                return Result.NoBus
            }
            return try {
                connection.requestBusName(BUS_NAME)
                connection.exportObject(PATH, object : DesktopAppInterface {
                    override fun getObjectPath() = PATH
                    override fun Show() = SwingUtilities.invokeLater(onShow)
                    override fun ToggleTunnel(name: String) = SwingUtilities.invokeLater { onToggleTunnel(name) }
                })
                Result.Acquired(SingleInstance(connection))
            } catch (_: Exception) {
                try {
                    forward(connection.getRemoteObject(BUS_NAME, PATH, DesktopAppInterface::class.java, false))
                } catch (e: Exception) {
                    AppLog.w("SingleInstance", "Could not reach the running instance: $e")
                }
                connection.close()
                Result.AlreadyRunning
            }
        }
    }

    sealed class Result {
        class Acquired(val instance: SingleInstance) : Result()
        data object AlreadyRunning : Result()
        data object NoBus : Result()
    }
}
