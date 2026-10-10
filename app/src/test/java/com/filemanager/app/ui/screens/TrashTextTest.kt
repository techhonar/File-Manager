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
}
