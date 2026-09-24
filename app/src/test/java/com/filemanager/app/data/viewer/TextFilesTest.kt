package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(bytes: ByteArray) = File(temp.root, "a.txt").apply { writeBytes(bytes) }

    @Test
    fun `splits lines and drops Windows line endings`() {
        val content = TextFiles.read(file("one\r\ntwo\n\nfour\n".toByteArray()))
        assertEquals(listOf("one", "two", "", "four"), content.lines)
        assertFalse(content.truncated)
    }

    @Test
    fun `an empty file has no lines`() {
        assertEquals(emptyList<String>(), TextFiles.read(file(ByteArray(0))).lines)
    }

    @Test
    fun `reads UTF-16 when a byte-order mark says so`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "héllo".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "héllo".toByteArray(Charsets.UTF_16BE)
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray()
        assertEquals("héllo", TextFiles.decode(le))
        assertEquals("héllo", TextFiles.decode(be))
        assertEquals("héllo", TextFiles.decode(utf8))
        assertEquals("héllo", TextFiles.decode("héllo".toByteArray()))
    }

    @Test
    fun `stops at the limit and says so`() {
        val content = TextFiles.read(file("abcdefghij".toByteArray()), limit = 4)
        assertEquals(listOf("abcd"), content.lines)
        assertTrue(content.truncated)
    }

    @Test
    fun `breaks a very long line into pieces`() {
        assertEquals(listOf("abc", "def", "g", "x"), TextFiles.lines("abcdefg\nx", piece = 3))
    }

    @Test
    fun `picks the viewer by extension`() {
        assertEquals(ViewerKind.PDF, viewerKind("Report.PDF"))
        assertEquals(ViewerKind.DOCX, viewerKind("letter.docx"))
        assertEquals(ViewerKind.TEXT, viewerKind("notes.txt"))
        assertEquals(ViewerKind.TEXT, viewerKind(".gitignore"))
        assertNull(viewerKind("old.doc"))
        assertNull(viewerKind("page.html"))
        assertNull(viewerKind("README"))
    }
}
