package com.filemanager.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTypesTest {

    @Test
    fun `a PDF and a Word file are different kinds`() {
        assertEquals(DocumentType(DocumentKind.PDF, "PDF"), documentType("Boarding pass.pdf"))
        assertEquals(DocumentType(DocumentKind.WORD, "DOCX"), documentType("Project plan.docx"))
        assertEquals(DocumentType(DocumentKind.SHEET, "XLSX"), documentType("Budget 2026.xlsx"))
        assertEquals(DocumentType(DocumentKind.SLIDES, "PPTX"), documentType("Pitch.pptx"))
        assertEquals(DocumentType(DocumentKind.TEXT, "TXT"), documentType("notes.txt"))
        assertEquals(DocumentType(DocumentKind.WEB, "JSON"), documentType("data.json"))
        assertEquals(DocumentType(DocumentKind.EBOOK, "EPUB"), documentType("Novel.epub"))
    }

    @Test
    fun `the label is the extension as it is, whatever its case`() {
        assertEquals(DocumentType(DocumentKind.PDF, "PDF"), documentType("SCAN.PDF"))
        assertEquals(DocumentType(DocumentKind.WORD, "DOC"), documentType("old letter.Doc"))
        assertEquals("only the last dot counts", DocumentType(DocumentKind.SHEET, "CSV"), documentType("report.v2.csv"))
    }

    @Test
    fun `every extension the Rust core counts as a document has a kind`() {
        // DOCUMENT in rust-core/src/categories.rs. A document this misses
        // falls back to the old shared icon, which is the bug this fixed.
        val rustDocuments = listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "md",
            "csv", "epub", "html", "htm", "json", "xml",
        )
        rustDocuments.forEach { assertNotNull(it, documentType("file.$it")) }
    }

    @Test
    fun `anything that is not a document has no kind`() {
        listOf("photo.jpg", "song.mp3", "backup.zip", "app.apk", "README", "archive.tar.gz", ".hidden")
            .forEach { assertNull(it, documentType(it)) }
    }

    @Test
    fun `labels fit their space, the shorter ones larger`() {
        assertTrue(documentLabelScale("MD") > documentLabelScale("PDF"))
        assertTrue(documentLabelScale("PDF") > documentLabelScale("DOCX"))
        val longest = listOf("pdf", "docx", "html", "json", "epub", "pptx", "xlsx")
            .maxOf { documentType("f.$it")!!.label.length }
        assertEquals("no label is longer than four letters", 4, longest)
    }
}
