package com.filemanager.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.filemanager_core.TrashItem

class TrashTextTest {

    private fun item(days: Int, isDir: Boolean = false) =
        TrashItem("id", "a.txt", "/storage/emulated/0/a.txt", 0uL, 1uL, isDir, days)

    @Test
    fun `groups are headed by the days left`() {
        assertEquals("30 days until deletion", deletionHeading(30))
        assertEquals("1 day until deletion", deletionHeading(1))
        assertEquals("Deleting today", deletionHeading(0))
        assertEquals("past due is still today, until the purge", "Deleting today", deletionHeading(-2))
    }

    @Test
    fun `details say when a file or a folder goes`() {
        assertEquals("This file will be deleted in 30 days.", deletionNote(item(30)))
        assertEquals("This folder will be deleted in 1 day.", deletionNote(item(1, isDir = true)))
        assertEquals("This file will be deleted today.", deletionNote(item(0)))
    }

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
