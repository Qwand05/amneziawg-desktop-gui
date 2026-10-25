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
import org.amnezia.awg.desktop.util.AppLog
import javax.swing.SwingUtilities

/**
 * An entry of the tray icon's context menu. Equality ignores the click
 * handlers so that the shell is only told about real changes.
 *
 * The [key] identifies an entry among its siblings across updates (the
 * label does by default): the shell keeps menu items by id, so an entry
 * must keep its id even when entries before it come and go, or the shell
 * would show it with another entry's label and state.
 */
sealed class TrayMenuEntry {
    abstract val key: String

    class Action(val label: String, val enabled: Boolean = true, override val key: String = label, val onClick: () -> Unit) : TrayMenuEntry() {
        override fun equals(other: Any?) = other is Action && other.label == label && other.enabled == enabled
        override fun hashCode() = label.hashCode() * 31 + enabled.hashCode()
    }

    class Toggle(
        val label: String,
        val checked: Boolean,
        val enabled: Boolean = true,
        override val key: String = label,
        val onClick: () -> Unit,
    ) : TrayMenuEntry() {
        override fun equals(other: Any?) =
            other is Toggle && other.label == label && other.checked == checked && other.enabled == enabled
        override fun hashCode() = (label.hashCode() * 31 + checked.hashCode()) * 31 + enabled.hashCode()
    }

    class Submenu(
        val label: String,
        val enabled: Boolean = true,
        override val key: String = label,
        val children: List<TrayMenuEntry>,
    ) : TrayMenuEntry() {
        override fun equals(other: Any?) =
            other is Submenu && other.label == label && other.enabled == enabled && other.children == children
        override fun hashCode() = (label.hashCode() * 31 + enabled.hashCode()) * 31 + children.hashCode()
    }

    /** Keyed by its ordinal among its sibling separators. */
    data object Separator : TrayMenuEntry() {
        override val key get() = "separator"
    }
}

/** What the tray shows; [active] picks the colored or the greyed out icon. */
data class TrayState(val tooltip: String, val active: Boolean, val menu: List<TrayMenuEntry>)

internal const val ROOT_ID = 0
internal const val FIRST_ITEM_ID = 1
private const val LAYOUT_SIGNATURE = "(ia{sv}av)"

/**
 * Hands out dbusmenu ids by entry path (`parent path / key`), the same id
 * for the same path for as long as the tray lives, so that an entry's id
 * never changes and no id is ever reused for a different entry.
 */
internal class MenuIds {
    private val ids = HashMap<String, Int>()

    fun idOf(path: String): Int = ids.getOrPut(path) { FIRST_ITEM_ID + ids.size }
}

/**
 * The menu as dbusmenu sees it: a tree of integer ids. The root ([ROOT_ID])
 * holds the top-level entries.
 */
internal class MenuTree(entries: List<TrayMenuEntry>, ids: MenuIds = MenuIds()) {
    class Node(val id: Int, val entry: TrayMenuEntry?, val children: List<Node>)

    private val nodes = HashMap<Int, Node>()
    val root: Node

    init {
        fun build(entry: TrayMenuEntry?, path: String): Node {
            val id = if (entry == null) ROOT_ID else ids.idOf(path)
            val children = when (entry) {
                null -> entries
                is TrayMenuEntry.Submenu -> entry.children
                else -> emptyList()
            }
            var separators = 0
            val childNodes = children.map { child ->
                val key = if (child is TrayMenuEntry.Separator) "${child.key}#${separators++}" else child.key
                build(child, "$path/$key")
            }
            return Node(id, entry, childNodes).also { nodes[id] = it }
        }
        root = build(null, "")
    }

    fun node(id: Int): Node? = nodes[id]

    /** What changed since [old]: whether any node's children differ, and the ids whose properties did. */
    class Diff(val structureChanged: Boolean, val changedIds: List<Int>)

    fun diff(old: MenuTree): Diff {
        var structureChanged = false
        val changed = ArrayList<Int>()
        for (node in nodes.values) {
            val before = old.node(node.id)
            if (before == null) {
                structureChanged = true
                continue
            }
            if (before.children.map { it.id } != node.children.map { it.id }) structureChanged = true
            if (plainProperties(before) != plainProperties(node)) changed.add(node.id)
        }
        if (old.nodes.keys != nodes.keys) structureChanged = true
        return Diff(structureChanged, changed.sorted())
    }

    /** [id]'s node with its children down to [depth] levels; negative means all of them. */
    fun layout(id: Int, depth: Int): MenuLayout? = node(id)?.let { layout(it, depth) }

