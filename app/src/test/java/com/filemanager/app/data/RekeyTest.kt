package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Carrying favourites and pins across a rename or a move.
 *
 * They are keyed by path, so a rename that carried only the exact path lost
 * every mark inside a renamed folder - and the favourites screen then forgot
 * them for good, as files that no longer existed.
 */
class RekeyTest {

    @Test
    fun `a mark on the path itself moves`() {
        assertEquals(
            setOf("/sd/Photo.jpg"),
            rekeyed(setOf("/sd/photo.jpg"), "/sd/photo.jpg", "/sd/Photo.jpg"),
        )
    }

    @Test
    fun `marks inside a renamed folder go with it`() {
        val marks = setOf("/sd/Trip/best.jpg", "/sd/Trip/day2/sunset.jpg", "/sd/other.jpg")
        assertEquals(
            setOf("/sd/Trip 2024/best.jpg", "/sd/Trip 2024/day2/sunset.jpg", "/sd/other.jpg"),
            rekeyed(marks, "/sd/Trip", "/sd/Trip 2024"),
        )
    }

    @Test
    fun `a sibling that only starts the same way is left alone`() {
        // The reason the prefix carries a slash.
        val marks = setOf("/sd/Trip/a.jpg", "/sd/Trip 2019/b.jpg")
        assertEquals(
            setOf("/sd/Holiday/a.jpg", "/sd/Trip 2019/b.jpg"),
            rekeyed(marks, "/sd/Trip", "/sd/Holiday"),
        )
    }

    @Test
    fun `nothing affected means nothing to write`() {
        assertNull(rekeyed(setOf("/sd/a.jpg"), "/sd/b.jpg", "/sd/c.jpg"))
        assertNull(rekeyed(setOf("/sd/a.jpg"), "/sd/a.jpg", "/sd/a.jpg"))
    }

    @Test
    fun `a mark already at the destination is kept, not toggled off`() {
        // The old implementation toggled, which removed a mark that happened
        // to be at the destination already.
        assertEquals(
            setOf("/sd/new.jpg"),
            rekeyed(setOf("/sd/old.jpg", "/sd/new.jpg"), "/sd/old.jpg", "/sd/new.jpg"),
        )
    }
}
