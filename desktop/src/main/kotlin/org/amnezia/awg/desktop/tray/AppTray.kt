/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.tray

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * An entry of the tray icon's context menu. Equality ignores the click
 * handlers so that the shell is only told about real layout changes.
 */
sealed class TrayMenuEntry {
    class Action(val label: String, val enabled: Boolean = true, val onClick: () -> Unit) : TrayMenuEntry() {
        override fun equals(other: Any?) = other is Action && other.label == label && other.enabled == enabled
        override fun hashCode() = label.hashCode() * 31 + enabled.hashCode()
    }

    class Toggle(val label: String, val checked: Boolean, val enabled: Boolean = true, val onClick: () -> Unit) : TrayMenuEntry() {
        override fun equals(other: Any?) =
            other is Toggle && other.label == label && other.checked == checked && other.enabled == enabled
        override fun hashCode() = (label.hashCode() * 31 + checked.hashCode()) * 31 + enabled.hashCode()
    }

    data object Separator : TrayMenuEntry()
}

/** What the tray shows; [active] picks the colored or the greyed out icon. */
data class TrayState(val tooltip: String, val active: Boolean, val menu: List<TrayMenuEntry>)

/**
 * Native tray icon implementing the StatusNotifierItem specification, which
 * is what KDE Plasma (X11 and Wayland) uses. The legacy XEmbed tray that AWT
 * uses only works on Plasma through `xembedsniproxy`, which mispositions menus
 * on Wayland, so it is only used as a fallback (see `Main.kt`).
 *
 * All D-Bus callbacks arrive on dbus-java worker threads and are forwarded to
 * the Swing/Compose thread.
 */
