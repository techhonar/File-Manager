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
    fun `saves a file back in its own encoding and line breaks`() {
        val original = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "one\r\ntwo\r\n".toByteArray(Charsets.UTF_16LE)
        val f = file(original)
        val ready = TextFiles.openForEditing(f) as Editing.Ready
        assertEquals("one\ntwo\n", ready.text)
        assertEquals(TextEncoding.UTF16LE, ready.encoding)
        assertTrue(ready.crlf)

        // Unchanged, it is the same bytes; edited, the edit alone differs.
        TextFiles.save(f, ready.text, ready.encoding, ready.crlf)
        assertTrue(original.contentEquals(f.readBytes()))
        TextFiles.save(f, "one\nthree\n", ready.encoding, ready.crlf)
        val expected = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "one\r\nthree\r\n".toByteArray(Charsets.UTF_16LE)
        assertTrue(expected.contentEquals(f.readBytes()))
        assertEquals(listOf("a.txt"), temp.root.list()!!.toList())
    }

    @Test
    fun `keeps Unix line breaks and a missing byte-order mark`() {
        val f = file("x\ny".toByteArray())
        val ready = TextFiles.openForEditing(f) as Editing.Ready
        assertEquals(TextEncoding.UTF8, ready.encoding)
        assertFalse(ready.crlf)
        TextFiles.save(f, "x\r\nz", ready.encoding, ready.crlf)
        assertEquals("x\nz", f.readText())
    }

    @Test
    fun `refuses to edit what isn't valid text or is too large`() {
        // Latin-1 "café": not valid UTF-8, and saving it as read would lose the é.
        assertEquals(Editing.NotText, TextFiles.openForEditing(file(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))))
        assertEquals(Editing.TooLarge, TextFiles.openForEditing(file(ByteArray(10) { 0x61 }), limit = 5))
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
