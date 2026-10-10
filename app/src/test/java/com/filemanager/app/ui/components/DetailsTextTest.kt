package com.filemanager.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsTextTest {

    @Test
    fun `counts read as words`() {
        assertEquals("1 item", countOf(1, "item"))
        assertEquals("4 items", countOf(4, "item"))
        assertEquals("0 items", countOf(0, "item"))
    }

    @Test
    fun `what a selection holds, folders first as One UI says it`() {
        assertEquals("2 files", containsText(files = 2, folders = 0))
        assertEquals("2 folders", containsText(files = 0, folders = 2))
        assertEquals("1 folder, 3 files", containsText(files = 3, folders = 1))
        assertEquals("1 folder, 1 file", containsText(files = 1, folders = 1))
        assertEquals("Nothing", containsText(files = 0, folders = 0))
    }

    @Test
    fun `dates run from the earliest to the latest, whatever the order`() {
        val early = 1_759_900_000_000L
        val late = early + 3 * 86_400_000L
        assertEquals("${shownTime(early)} - ${shownTime(late)}", modifiedRange(listOf(late, early)))
        assertEquals("one time is just that time", shownTime(early), modifiedRange(listOf(early, early)))
        assertEquals("", modifiedRange(emptyList()))
    }
}
