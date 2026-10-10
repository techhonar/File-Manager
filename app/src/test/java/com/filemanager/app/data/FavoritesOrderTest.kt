package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The order favourites are kept in: as added, or as dragged. */
class FavoritesOrderTest {

    @Test
    fun `favourites keep the order stored for them`() {
        val members = setOf("/sd/b.txt", "/sd/a.txt", "/sd/c.txt")
        assertEquals(
            listOf("/sd/c.txt", "/sd/a.txt", "/sd/b.txt"),
            ordered(members, listOf("/sd/c.txt", "/sd/a.txt", "/sd/b.txt")).toList(),
        )
    }

    @Test
    fun `ones marked before there was an order follow, by name, as they were listed`() {
        val members = setOf("/sd/Zed.txt", "/sd/apple.txt", "/sd/kept.txt", "/sd/Mango.txt")
        assertEquals(
            listOf("/sd/kept.txt", "/sd/apple.txt", "/sd/Mango.txt", "/sd/Zed.txt"),
            ordered(members, listOf("/sd/kept.txt")).toList(),
        )
    }

    @Test
    fun `an order naming a favourite since removed skips it`() {
        assertEquals(listOf("/sd/a.txt"), ordered(setOf("/sd/a.txt"), listOf("/sd/gone.txt", "/sd/a.txt")).toList())
    }

    @Test
    fun `a drag reorders what was shown and leaves the hidden where they were`() {
        val all = listOf("/sd/a.txt", "/sd/.hidden", "/sd/b.txt", "/sd/c.txt")
        // On screen: a, b, c - the hidden one is not - and c dragged to the top.
        assertEquals(
            listOf("/sd/c.txt", "/sd/.hidden", "/sd/a.txt", "/sd/b.txt"),
            reordered(all, listOf("/sd/c.txt", "/sd/a.txt", "/sd/b.txt")),
        )
    }

    @Test
    fun `a drag naming something not a favourite changes nothing else`() {
        val all = listOf("/sd/a.txt", "/sd/b.txt")
        assertEquals(listOf("/sd/b.txt", "/sd/a.txt"), reordered(all, listOf("/sd/stray.txt", "/sd/b.txt", "/sd/a.txt")))
    }

    @Test
    fun `a renamed favourite keeps its place`() {
        val marks = linkedSetOf("/sd/a.txt", "/sd/Trip/best.jpg", "/sd/z.txt")
        assertEquals(
            listOf("/sd/a.txt", "/sd/Trip 2024/best.jpg", "/sd/z.txt"),
            rekeyed(marks, "/sd/Trip", "/sd/Trip 2024")!!.toList(),
        )
    }
}
