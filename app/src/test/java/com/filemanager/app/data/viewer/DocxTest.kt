package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
        """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" """ +
        """xmlns:v="urn:schemas-microsoft-com:vml""""

    /** A .docx whose body is [body], with any of the other parts given. */
    private fun docx(
        body: String,
        styles: String? = null,
        numbering: String? = null,
        rels: String? = null,
    ): File {
        val file = File(temp.root, "test.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("word/document.xml", """<?xml version="1.0"?><w:document $ns><w:body>$body</w:body></w:document>""")
            styles?.let { put("word/styles.xml", """<?xml version="1.0"?><w:styles $ns>$it</w:styles>""") }
            numbering?.let { put("word/numbering.xml", """<?xml version="1.0"?><w:numbering $ns>$it</w:numbering>""") }
            rels?.let {
                put(
                    "word/_rels/document.xml.rels",
                    """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$it</Relationships>""",
                )
            }
        }
        return file
    }

    private fun paragraphs(file: File) = Docx.read(file).blocks.filterIsInstance<DocParagraph>()

    @Test
    fun `reads runs with their formatting and joins same-format runs`() {
        val body = """<w:p>
            <w:r><w:t xml:space="preserve">Plain </w:t></w:r>
            <w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r>
            <w:r><w:rPr><w:b w:val="0"/><w:i/></w:rPr><w:t xml:space="preserve"> italic</w:t></w:r>
            <w:r><w:rPr><w:i/></w:rPr><w:t xml:space="preserve"> more</w:t></w:r>
        </w:p>"""
        val spans = paragraphs(docx(body)).single().spans
        assertEquals(
            listOf(DocSpan("Plain "), DocSpan("bold", bold = true), DocSpan(" italic more", italic = true)),
            spans,
        )
    }

    @Test
    fun `headings come from the style name, not its translated id`() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="berschrift1"><w:name w:val="heading 1"/></w:style>
            <w:style w:type="paragraph" w:styleId="Titel"><w:name w:val="Title"/></w:style>
            <w:style w:type="paragraph" w:styleId="MyHeading"><w:name w:val="My heading"/><w:basedOn w:val="berschrift1"/></w:style>"""
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Titel"/></w:pPr><w:r><w:t>T</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="berschrift1"/></w:pPr><w:r><w:t>H</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="MyHeading"/></w:pPr><w:r><w:t>M</w:t></w:r></w:p>
            <w:p><w:r><w:t>B</w:t></w:r></w:p>"""
        val styled = paragraphs(docx(body, styles = styles)).map { it.text to it.style }
        assertEquals(
            listOf(
                "T" to DocStyle.TITLE,
                "H" to DocStyle.HEADING1,
                "M" to DocStyle.HEADING1,
                "B" to DocStyle.BODY,
            ),
            styled,
        )
    }

    @Test
    fun `a list level with no number gives no marker and no indent, as headings in an outline have`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
                <w:lvl w:ilvl="0"><w:numFmt w:val="none"/><w:lvlText w:val=""/></w:lvl>
                <w:lvl w:ilvl="1"><w:numFmt w:val="none"/><w:lvlText w:val=""/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>"""
        val body = """<w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Sub-heading</w:t></w:r></w:p>"""
        val heading = paragraphs(docx(body, numbering = numbering)).single()
        assertEquals(null, heading.marker)
        assertEquals(0, heading.level)
    }

    @Test
    fun `numbers list items per level and bullets unordered ones`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
                <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
                <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%1.%2)"/></w:lvl>
            </w:abstractNum>
            <w:abstractNum w:abstractNumId="1">
                <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val=""/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>"""
        fun item(num: Int, level: Int, text: String) =
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$num"/></w:numPr></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
        val body = item(1, 0, "one") + item(1, 1, "one-a") + item(1, 1, "one-b") +
            item(1, 0, "two") + item(1, 1, "two-a") + item(2, 0, "dot")
        val items = paragraphs(docx(body, numbering = numbering)).map { Triple(it.marker, it.level, it.text) }
        assertEquals(
            listOf(
                Triple("1.", 0, "one"),
                Triple("1.a)", 1, "one-a"),
                Triple("1.b)", 1, "one-b"),
                Triple("2.", 0, "two"),
                Triple("2.a)", 1, "two-a"),
                Triple("•", 0, "dot"),
            ),
            items,
        )
    }

    @Test
    fun `numbers paragraphs whose style is a list, unless they opt out`() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="ListNumber"><w:name w:val="List Number"/>
                <w:pPr><w:numPr><w:numId w:val="3"/></w:numPr></w:pPr></w:style>"""
        val numbering = """
            <w:abstractNum w:abstractNumId="7">
                <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="3"><w:abstractNumId w:val="7"/></w:num>"""
        fun item(text: String, own: String = "") =
            """<w:p><w:pPr><w:pStyle w:val="ListNumber"/>$own</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
        val body = item("first") + item("second") +
            item("plain", own = """<w:numPr><w:numId w:val="0"/></w:numPr>""")
        val markers = paragraphs(docx(body, styles = styles, numbering = numbering)).map { it.marker }
        assertEquals(listOf("1.", "2.", null), markers)
    }

    @Test
    fun `reads tables with spans and relative column widths`() {
        val body = """<w:tbl>
            <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="1000"/><w:gridCol w:w="1000"/></w:tblGrid>
            <w:tr>
                <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>wide</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>c</w:t></w:r></w:p></w:tc>
            </w:tr>
            <w:tr>
                <w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>c2</w:t></w:r></w:p></w:tc>
            </w:tr>
        </w:tbl>
        <w:p><w:r><w:t>after</w:t></w:r></w:p>"""
        val blocks = Docx.read(docx(body)).blocks
        val table = blocks[0] as DocTable
        assertEquals(listOf(2000f, 1000f, 1000f), table.columns)
        assertEquals(listOf(2, 1), table.rows[0].map { it.span })
        assertEquals(
            listOf(listOf("wide", "c"), listOf("a", "b", "c2")),
            table.rows.map { row -> row.map { (it.blocks.single() as DocParagraph).text } },
        )
        assertEquals("after", (blocks[1] as DocParagraph).text)
    }

    @Test
    fun `places pictures where they are and reads each only once`() {
        val rels = """
            <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
            <Relationship Id="rId6" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com" TargetMode="External"/>"""
        val body = """<w:p>
            <w:r><w:t>before</w:t></w:r>
            <w:r><mc:AlternateContent>
                <mc:Choice Requires="wps"><w:drawing><wp:inline>
                    <wp:extent cx="1828800" cy="914400"/>
                    <a:graphic><a:graphicData><a:blip r:embed="rId5"/></a:graphicData></a:graphic>
                </wp:inline></w:drawing></mc:Choice>
                <mc:Fallback><w:pict><v:shape><v:imagedata r:id="rId5"/></v:shape></w:pict></mc:Fallback>
            </mc:AlternateContent></w:r>
            <w:r><w:t>after</w:t></w:r>
        </w:p>"""
        val blocks = Docx.read(docx(body, rels = rels)).blocks
        assertEquals(3, blocks.size)
        assertEquals("before", (blocks[0] as DocParagraph).text)
        assertEquals(DocImage("word/media/image1.png", 1828800, 914400), blocks[1])
        assertEquals("after", (blocks[2] as DocParagraph).text)
    }

    @Test
    fun `leaves out deleted, hidden and superseded text`() {
        val body = """<w:p>
            <w:r><w:t xml:space="preserve">kept </w:t></w:r>
            <w:del w:id="1"><w:r><w:delText>gone </w:delText></w:r></w:del>
            <w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden </w:t></w:r>
            <w:r><w:rPr><w:b/><w:rPrChange w:id="2"><w:rPr><w:i/></w:rPr></w:rPrChange></w:rPr><w:t>bold</w:t></w:r>
        </w:p>"""
        val spans = paragraphs(docx(body)).single().spans
        assertEquals(listOf(DocSpan("kept "), DocSpan("bold", bold = true)), spans)
    }

    @Test
    fun `keeps empty paragraphs, breaks and tabs`() {
        val body = """<w:p/><w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t><w:br/><w:t>c</w:t></w:r></w:p>"""
        assertEquals(listOf("", "a\tb\nc"), paragraphs(docx(body)).map { it.text })
    }

    @Test
    fun `stops at the character limit and says so`() {
        val body = (1..50).joinToString("") { "<w:p><w:r><w:t>0123456789</w:t></w:r></w:p>" }
        val document = Docx.read(docx(body), maxChars = 95)
        assertTrue(document.truncated)
        assertEquals(9, document.blocks.size)
        assertFalse(Docx.read(docx(body)).truncated)
    }

    // ---- Files made to break a reader ------------------------------------

    @Test
    fun `a list level below none or past the deepest is brought within what lists have`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
                <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
                <w:lvl w:ilvl="2000000000"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>"""
        fun item(level: String) =
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        val levels = paragraphs(docx(item("-5") + item("99") + item("2000000000"), numbering = numbering)).map { it.level }
        // A negative indent is one the screen refuses to lay out at all.
        assertEquals(listOf(0, 8, 8), levels)
    }

    @Test
    fun `a cell said to span two thousand million columns spans what a table has`() {
        val body = """<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2000000000"/></w:tcPr><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc>""" +
            """<w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""
        val table = Docx.read(docx(body)).blocks.single() as DocTable
        assertEquals(listOf(Docx.MAX_COLUMNS, 1), table.rows.single().map { it.span })
        assertEquals(Docx.MAX_COLUMNS + 1, table.columns.size)
    }

    @Test
    fun `tables nested deeper than is shown are read as the text of the cell they are in`() {
        val deep = 9
        val body = (1..deep).joinToString("") { "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>level $it</w:t></w:r></w:p>" } +
            "</w:tc></w:tr></w:tbl>".repeat(deep) + "<w:p><w:r><w:t>after</w:t></w:r></w:p>"
        val blocks = Docx.read(docx(body)).blocks
        var depth = 0
        val texts = ArrayList<String>()
        fun walk(list: List<DocBlock>, at: Int) {
            for (block in list) {
                when (block) {
                    is DocParagraph -> texts += block.text
                    is DocTable -> {
                        depth = maxOf(depth, at + 1)
                        block.rows.forEach { row -> row.forEach { walk(it.blocks, at + 1) } }
                    }
                    else -> Unit
                }
            }
        }
        walk(blocks, 0)
        assertEquals(Docx.MAX_TABLE_DEPTH, depth)
        // Nothing lost: only the tables around the deepest text.
        assertEquals((1..deep).map { "level $it" } + "after", texts)
    }

    @Test
    fun `a table row holding more than is shown at once keeps that much, and what comes after it`() {
        val body = "<w:tbl><w:tr><w:tc>" + "<w:p><w:r><w:t>a</w:t></w:r></w:p>".repeat(Docx.MAX_TOGETHER + 200) +
            "</w:tc>" + "<w:tc><w:p/></w:tc>".repeat(100) + "</w:tr><w:tr><w:tc><w:p><w:r><w:t>next row</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:r><w:t>after</w:t></w:r></w:p>"
        val document = Docx.read(docx(body))
        val table = document.blocks[0] as DocTable
        assertTrue(document.truncated)
        assertTrue(table.rows[0].sumOf { it.blocks.size + 1 } <= Docx.MAX_TOGETHER)
        assertEquals("next row", (table.rows[1].single().blocks.single() as DocParagraph).text)
        assertEquals("after", (document.blocks[1] as DocParagraph).text)
    }

    @Test
    fun `a row of more cells than a table has columns keeps as many as that`() {
        val body = "<w:tbl><w:tr>" + "<w:tc><w:p><w:r><w:t>c</w:t></w:r></w:p></w:tc>".repeat(Docx.MAX_COLUMNS + 50) + "</w:tr></w:tbl>"
        val table = Docx.read(docx(body)).blocks.single() as DocTable
        assertEquals(Docx.MAX_COLUMNS, table.rows.single().size)
    }

    @Test
    fun `a document of nothing but empty paragraphs is cut short`() {
        val document = Docx.read(docx("<w:p/>".repeat(Docx.MAX_PIECES + 10)))
        assertTrue(document.truncated)
        assertEquals(Docx.MAX_PIECES, document.blocks.size)
    }

    /** A .docx whose main part is [xml], whole. */
    private fun docxOf(xml: String): File {
        val file = File(temp.root, "raw.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `a part that declares a document type is not read`() {
        // Each name stands for the one before it sixteen times over: a few
        // lines that come to gigabytes where they are used.
        val entities = (1..8).joinToString("") { n ->
            val before = if (n == 1) "a".repeat(64) else "&e${n - 1};".repeat(16)
            """<!ENTITY e$n "$before">"""
        }
        val growing = """<?xml version="1.0"?><!DOCTYPE d [$entities]><w:document $ns><w:body><w:p><w:pPr><w:pStyle w:val="&e8;"/></w:pPr></w:p></w:body></w:document>"""
        assertTrue(runCatching { Docx.read(docxOf(growing)) }.exceptionOrNull() is org.xml.sax.SAXException)
        // Nor one that defines nothing: it is the declaring that is refused, before anything is defined.
        val idle = """<?xml version="1.0"?><!DOCTYPE d><w:document $ns><w:body><w:p><w:r><w:t>x</w:t></w:r></w:p></w:body></w:document>"""
        val refused = runCatching { Docx.read(docxOf(idle)) }.exceptionOrNull()
        assertTrue(refused is org.xml.sax.SAXException)
        assertEquals("A document type in an Office part", refused?.message)
    }

    @Test
    fun `a file that unpacks to more than is read is shown as far as it was read`() {
        val file = File(temp.root, "padded.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<?xml version="1.0"?><w:document $ns><w:body><w:p><w:r><w:t>before</w:t></w:r></w:p>""".toByteArray())
            // A few kilobytes zipped, more than is read unpacked.
            val spaces = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat((MAX_UNPACKED_BYTES shr 20).toInt() + 1) { zip.write(spaces) }
            zip.write("""<w:p><w:r><w:t>after</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        assertTrue(file.length() < 1 shl 20)
        val document = Docx.read(file)
        assertTrue(document.truncated)
        assertEquals(listOf("before"), document.blocks.map { (it as DocParagraph).text })
    }

    @Test(expected = org.xml.sax.SAXException::class)
    fun `a part nested deeper than any real one is not read`() {
        Docx.read(docx("<w:p>".repeat(1_100) + "</w:p>".repeat(1_100)))
    }

    @Test
    fun `no more is read of a part than any real part holds`() {
        val input = BoundedInput(ByteArray(100).inputStream(), limit = 60)
        assertEquals(50, input.read(ByteArray(50)))
        val failed = runCatching { input.read(ByteArray(50)) }.exceptionOrNull()
        assertTrue(failed is java.io.IOException)
    }

    @Test
    fun `formats numbers the way Word does`() {
        assertEquals("iv", formatNumber(4, "lowerRoman"))
        assertEquals("XIV", formatNumber(14, "upperRoman"))
        assertEquals("aa", formatNumber(27, "lowerLetter"))
        assertEquals("C", formatNumber(3, "upperLetter"))
        assertEquals("07", formatNumber(7, "decimalZero"))
        assertEquals("12", formatNumber(12, "chineseCounting"))
    }

    @Test
    fun `resolves picture paths relative to the word folder`() {
        assertEquals("word/media/a.png", resolveTarget("media/a.png"))
        assertEquals("media/b.png", resolveTarget("../media/b.png"))
        assertEquals("word/media/c.png", resolveTarget("/word/media/c.png"))
    }
}
