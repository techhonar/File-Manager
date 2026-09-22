package com.filemanager.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The name extraction and compression write to.
 *
 * Worth pinning because getting it wrong is silent: extraction used to write
 * into a folder that already existed, replacing the user's own files where the
 * names matched, and nothing reported it.
 */
class FreeNameTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("free-name").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `the plain name when nothing is using it`() {
        assertEquals("photos", freeName(dir, "photos").name)
        assertEquals("photos.zip", freeName(dir, "photos", "zip").name)
    }

    @Test
    fun `a folder that exists is never the answer`() {
        File(dir, "photos").mkdir()
        File(dir, "photos (1)").mkdir()
        val chosen = freeName(dir, "photos")
        assertEquals("photos (2)", chosen.name)
        assertFalse(chosen.exists())
    }

    @Test
    fun `a file counts as taken as much as a folder`() {
        // Extracting "report.zip" beside a plain file called "report" must not
        // try to make a folder where the file is.
        File(dir, "report").writeText("a file, not a folder")
        assertEquals("report (1)", freeName(dir, "report").name)
    }

    @Test
    fun `the number goes before the extension`() {
        File(dir, "album.zip").writeText("x")
        assertEquals("album (1).zip", freeName(dir, "album", "zip").name)
    }
}
