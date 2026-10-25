/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dbusmenu id tree behind the tray menu, submenus included. */
class MenuTreeTest {
    private var clicked = ""

    /** The menu as `Main.kt` builds it, for one tunnel that is up when [traffic] is given. */
    private fun menu(windowVisible: Boolean = false, traffic: String? = null, busy: Boolean = false) = listOf(
        TrayMenuEntry.Action(if (windowVisible) "Hide" else "Show", key = "window") { clicked = "window" },
        TrayMenuEntry.Separator,
        TrayMenuEntry.Submenu(
            "office — " + if (traffic != null) "Connected" else "Not connected",
            key = "tunnel:office",
            children = buildList {
                if (traffic != null) {
                    add(TrayMenuEntry.Action(traffic, enabled = false, key = "traffic") { clicked = "traffic" })
                    add(TrayMenuEntry.Separator)
                }
                add(TrayMenuEntry.Action(if (traffic != null) "Disconnect" else "Connect", enabled = !busy, key = "toggle") { clicked = "toggle" })
                add(TrayMenuEntry.Action("Show details", key = "details") { clicked = "details" })
            },
        ),
        TrayMenuEntry.Separator,
        TrayMenuEntry.Action("Settings", key = "settings") { clicked = "settings" },
        TrayMenuEntry.Action("Quit", key = "quit") { clicked = "quit" },
    )

    private fun label(tree: MenuTree, id: Int) = (tree.node(id)!!.entry as TrayMenuEntry.Action).label

    @Test
    fun idsAreAssignedDepthFirst() {
        val tree = MenuTree(menu())
        assertEquals(listOf(1, 2, 3, 6, 7, 8), tree.root.children.map { it.id })
        assertEquals(listOf(4, 5), tree.node(3)!!.children.map { it.id })
        assertEquals("Connect", label(tree, 4))
        assertEquals("Settings", label(tree, 7))
        assertNull(tree.node(9))
    }

    @Test
    fun idsStayStableWhenEntriesAppearAndChange() {
        val ids = MenuIds()
        val down = MenuTree(menu(), ids)
        val up = MenuTree(menu(traffic = "rx: 1 KiB, tx: 2 KiB"), ids)
        // Everything keeps its id; the two new entries get fresh ones.
        assertEquals("Settings", label(up, 7))
        assertEquals("Quit", label(up, 8))
        assertEquals("Disconnect", label(up, 4))
        assertEquals("Show details", label(up, 5))
        assertEquals(listOf(9, 10, 4, 5), up.node(3)!!.children.map { it.id })
        assertEquals("rx: 1 KiB, tx: 2 KiB", label(up, 9))
        assertTrue(up.node(10)!!.entry is TrayMenuEntry.Separator)

        val diff = up.diff(down)
        assertTrue(diff.structureChanged)
        // Back down: the ids are not reused for anything else.
        val downAgain = MenuTree(menu(), ids)
        assertEquals(listOf(4, 5), downAgain.node(3)!!.children.map { it.id })
        assertNull(downAgain.node(9))
        assertTrue(downAgain.diff(up).structureChanged)
    }

    @Test
    fun propertyChangesAreReportedWithoutAStructureChange() {
        val ids = MenuIds()
        val before = MenuTree(menu(traffic = "rx: 1 KiB, tx: 2 KiB"), ids)
        val after = MenuTree(menu(windowVisible = true, traffic = "rx: 3 KiB, tx: 2 KiB", busy = true), ids)
        val diff = after.diff(before)
        assertFalse(diff.structureChanged)
        // The window entry's label (1), the traffic label (4) and the toggle's enabled state (6).
        assertEquals(listOf(1, 4, 6), diff.changedIds)
        val same = MenuTree(menu(windowVisible = true, traffic = "rx: 3 KiB, tx: 2 KiB", busy = true), ids)
        assertFalse(same.diff(after).structureChanged)
        assertEquals(emptyList<Int>(), same.diff(after).changedIds)
    }

    @Test
    fun layoutHonoursTheRecursionDepth() {
        val tree = MenuTree(menu(traffic = "rx: 1 KiB, tx: 2 KiB"))
        val all = tree.layout(ROOT_ID, -1)!!
        assertEquals(6, all.children.size)
        val submenu = all.children[2].value as MenuLayout
        assertEquals(3, submenu.id)
        assertEquals(4, submenu.children.size)

        val oneLevel = tree.layout(ROOT_ID, 1)!!
        assertEquals(0, (oneLevel.children[2].value as MenuLayout).children.size)
        assertEquals(0, tree.layout(ROOT_ID, 0)!!.children.size)

        assertEquals(4, tree.layout(3, -1)!!.children.size)
        assertNull(tree.layout(42, -1))
    }

    @Test
    fun propertiesDescribeEachKindOfEntry() {
        val tree = MenuTree(menu(traffic = "rx: 1 KiB, tx: 2 KiB"))
        fun props(id: Int) = tree.properties(tree.node(id)!!).mapValues { it.value.value }
        assertEquals("submenu", props(ROOT_ID)["children-display"])
        assertEquals(mapOf("label" to "Show", "enabled" to true), props(1))
        assertEquals("separator", props(2)["type"])
        assertEquals("submenu", props(3)["children-display"])
        assertEquals("office — Connected", props(3)["label"])
        assertEquals(false, props(4)["enabled"])
    }

    @Test
    fun onlyEnabledItemsAreClickable() {
        val tree = MenuTree(menu(traffic = "rx: 1 KiB, tx: 2 KiB"))
        assertNull(tree.onClick(ROOT_ID))
        assertNull(tree.onClick(2))
        assertNull(tree.onClick(3))
        assertNull(tree.onClick(4))
        assertNull(tree.onClick(99))
        assertNotNull(tree.onClick(6))
        tree.onClick(6)!!()
        assertEquals("toggle", clicked)
    }

    @Test
    fun equalityIgnoresHandlersButNotChildren() {
        assertEquals(menu(), menu())
        assertTrue(menu() != menu(traffic = "rx: 1 KiB, tx: 2 KiB"))
        assertTrue(menu() != menu(windowVisible = true))
    }
}