class StatusNotifierTray private constructor(
    private val connection: DBusConnection,
    private val busName: String,
    private val onActivate: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var state = TrayState("", false, emptyList())
    private val revision = AtomicLong(1)
    private val activeIcon = loadIcon(greyscale = false)
    private val inactiveIcon = loadIcon(greyscale = true)

    private val item = object : StatusNotifierItemInterface, Properties {
        override fun getObjectPath() = ITEM_PATH

        private fun properties(): Map<String, Variant<*>> = mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("amnezia-awg"),
            "Title" to Variant("AmneziaWG"),
            "Status" to Variant("Active"),
            "WindowId" to Variant(0),
            "IconName" to Variant(""),
            "IconPixmap" to Variant(if (state.active) activeIcon else inactiveIcon, "a(iiay)"),
            "AttentionIconName" to Variant(""),
            "ToolTip" to Variant(ToolTip("", emptyList(), "AmneziaWG", state.tooltip), "(sa(iiay)ss)"),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(MENU_PATH)),
        )

        override fun <A : Any?> Get(iface: String, name: String): A = property(properties(), name)
        override fun <A : Any?> Set(iface: String, name: String, value: A) = throw readOnly(name)
        override fun GetAll(iface: String) = properties()

        override fun ContextMenu(x: Int, y: Int) {}
        override fun Activate(x: Int, y: Int) = SwingUtilities.invokeLater(onActivate)
        override fun SecondaryActivate(x: Int, y: Int) = SwingUtilities.invokeLater(onActivate)
        override fun Scroll(delta: Int, orientation: String) {}
    }

    private val menu = object : DBusMenuInterface, Properties {
        override fun getObjectPath() = MENU_PATH

        private val properties: Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )

        override fun <A : Any?> Get(iface: String, name: String): A = property(properties, name)
        override fun <A : Any?> Set(iface: String, name: String, value: A) = throw readOnly(name)
        override fun GetAll(iface: String) = properties

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutResult<UInt32, MenuLayout> {
            val entries = state.menu
            val layout = if (parentId == ROOT_ID) {
                val children = if (recursionDepth == 0) emptyList()
                else entries.indices.map { Variant(itemLayout(it, entries[it]), LAYOUT_SIGNATURE) }
                MenuLayout(ROOT_ID, mapOf("children-display" to Variant("submenu")), children)
            } else {
                itemLayout(parentId - FIRST_ITEM_ID, entries.getOrNull(parentId - FIRST_ITEM_ID) ?: TrayMenuEntry.Separator)
            }
            return LayoutResult(UInt32(revision.get()), layout)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties> {
            val entries = state.menu
            return ids.mapNotNull { id ->
                entries.getOrNull(id - FIRST_ITEM_ID)?.let { MenuItemProperties(id, properties(it)) }
            }
        }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            state.menu.getOrNull(id - FIRST_ITEM_ID)?.let { properties(it)[name] } ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (val entry = state.menu.getOrNull(id - FIRST_ITEM_ID)) {
                is TrayMenuEntry.Action -> if (entry.enabled) SwingUtilities.invokeLater(entry.onClick)
                is TrayMenuEntry.Toggle -> if (entry.enabled) SwingUtilities.invokeLater(entry.onClick)
                else -> {}
            }
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int) = false
        override fun AboutToShowGroup(ids: List<Int>) = AboutToShowGroupResult(emptyList<Int>(), emptyList<Int>())
    }

    /** Get replies must be variants; returning one makes dbus-java send it as such. */
    @Suppress("UNCHECKED_CAST")
    private fun <A> property(properties: Map<String, Variant<*>>, name: String): A =
        (properties[name] ?: throw DBusExecutionException("No such property: $name")) as A

    private fun readOnly(name: String) = DBusExecutionException("Property $name is read-only")

    private fun itemLayout(index: Int, entry: TrayMenuEntry) =
        MenuLayout(FIRST_ITEM_ID + index, properties(entry), emptyList())

    private fun properties(entry: TrayMenuEntry): Map<String, Variant<*>> = when (entry) {
        is TrayMenuEntry.Action -> mapOf("label" to Variant(entry.label), "enabled" to Variant(entry.enabled))
        is TrayMenuEntry.Toggle -> mapOf(
            "label" to Variant(entry.label),
            "enabled" to Variant(entry.enabled),
            "toggle-type" to Variant("checkmark"),
            "toggle-state" to Variant(if (entry.checked) 1 else 0),
        )
        TrayMenuEntry.Separator -> mapOf("type" to Variant("separator"))
    }

    fun update(newState: TrayState) {
        val old = state
        state = newState
        try {
            if (old.active != newState.active) connection.sendMessage(StatusNotifierItemInterface.NewIcon(ITEM_PATH))
            if (old.tooltip != newState.tooltip) connection.sendMessage(StatusNotifierItemInterface.NewToolTip(ITEM_PATH))
            if (old.menu != newState.menu) {
                connection.sendMessage(DBusMenuInterface.LayoutUpdated(MENU_PATH, UInt32(revision.incrementAndGet()), ROOT_ID))
            }
        } catch (_: Exception) {
            // The shell went away; it will query the new state when it comes back.
        }
    }

    private fun register() {
        connection.getRemoteObject(WATCHER_NAME, WATCHER_PATH, StatusNotifierWatcherInterface::class.java, false)
            .RegisterStatusNotifierItem(busName)
    }

    override fun close() {
        try {
            connection.releaseBusName(busName)
        } catch (_: Exception) {
        }
        connection.close()
    }

    companion object {
        private const val ITEM_PATH = "/StatusNotifierItem"
        private const val MENU_PATH = "/MenuBar"
        private const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
        private const val WATCHER_PATH = "/StatusNotifierWatcher"
        private const val LAYOUT_SIGNATURE = "(ia{sv}av)"
        private const val ROOT_ID = 0
        private const val FIRST_ITEM_ID = 1
        private val ICON_SIZES = intArrayOf(16, 22, 24, 32, 48, 64)

        /**
         * Register a tray icon, or return null when no StatusNotifierWatcher
         * (i.e. no compatible tray) is running.
         */
        fun start(onActivate: () -> Unit): StatusNotifierTray? {
            val connection = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build()
            } catch (e: Exception) {
                System.err.println("AmneziaWG: native tray unavailable, falling back: $e")
                return null
            } catch (e: LinkageError) {
                // A JDK module missing from a trimmed runtime must not take the app down.
                System.err.println("AmneziaWG: native tray unavailable, falling back: $e")
                return null
            }
            return try {
                val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
                connection.requestBusName(busName)
                val tray = StatusNotifierTray(connection, busName, onActivate)
                connection.exportObject(ITEM_PATH, tray.item)
                connection.exportObject(MENU_PATH, tray.menu)
                tray.register()
                // Register again whenever plasmashell (the watcher) restarts.
                connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                    if (signal.name == WATCHER_NAME && signal.newOwner.isNotEmpty()) {
                        try {
                            tray.register()
                        } catch (_: Exception) {
                        }
                    }
                }
                tray
            } catch (e: Exception) {
                System.err.println("AmneziaWG: native tray unavailable, falling back: $e")
                connection.close()
                null
            }
        }

        fun loadIconImage(): BufferedImage =
            StatusNotifierTray::class.java.getResourceAsStream("/icon.png")!!.use { ImageIO.read(it) }

        private fun loadIcon(greyscale: Boolean): List<Pixmap> {
            val source = loadIconImage()
            return ICON_SIZES.map { size ->
                val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                scaled.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    drawImage(source, 0, 0, size, size, null)
                    dispose()
                }
                // ARGB32, network byte order, as the specification requires.
                val data = ByteArray(size * size * 4)
                for (y in 0 until size) for (x in 0 until size) {
                    var argb = scaled.getRGB(x, y)
                    if (greyscale) argb = toGrey(argb)
                    val i = (y * size + x) * 4
                    data[i] = (argb ushr 24).toByte()
                    data[i + 1] = (argb ushr 16).toByte()
                    data[i + 2] = (argb ushr 8).toByte()
                    data[i + 3] = argb.toByte()
                }
                Pixmap(size, size, data)
            }
        }

        /** Desaturated, slightly faded variant used while no tunnel is up. */
        private fun toGrey(argb: Int): Int {
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val l = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            return ((a * 3 / 4) shl 24) or (l shl 16) or (l shl 8) or l
        }
    }
}
