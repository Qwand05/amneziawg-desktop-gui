/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.desktop.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The dbusmenu id tree behind the tray menu, submenus included. */
class MenuTreeTest {
    private var clicked = ""
    private val menu = listOf(
        TrayMenuEntry.Action("Show") { clicked = "show" },
        TrayMenuEntry.Separator,
        TrayMenuEntry.Submenu(
            "office — Connected",
            children = listOf(
                TrayMenuEntry.Action("rx: 1 KiB, tx: 2 KiB", enabled = false) { clicked = "stats" },
                TrayMenuEntry.Separator,
                TrayMenuEntry.Action("Disconnect") { clicked = "disconnect" },
            ),
        ),
        TrayMenuEntry.Action("Quit") { clicked = "quit" },
    )
    private val tree = MenuTree(menu)

    @Test
    fun idsAreAssignedDepthFirst() {
        assertEquals(listOf(1, 2, 3, 7), tree.root.children.map { it.id })
        assertEquals(listOf(4, 5, 6), tree.node(3)!!.children.map { it.id })
        assertEquals("Disconnect", (tree.node(6)!!.entry as TrayMenuEntry.Action).label)
        assertEquals("Quit", (tree.node(7)!!.entry as TrayMenuEntry.Action).label)
        assertNull(tree.node(8))
    }

    @Test
    fun layoutHonoursTheRecursionDepth() {
        val all = tree.layout(ROOT_ID, -1)!!
        assertEquals(4, all.children.size)
        val submenu = all.children[2].value as MenuLayout
        assertEquals(3, submenu.id)
        assertEquals(3, submenu.children.size)

        val oneLevel = tree.layout(ROOT_ID, 1)!!
        assertEquals(0, (oneLevel.children[2].value as MenuLayout).children.size)
        assertEquals(0, tree.layout(ROOT_ID, 0)!!.children.size)

        assertEquals(3, tree.layout(3, -1)!!.children.size)
        assertNull(tree.layout(42, -1))
    }

    @Test
    fun propertiesDescribeEachKindOfEntry() {
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
        assertNull(tree.onClick(ROOT_ID))
        assertNull(tree.onClick(2))
        assertNull(tree.onClick(3))
        assertNull(tree.onClick(4))
        assertNull(tree.onClick(99))
        assertNotNull(tree.onClick(6))
        tree.onClick(6)!!()
        assertEquals("disconnect", clicked)
    }

    @Test
    fun equalityIgnoresHandlersButNotChildren() {
        val same = listOf(
            TrayMenuEntry.Action("Show") {},
            TrayMenuEntry.Separator,
            TrayMenuEntry.Submenu(
                "office — Connected",
                children = listOf(
                    TrayMenuEntry.Action("rx: 1 KiB, tx: 2 KiB", enabled = false) {},
                    TrayMenuEntry.Separator,
                    TrayMenuEntry.Action("Disconnect") {},
                ),
            ),
            TrayMenuEntry.Action("Quit") {},
        )
        assertEquals(menu, same)
        val changed = same.toMutableList().apply {
            this[2] = TrayMenuEntry.Submenu("office — Connected", children = listOf(TrayMenuEntry.Action("Connect") {}))
        }
        assert(menu != changed)
    }
}
