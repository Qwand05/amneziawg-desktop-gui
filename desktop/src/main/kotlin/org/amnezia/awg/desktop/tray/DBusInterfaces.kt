/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
@file:Suppress("FunctionName", "unused")

package org.amnezia.awg.desktop.tray

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/*
 * D-Bus interfaces of the freedesktop/KDE system tray protocol:
 *
 *  - org.kde.StatusNotifierItem: the icon itself, shown natively by Plasma,
 *    and by GNOME with the AppIndicator extension.
 *  - com.canonical.dbusmenu: its context menu, rendered by the shell.
 *
 * Member names must match the specification exactly, hence the
 * capitalised function names.
 */

/** One icon size, ARGB32 in network byte order. */
class Pixmap(
    @field:Position(0) @JvmField val width: Int,
    @field:Position(1) @JvmField val height: Int,
    @field:Position(2) @JvmField val data: ByteArray,
) : Struct()

class ToolTip(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val iconPixmap: List<Pixmap>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val description: String,
) : Struct()

/*
 * Properties are served through org.freedesktop.DBus.Properties implemented
 * by hand (see AppTray.kt): dbus-java's @DBusBoundProperty answers Get with
 * the bare value instead of a variant, which strict clients reject.
 */
@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItemInterface : DBusInterface {
    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewIcon(path: String) : DBusSignal(path)
    class NewToolTip(path: String) : DBusSignal(path)
    class NewTitle(path: String) : DBusSignal(path)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcherInterface : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

/** A menu node: id, properties and children (each a variant holding a [MenuLayout]). */
class MenuLayout(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

class MenuItemProperties(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

/** Properties an item no longer has, for ItemsPropertiesUpdated. */
class MenuItemRemovedProperties(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val names: List<String>,
) : Struct()

class MenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()

/*
 * Multiple return values. dbus-java derives their D-Bus types from the type
 * arguments at the use site, hence the generic parameters.
 */
class LayoutResult<A, B>(
    @field:Position(0) @JvmField val revision: A,
    @field:Position(1) @JvmField val layout: B,
) : Tuple()

class AboutToShowGroupResult<A, B>(
    @field:Position(0) @JvmField val updatesNeeded: A,
    @field:Position(1) @JvmField val idErrors: B,
) : Tuple()

@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenuInterface : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutResult<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupResult<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)

    /** Property changes of existing items; the shell applies them without rebuilding the menu. */
    class ItemsPropertiesUpdated(
        path: String,
        val updatedProps: List<MenuItemProperties>,
        val removedProps: List<MenuItemRemovedProperties>,
    ) : DBusSignal(path, updatedProps, removedProps)
}
