package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A file from the test resources, made by LibreOffice's Word 97 and RTF writers. */
    private fun sample(name: String, saveAs: String = name): File {
        val file = File(temp.root, saveAs)
        javaClass.getResourceAsStream("/viewer/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun paragraphs(document: DocxDocument) = document.blocks.filterIsInstance<DocParagraph>()

    /** Both formats, read from the same document, come out the same. */
    private fun assertSampleRead(document: DocxDocument) {
        val shown = paragraphs(document)
        assertEquals(
            listOf(
                "Quarterly report",
                "This report covers sales, costs and the plan for next year.",
                "Highlights",
                "Sales rose by 14%",
                "Costs fell",
                "First step",
                "Second step",
                "A centred line",
                "Line one\nLine two, after a break",
                // A link's text, without the field code around it.
                "Visit our website for more.",
                "این یک پاراگراف فارسی است.",
                "Café, naïve, Ünïcödé — done.",
            ),
            shown.map { it.text },
        )
        assertEquals(DocStyle.HEADING1, shown[0].style)
        assertEquals(DocStyle.HEADING2, shown[2].style)
        assertEquals(DocStyle.BODY, shown[1].style)
        assertTrue(shown[1].spans.any { it.text == "sales" && it.bold })
        assertTrue(shown[1].spans.any { it.text == "costs" && it.italic })
        assertEquals(listOf("•", "•", "1.", "2."), shown.subList(3, 7).map { it.marker })
        assertNull(shown[1].marker)
        assertEquals(DocAlign.CENTER, shown[7].align)
        assertEquals(true, shown[10].rtl)

        val table = document.blocks.filterIsInstance<DocTable>().single()
        assertEquals(3, table.columns.size)
        assertEquals(
            listOf(listOf("Region", "Q1", "Q2"), listOf("North", "12,500", "14,250"), listOf("South", "", "8,120")),
            table.rows.map { row -> row.map { cell -> cell.blocks.filterIsInstance<DocParagraph>().joinToString("\n") { it.text } } },
        )
        // The table stands where it is in the document: after the line break, before the link.
        val tableAt = document.blocks.indexOf(table)
        assertEquals("Line one\nLine two, after a break", (document.blocks[tableAt - 1] as DocParagraph).text)
        assertEquals("Visit our website for more.", (document.blocks[tableAt + 1] as DocParagraph).text)
    }

    @Test
    fun `reads a Word 97 to 2003 document`() {
        val document = Doc.read(sample("sample.doc"))
        assertSampleRead(document)
        assertEquals(false, document.truncated)
    }

    @Test
    fun `reads RTF, and a doc that is RTF inside`() {
        assertSampleRead(Doc.read(sample("sample.rtf")))
        assertSampleRead(Doc.read(sample("sample.rtf", saveAs = "letter.doc")))
    }

    @Test
    fun `a doc that is a docx inside is read as one`() {
        val file = File(temp.root, "renamed.doc")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>From a docx</w:t></w:r></w:p></w:body></w:document>"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }
        assertEquals(listOf("From a docx"), paragraphs(Doc.read(file)).map { it.text })
    }

    @Test
    fun `a long document stops at the most characters and says so`() {
        val document = Doc.read(sample("sample.doc"), maxChars = 40)
        assertTrue(document.truncated)
        assertTrue(paragraphs(document).sumOf { it.text.length } <= 40)
    }

    @Test(expected = IOException::class)
    fun `a file that is not a Word document fails to read`() {
        val file = File(temp.root, "notes.doc").apply { writeText("just some text") }
        Doc.read(file)
    }

    @Test(expected = IOException::class)
    fun `a damaged Word document fails to read, rather than looping or reading past its end`() {
        val whole = sample("sample.doc")
        val cut = File(temp.root, "cut.doc").apply { writeBytes(whole.readBytes().copyOf(2048)) }
        Doc.read(cut)
    }

    private fun rtf(body: String) = Doc.read(File(temp.root, "t${System.nanoTime()}.rtf").apply { writeBytes(body.toByteArray(Charsets.ISO_8859_1)) })

    @Test
    fun `RTF escapes, Unicode and code pages`() {
        // Built from parts, so the source holds no backslash-u escape: some editors and tools turn one into the character it names.
        val backslash = Char(92)
        val document = rtf("""{\rtf1\ansi\ansicpg1252 Caf\'e9 """ + "${backslash}u8364?5" + """ a\~b \{x\}\par}""")
        assertEquals(listOf("Café €5 a\u00A0b {x}"), paragraphs(document).map { it.text })
        val arabic = rtf("""{\rtf1\ansi\ansicpg1256 \'d3\'e1\'c7\'e3\par}""")
        assertEquals(listOf("سلام"), paragraphs(arabic).map { it.text })
    }

    @Test
    fun `RTF leaves out what isn't text, and keeps a field's result`() {
        val document = rtf(
            """{\rtf1{\fonttbl{\f0 Arial;}}{\colortbl;\red0\green0\blue0;}{\*\generator Writer;}""" +
                """{\info{\title Secret}}Go to {\field{\*\fldinst HYPERLINK "http://x"}{\fldrslt the site}}.""" +
                """{\pict\pngblip 89504e47}\par}""",
        )
        assertEquals(listOf("Go to the site."), paragraphs(document).map { it.text })
    }

    @Test
    fun `RTF tables, list markers and headings from the stylesheet`() {
        val document = rtf(
            """{\rtf1{\stylesheet{\s0 Normal;}{\s1\b heading 1;}{\s2 Title;}}""" +
                """\pard\s2 Report\par\pard\s1 Part one\par""" +
                """\pard{\listtext\pard\plain 1.\tab}Item\par""" +
                """\trowd\cellx1000\cellx2000\pard\intbl A\cell B\cell\row\pard After\par}""",
        )
        val shown = paragraphs(document)
        assertEquals(listOf("Report", "Part one", "Item", "After"), shown.map { it.text })
        assertEquals(listOf(DocStyle.TITLE, DocStyle.HEADING1, DocStyle.BODY, DocStyle.BODY), shown.map { it.style })
        assertEquals("1.", shown[2].marker)
        val table = document.blocks.filterIsInstance<DocTable>().single()
        assertEquals(listOf(listOf("A", "B")), table.rows.map { row -> row.map { (it.blocks.single() as DocParagraph).text } })
    }
}
