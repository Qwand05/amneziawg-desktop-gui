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
}

/**
 * Keeps the application single-instance: with "close to tray", launching it
 * again from the menu must show the hidden window rather than start a second
 * copy with a second tray icon.
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
         * Claim the instance. Returns null after asking an already running
         * instance to show itself (the caller should then exit). Without a
         * session bus the check is skipped and a dummy instance is returned.
         */
        fun acquire(onShow: () -> Unit): Result {
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
                })
                Result.Acquired(SingleInstance(connection))
            } catch (_: Exception) {
                try {
                    connection.getRemoteObject(BUS_NAME, PATH, DesktopAppInterface::class.java, false).Show()
                } catch (_: Exception) {
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
