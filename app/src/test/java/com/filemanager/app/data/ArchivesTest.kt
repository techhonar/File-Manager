package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which archives a tap offers to extract, and the folder each unpacks into. */
class ArchivesTest {

    @Test
    fun `every kind the core unpacks is offered`() {
        listOf(
            "a.zip", "a.rar", "a.7z", "a.tar",
            "a.tar.gz", "a.tgz", "a.tar.bz2", "a.tbz2", "a.tar.xz", "a.txz", "a.tar.zst",
            "notes.txt.gz", "SHOUTING.RAR",
        ).forEach { assertTrue(it, isExtractable(it)) }
    }

    @Test
    fun `anything else is left to other apps`() {
        listOf("disc.iso", "game.xapk", "app.apk", "photo.jpg", "rar", "archive.zip.txt")
            .forEach { assertFalse(it, isExtractable(it)) }
    }

    @Test
    fun `a compressed tar unpacks into a folder named without either ending`() {
        assertEquals("photos", archiveBaseName("photos.tar.gz"))
        assertEquals("photos", archiveBaseName("photos.TAR.XZ"))
        assertEquals("photos", archiveBaseName("photos.tgz"))
        assertEquals("photos", archiveBaseName("photos.7z"))
        assertEquals("notes.txt", archiveBaseName("notes.txt.gz"))
        assertEquals("v1.2", archiveBaseName("v1.2.zip"))
    }

    @Test
    fun `a name that is nothing but an ending keeps it`() {
        assertEquals(".zip", archiveBaseName(".zip"))
    }
}
