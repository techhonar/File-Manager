package com.filemanager.app.data.external

import com.filemanager.app.data.viewer.ViewerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingTest {

    @Test
    fun `finds the viewer from the name first, then the type`() {
        assertEquals(ViewerKind.PDF, viewerKindFor("application/octet-stream", "report.pdf"))
        assertEquals(ViewerKind.PDF, viewerKindFor("application/pdf", "12345"))
        assertEquals(ViewerKind.DOCX, viewerKindFor(DOCX_TYPE, null))
        assertEquals(ViewerKind.DOC, viewerKindFor(DOC_TYPE, "1234"))
        assertEquals(ViewerKind.DOC, viewerKindFor("text/rtf", null))
        assertEquals(ViewerKind.XLSX, viewerKindFor(XLSX_TYPE, null))
        assertEquals(ViewerKind.XLSX, viewerKindFor("application/vnd.ms-excel.sheet.macroEnabled.12", "1234"))
        assertEquals(ViewerKind.PPTX, viewerKindFor(PPTX_TYPE, null))
        assertEquals(ViewerKind.PPTX, viewerKindFor("application/vnd.openxmlformats-officedocument.presentationml.slideshow", null))
        assertNull(viewerKindFor("application/vnd.ms-excel", "1234"))
        assertEquals(ViewerKind.TEXT, viewerKindFor("text/plain; charset=utf-8", "msg"))
        assertEquals(ViewerKind.TEXT, viewerKindFor("application/json", null))
        assertNull(viewerKindFor("image/png", "photo"))
        assertNull(viewerKindFor(null, null))
    }

    @Test
    fun `a list of types wins over the umbrella type`() {
        assertEquals(listOf("image/*", "application/pdf"), requestedTypes("*/*", arrayOf("image/*", "application/PDF")))
        assertEquals(listOf("image/*"), requestedTypes("image/*", null))
        assertEquals(emptyList<String>(), requestedTypes("*/*", null))
        assertEquals(emptyList<String>(), requestedTypes(null, emptyArray()))
        assertEquals(emptyList<String>(), requestedTypes("image/*", arrayOf("*/*")))
    }

    @Test
    fun `accepts exact types and wildcards`() {
        assertTrue(accepts(emptyList(), null))
        assertTrue(accepts(listOf("image/*"), "image/jpeg"))
        assertTrue(accepts(listOf("application/pdf"), "application/pdf"))
        assertFalse(accepts(listOf("image/*"), "video/mp4"))
        assertFalse(accepts(listOf("image/*"), null))
        assertFalse(accepts(listOf("image/*"), "imagex/odd"))
    }

    @Test
    fun `turns storage document ids into folders`() {
        val primary = "/storage/emulated/0"
        assertEquals("/storage/emulated/0/Download", documentFolder(listOf("document", "primary:Download"), primary))
        assertEquals("/storage/emulated/0", documentFolder(listOf("root", "primary"), primary))
        assertEquals("/storage/1234-ABCD/Music/Rock", documentFolder(listOf("document", "1234-ABCD:Music/Rock"), primary))
        assertEquals("/storage/emulated/0/Documents", documentFolder(listOf("root", "home"), primary))
        assertEquals(
            "/storage/emulated/0/DCIM/Camera",
            documentFolder(listOf("tree", "primary:DCIM", "document", "primary:DCIM/Camera"), primary),
        )
        assertNull(documentFolder(listOf("something"), primary))
    }

    @Test
    fun `a card or drive is found where the phone has it, not always under storage`() {
        val primary = "/storage/emulated/0"
        val volumes = listOf(primary, "/storage/1234-ABCD", "/mnt/media_rw/5E6F-7A8B")
        assertEquals("/storage/1234-ABCD/Music", documentFolder(listOf("document", "1234-ABCD:Music"), primary, volumes))
        // A USB drive, which most phones keep somewhere else.
        assertEquals("/mnt/media_rw/5E6F-7A8B/Photos/2024", documentFolder(listOf("document", "5E6F-7A8B:Photos/2024"), primary, volumes))
        assertEquals("/mnt/media_rw/5E6F-7A8B", documentFolder(listOf("root", "5e6f-7a8b"), primary, volumes))
        // One that isn't in now: where a card would be.
        assertEquals("/storage/9999-0000/x", documentFolder(listOf("document", "9999-0000:x"), primary, volumes))
        assertEquals("/storage/emulated/0/Download", documentFolder(listOf("document", "primary:Download"), primary, volumes))
    }

    @Test
    fun `makes names safe to save under`() {
        assertEquals("a_b.pdf", safeFileName("a/b.pdf"))
        assertEquals("file", safeFileName(null))
        assertEquals("file", safeFileName(".."))
        assertEquals(".gitignore", safeFileName(".gitignore"))
        val long = "x".repeat(300) + ".docx"
        assertEquals(120, safeFileName(long).length)
        assertTrue(safeFileName(long).endsWith(".docx"))
    }
}
