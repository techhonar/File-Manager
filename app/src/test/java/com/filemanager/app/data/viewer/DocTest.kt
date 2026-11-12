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

    @Test
    fun `a doc that is plain text is shown as its lines`() {
        val file = File(temp.root, "README.doc").apply { writeText("Keymap notes\n\nUse AltGr for the third level.\n") }
        assertEquals(listOf("Keymap notes", "", "Use AltGr for the third level."), paragraphs(Doc.read(file)).map { it.text })
    }

    @Test(expected = IOException::class)
    fun `a doc that is a web page is not shown as its markup`() {
        Doc.read(File(temp.root, "export.doc").apply { writeText("<html><body><p>Report</p></body></html>") })
    }

    @Test(expected = IOException::class)
    fun `a file that is not a document at all fails to read`() {
        Doc.read(File(temp.root, "data.doc").apply { writeBytes(ByteArray(600) { (it * 7 % 256).toByte() }) })
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
    fun `RTF bytes are letters of the font in use - Cyrillic, Persian - not of the document's code page`() {
        val fonts = """{\fonttbl{\f0\fswiss\fcharset0 Arial;}{\f1\fswiss\fcharset204{\*\panose 020b0604020202020204}Arial Cyr;}{\f2\fnil\fcharset178 Tahoma;}}"""
        val russian = rtf("""{\rtf1\ansi\ansicpg1252\deff0""" + fonts + """\f1 \'cf\'f0\'e8\'e2\'e5\'f2 \f0 hello {\f2 \'d3\'e1\'c7\'e3} caf\'e9\par}""")
        assertEquals(listOf("Привет hello سلام café"), paragraphs(russian).map { it.text })
        // The document's usual font decides for text that names none.
        val usual = rtf("""{\rtf1\ansi\ansicpg1252\deff1""" + fonts + """\'cf\'f0\'e8\'e2\'e5\'f2\par\plain \'e4\'e0\par}""")
        assertEquals(listOf("Привет", "да"), paragraphs(usual).map { it.text })
    }

    @Test
    fun `RTF reads past a picture's raw bytes, and words deleted with changes tracked`() {
        // Five raw bytes after \bin5, braces and a backslash among them.
        val document = rtf("""{\rtf1 A{\pict\bin5 }{\}{}B {\deleted gone }kept\par}""")
        assertEquals(listOf("AB kept"), paragraphs(document).map { it.text })
        // A count far past the end of the file ends the reading, and nothing worse.
        val cut = rtf("""{\rtf1 Shown\par{\pict\bin2147483647 lost}""")
        assertEquals(listOf("Shown"), paragraphs(cut).map { it.text })
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
    // ---- Files made to break a reader ------------------------------------

    @Test
    fun `RTF of nothing but paragraph ends is cut short`() {
        val bs = Char(92)
        val document = rtf("{${bs}rtf1 " + "${bs}par ".repeat(Docx.MAX_PIECES + 10) + "}")
        assertTrue(document.truncated)
        assertEquals(Docx.MAX_PIECES, document.blocks.size)
    }

    @Test
    fun `RTF groups nested deeper than any document's are read as one`() {
        val bs = Char(92)
        val document = rtf("{${bs}rtf1 before " + "{".repeat(5_000) + "${bs}b deep" + "}".repeat(5_000) + " after${bs}par}")
        assertEquals(listOf("before deep after"), paragraphs(document).map { it.text })
        // Bold ended with the group it was set in, a thousand deep.
        assertEquals(listOf(false, true, false), paragraphs(document).single().spans.map { it.bold })
    }

    @Test
    fun `an RTF table row keeps as many cells as a table has columns, and as much as is shown at once`() {
        val bs = Char(92)
        val cells = rtf("{${bs}rtf1 ${bs}intbl " + "c${bs}cell ".repeat(Docx.MAX_COLUMNS + 40) + "${bs}row ${bs}pard after${bs}par}")
        val table = cells.blocks[0] as DocTable
        assertEquals(Docx.MAX_COLUMNS, table.rows.single().size)
        assertEquals("after", (cells.blocks[1] as DocParagraph).text)

        val crowded = rtf("{${bs}rtf1 ${bs}intbl " + "p${bs}par ".repeat(Docx.MAX_TOGETHER + 300) + "${bs}cell ${bs}row ${bs}pard after${bs}par}")
        val cell = (crowded.blocks[0] as DocTable).rows.single().single()
        assertEquals(Docx.MAX_TOGETHER, cell.blocks.size)
        assertEquals("after", (crowded.blocks[1] as DocParagraph).text)
    }

}
