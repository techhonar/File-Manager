package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageVolumesTest {

    private val phone = StorageVolume("Internal storage", "/storage/emulated/0", isRemovable = false, isPrimary = true)
    private val card = StorageVolume("SD card", "/storage/1234-ABCD", isRemovable = true, isPrimary = false)
    private val drive = StorageVolume("USB storage 1", "/mnt/media_rw/5678-EF01", isRemovable = true, isPrimary = false, isUsb = true)
    private val volumes = listOf(phone, card, drive)

    @Test
    fun `a USB drive is told apart from a card by where it is mounted, or by its name`() {
        assertTrue(isUsbDrive("/mnt/media_rw/5678-EF01", "USB storage 1", removable = true))
        assertTrue("mounted where a card would be, but named for what it is", isUsbDrive("/storage/5678-EF01", "SanDisk USB drive", removable = true))
        assertFalse(isUsbDrive("/storage/1234-ABCD", "SD card", removable = true))
        assertFalse(isUsbDrive("/storage/emulated/0", "Internal storage", removable = false))
        assertFalse("only removable storage is a drive", isUsbDrive("/mnt/media_rw/x", "USB", removable = false))
    }

    @Test
    fun `a path is on the volume that holds it most closely`() {
        assertEquals(phone, volumes.volumeOf("/storage/emulated/0/Download/a.pdf"))
        assertEquals(card, volumes.volumeOf("/storage/1234-ABCD"))
        assertEquals(drive, volumes.volumeOf("/mnt/media_rw/5678-EF01/Movies/clip.mp4"))
        assertNull("a name that only starts the same", volumes.volumeOf("/storage/1234-ABCDE/x"))
        assertNull(volumes.volumeOf("/data/local/tmp/x"))
        assertEquals("USB storage 1/Movies", shownPath("/mnt/media_rw/5678-EF01/Movies", volumes))
    }

    @Test
    fun `only what is on a USB drive is deleted for good, and the question says which`() {
        assertNull(volumes.permanentDelete(listOf("/storage/emulated/0/a.jpg", "/storage/1234-ABCD/b.jpg")))
        assertNull(volumes.permanentDelete(emptyList()))
        assertEquals(
            PermanentDelete(onDrive = 1, total = 1, drive = "USB storage 1", onlyName = "clip.mp4"),
            volumes.permanentDelete(listOf("/mnt/media_rw/5678-EF01/Movies/clip.mp4")),
        )
        assertEquals(
            PermanentDelete(onDrive = 2, total = 3, drive = "USB storage 1", onlyName = null),
            volumes.permanentDelete(
                listOf("/mnt/media_rw/5678-EF01/a.jpg", "/storage/emulated/0/b.jpg", "/mnt/media_rw/5678-EF01/c.jpg"),
            ),
        )
    }

    @Test
    fun `a delete says what went where`() {
        assertEquals("3 moved to trash", Deleted(trashed = 3, erased = 0).message)
        assertEquals("2 deleted", Deleted(trashed = 0, erased = 2).message)
        assertEquals("2 deleted, 1 moved to trash", Deleted(trashed = 1, erased = 2).message)
    }

    @Test
    fun `an error from the core is said as what went wrong, not as its raw text`() {
        assertEquals("Input/output error", osReason("/mnt/media_rw/5678-EF01/clip.mp4: Input/output error (os error 5)"))
        assertEquals("Stale file handle", osReason("Stale file handle (os error 116)"))
        assertNull(osReason(""))
    }
}