    private fun layout(node: Node, depth: Int): MenuLayout {
        val children = if (depth == 0) emptyList() else node.children.map { Variant(layout(it, depth - 1), LAYOUT_SIGNATURE) }
        return MenuLayout(node.id, properties(node), children)
    }

    fun properties(node: Node): Map<String, Variant<*>> = when (val entry = node.entry) {
        null -> mapOf("children-display" to Variant("submenu"))
        is TrayMenuEntry.Action -> mapOf("label" to Variant(entry.label), "enabled" to Variant(entry.enabled))
        is TrayMenuEntry.Toggle -> mapOf(
            "label" to Variant(entry.label),
            "enabled" to Variant(entry.enabled),
            "toggle-type" to Variant("checkmark"),
            "toggle-state" to Variant(if (entry.checked) 1 else 0),
        )
        is TrayMenuEntry.Submenu -> mapOf(
            "label" to Variant(entry.label),
            "enabled" to Variant(entry.enabled),
            "children-display" to Variant("submenu"),
        )
        TrayMenuEntry.Separator -> mapOf("type" to Variant("separator"))
    }

    /** [properties] with the variants unwrapped, for comparison. */
    private fun plainProperties(node: Node): Map<String, Any?> = properties(node).mapValues { it.value.value }

    /** The click handler of [id], or null for separators, submenus and disabled items. */
    fun onClick(id: Int): (() -> Unit)? = when (val entry = node(id)?.entry) {
        is TrayMenuEntry.Action -> entry.onClick.takeIf { entry.enabled }
        is TrayMenuEntry.Toggle -> entry.onClick.takeIf { entry.enabled }
        else -> null
    }
}

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
    private val menuIds = MenuIds()
    @Volatile
    private var tree = MenuTree(emptyList(), menuIds)
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
            val tree = tree
            // An id the shell remembers from an older revision gets an empty node.
            val layout = tree.layout(parentId, recursionDepth) ?: MenuLayout(parentId, mapOf("type" to Variant("separator")), emptyList())
            return LayoutResult(UInt32(revision.get()), layout)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties> {
            val tree = tree
            return ids.mapNotNull { id -> tree.node(id)?.let { MenuItemProperties(id, tree.properties(it)) } }
        }

        override fun GetProperty(id: Int, name: String): Variant<*> {
            val tree = tree
            return tree.node(id)?.let { tree.properties(it)[name] } ?: Variant("")
        }

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            tree.onClick(id)?.let { SwingUtilities.invokeLater(it) }
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

    fun update(newState: TrayState) {
        val old = state
        state = newState
        try {
            if (old.active != newState.active) connection.sendMessage(StatusNotifierItemInterface.NewIcon(ITEM_PATH))
            if (old.tooltip != newState.tooltip) connection.sendMessage(StatusNotifierItemInterface.NewToolTip(ITEM_PATH))
            if (old.menu != newState.menu) {
                val previous = tree
                val current = MenuTree(newState.menu, menuIds)
                tree = current
                val diff = current.diff(previous)
                if (diff.structureChanged) {
                    connection.sendMessage(DBusMenuInterface.LayoutUpdated(MENU_PATH, UInt32(revision.incrementAndGet()), ROOT_ID))
                } else if (diff.changedIds.isNotEmpty()) {
                    // Labels and enabled states only (traffic, status): updated
                    // in place, so an open menu stays open.
                    val updated = diff.changedIds.map { MenuItemProperties(it, current.properties(current.node(it)!!)) }
                    connection.sendMessage(DBusMenuInterface.ItemsPropertiesUpdated(MENU_PATH, updated, emptyList()))
                }
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
        private val ICON_SIZES = intArrayOf(16, 22, 24, 32, 48, 64)

        /**
         * Register a tray icon, or return null when no StatusNotifierWatcher
         * (i.e. no compatible tray) is running.
         */
        fun start(onActivate: () -> Unit): StatusNotifierTray? {
            val connection = try {
                DBusConnectionBuilder.forSessionBus().withShared(false).build()
            } catch (e: Exception) {
                AppLog.w("Tray", "Native tray unavailable, falling back: $e")
                return null
            } catch (e: LinkageError) {
                // A JDK module missing from a trimmed runtime must not take the app down.
                AppLog.w("Tray", "Native tray unavailable, falling back: $e")
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
                AppLog.w("Tray", "Native tray unavailable, falling back: $e")
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
