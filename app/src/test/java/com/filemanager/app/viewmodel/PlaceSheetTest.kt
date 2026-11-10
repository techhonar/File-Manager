package com.filemanager.app.viewmodel

import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.ui.components.breadcrumbSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSheetTest {

    private val phone = StorageVolume("Internal storage", "/storage/emulated/0", isRemovable = false, isPrimary = true)
    private val nas = RemoteServer(id = "nas", name = "Home NAS", basePath = "/media")

    private fun sheet(
        sources: List<String>,
        move: Boolean,
        at: String,
        folders: Set<String>? = emptySet(),
    ) = PlaceSheet(sources, move, folders, PlaceSpot.Phone(phone, at), isLoading = false)

    @Test
    fun `moving into the folder it is all in already is greyed out, as One UI has it`() {
        val inDownload = listOf("/storage/emulated/0/Download/a.pdf", "/storage/emulated/0/Download/b.pdf")
        assertFalse(sheet(inDownload, move = true, at = "/storage/emulated/0/Download").canPlace)
        assertTrue("copying there makes a duplicate", sheet(inDownload, move = false, at = "/storage/emulated/0/Download").canPlace)
        assertTrue(sheet(inDownload, move = true, at = "/storage/emulated/0/DCIM").canPlace)
        val mixed = inDownload + "/storage/emulated/0/DCIM/c.jpg"
        assertTrue("some of it would move", sheet(mixed, move = true, at = "/storage/emulated/0/Download").canPlace)
    }

    @Test
    fun `a greyed-out Move here or Copy here says why`() {
        val inDownload = listOf("/storage/emulated/0/Download/a.pdf")
        // Where the sheet opens for a move: Move here was greyed out with no word, and looked broken.
        assertEquals(
            "They're in this folder already. Choose another one.",
            sheet(inDownload, move = true, at = "/storage/emulated/0/Download").note,
        )
        assertNull(sheet(inDownload, move = false, at = "/storage/emulated/0/Download").note)

        val drive = StorageVolume("USB storage 1", "/mnt/media_rw/1234-ABCD", isRemovable = true, isPrimary = false, isUsb = true)
        val readOnly = PlaceSheet(inDownload, false, emptySet(), PlaceSpot.Phone(drive, drive.path), isLoading = false, writable = false)
        assertFalse(readOnly.canPlace)
        assertEquals("This storage is read-only: nothing can be put here.", readOnly.note)

        val fromDrive = listOf("/mnt/media_rw/1234-ABCD/video.mp4")
        val stuck = sheet(fromDrive, move = true, at = "/storage/emulated/0/Movies").copy(sourcesRemovable = false)
        assertFalse(stuck.canPlace)
        assertEquals("These can only be copied: the storage they're on is read-only.", stuck.note)
        assertTrue("a copy leaves them where they are", stuck.copy(move = false).canPlace)
        assertNull(stuck.copy(move = false).note)
    }

    @Test
    fun `a folder cannot go into itself`() {
        val album = listOf("/storage/emulated/0/Pictures/Album")
        assertFalse(sheet(album, move = false, at = "/storage/emulated/0/Pictures/Album").canPlace)
        assertFalse(sheet(album, move = true, at = "/storage/emulated/0/Pictures/Album/2026").canPlace)
        assertTrue("a name that only starts the same", sheet(album, move = true, at = "/storage/emulated/0/Pictures/Album 2").canPlace)
    }

    @Test
    fun `nothing is placed while the folder is loading or could not be read`() {
        val one = listOf("/storage/emulated/0/Download/a.pdf")
        assertFalse(sheet(one, move = false, at = "/storage/emulated/0/DCIM").copy(isLoading = true).canPlace)
        assertFalse(sheet(one, move = false, at = "/storage/emulated/0/DCIM").copy(error = "Permission denied").canPlace)
    }

    @Test
    fun `only files go to a server, and the sheet says so`() {
        val files = listOf("/storage/emulated/0/Download/a.pdf")
        val folder = "/storage/emulated/0/Pictures/Album"
        fun onServer(sources: List<String>, folders: Set<String>?) =
            PlaceSheet(sources, move = false, sourceFolders = folders, spot = PlaceSpot.Server(nas, "/media"), isLoading = false)

        assertFalse("not until it is known which are folders", onServer(files, null).canPlace)
        assertTrue(onServer(files, emptySet()).canPlace)
        assertNull(onServer(files, emptySet()).note)
        assertFalse(onServer(listOf(folder), setOf(folder)).canPlace)
        assertEquals("Only files can go to a server, not folders.", onServer(listOf(folder), setOf(folder)).note)
        assertTrue(onServer(files + folder, setOf(folder)).canPlace)
        assertEquals("Folders are left out: only files go to a server.", onServer(files + folder, setOf(folder)).note)
    }

    @Test
    fun `the breadcrumb runs from the top of the storage or the server`() {
        assertEquals(
            listOf(
                "Internal storage" to "/storage/emulated/0",
                "Download" to "/storage/emulated/0/Download",
                "Gopeed" to "/storage/emulated/0/Download/Gopeed",
            ),
            breadcrumbSteps(PlaceSpot.Phone(phone, "/storage/emulated/0/Download/Gopeed")),
        )
        assertEquals(
            listOf("Internal storage" to "/storage/emulated/0"),
            breadcrumbSteps(PlaceSpot.Phone(phone, "/storage/emulated/0")),
        )
        assertEquals(
            listOf("Home NAS" to "/media", "Films" to "/media/Films"),
            breadcrumbSteps(PlaceSpot.Server(nas, "/media/Films")),
        )
        val root = nas.copy(basePath = "/")
        assertEquals(
            listOf("Home NAS" to "/", "Backups" to "/Backups"),
            breadcrumbSteps(PlaceSpot.Server(root, "/Backups")),
        )
    }
}
