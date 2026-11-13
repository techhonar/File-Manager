package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptxTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ns = """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" """ +
        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" """ +
        """xmlns:dgm="http://schemas.openxmlformats.org/drawingml/2006/diagram""""
    private val types = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val packageRels = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** A slide's XML around [shapes]. */
    private fun slide(shapes: String, show: Boolean = true) =
        """<?xml version="1.0"?><p:sld $ns${if (show) "" else " show=\"0\""}><p:cSld><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            shapes + "</p:spTree></p:cSld></p:sld>"

    /** A text shape: a placeholder of [type] if given ("" for one that doesn't say), else a text box. */
    private fun shape(paragraphs: String, type: String? = null, lists: String = ""): String {
        val ph = when (type) {
            null -> ""
            "" -> """<p:ph idx="1"/>"""
            else -> """<p:ph type="$type"/>"""
        }
        return """<p:sp><p:nvSpPr><p:cNvPr id="2" name="s"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr/>""" +
            """<p:txBody><a:bodyPr/><a:lstStyle>$lists</a:lstStyle>$paragraphs</p:txBody></p:sp>"""
    }

    private fun para(text: String, props: String = "") = """<a:p>$props<a:r><a:rPr lang="en-US"/><a:t>$text</a:t></a:r></a:p>"""

    /**
     * A .pptx whose slides are [slides] in the presentation's order - stored
     * under the names given, which need not be in order - with any other parts.
     */
    private fun pptx(slides: List<Pair<String, String>>, parts: Map<String, String> = emptyMap()): File {
        val file = File(temp.root, "deck${System.nanoTime()}.pptx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("_rels/.rels", """<Relationships xmlns="$packageRels"><Relationship Id="rId1" Type="$types/officeDocument" Target="ppt/presentation.xml"/></Relationships>""")
            val ids = slides.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${it + 10}"/>""" }
            put("ppt/presentation.xml", """<?xml version="1.0"?><p:presentation $ns><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""")
            val rels = slides.withIndex().joinToString("") { (i, s) -> """<Relationship Id="rId${i + 10}" Type="$types/slide" Target="slides/${s.first}"/>""" }
            put("ppt/_rels/presentation.xml.rels", """<Relationships xmlns="$packageRels"><Relationship Id="rId1" Type="$types/slideMaster" Target="slideMasters/slideMaster1.xml"/>$rels</Relationships>""")
            for ((name, xml) in slides) put("ppt/slides/$name", xml)
            for ((name, text) in parts) put(name, text)
        }
        return file
    }

    private fun slideRels(vararg links: Pair<String, String>) =
        """<Relationships xmlns="$packageRels">""" + links.withIndex().joinToString("") { (i, link) ->
            """<Relationship Id="rId${i + 2}" Type="$types/${link.first}" Target="${link.second}"/>"""
        } + "</Relationships>"

    private fun PptxSlide.paragraphs() = blocks.filterIsInstance<DocParagraph>()

    @Test
    fun `slides come in the presentation's order, the title first on each`() {
        val first = slide(shape(para("Body text"), type = "") + shape(para("Agenda"), type = "title"))
        val second = slide(shape(para("Hello"), type = "ctrTitle") + shape(para("World"), type = "subTitle"), show = false)
        val deck = Pptx.read(pptx(listOf("slide9.xml" to first, "slide1.xml" to second)))
        assertEquals(2, deck.total)
        assertFalse(deck.truncated)
        val (one, two) = deck.slides
        assertEquals(1, one.number)
        assertEquals(listOf("Agenda", "Body text"), one.paragraphs().map { it.text })
        assertEquals(DocStyle.HEADING1, one.paragraphs()[0].style)
        assertFalse(one.hidden)
        assertEquals(listOf(DocStyle.TITLE, DocStyle.SUBTITLE), two.paragraphs().map { it.style })
        assertTrue(two.hidden)
    }

    @Test
    fun `body text has bullets by level, numbers count on, and text boxes have none`() {
        val body = para("One") +
            para("Sub", """<a:pPr lvl="1"/>""") +
            para("Plain", """<a:pPr><a:buNone/></a:pPr>""") +
            para("First", """<a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr>""") +
            para("Second", """<a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr>""") +
            para("Tick", """<a:pPr><a:buFont typeface="Wingdings"/><a:buChar char="ü"/></a:pPr>""") +
            para("Arrow", """<a:pPr><a:buChar char="➤"/></a:pPr>""") +
            // LibreOffice's own symbol font, and a character of a font's own.
            para("Star", """<a:pPr><a:buFont typeface="starbats" charset="2"/><a:buChar char="&#xF095;"/></a:pPr>""") +
            para("Private", """<a:pPr><a:buFont typeface="Arial"/><a:buChar char="&#xE00C;"/></a:pPr>""") +
            "<a:p><a:endParaRPr/></a:p>"
        val box = para("Boxed") + para("Lettered", """<a:pPr><a:buAutoNum type="alphaLcParenR" startAt="3"/></a:pPr>""")
        val deck = Pptx.read(pptx(listOf("slide1.xml" to slide(shape(body, type = "") + shape(box)))))
        val shown = deck.slides.single().paragraphs().map { Triple(it.text, it.marker, it.level) }
        assertEquals(
            listOf(
                Triple("One", "•", 0),
                Triple("Sub", "◦", 1),
                Triple("Plain", null, 0),
                Triple("First", "1.", 0),
                Triple("Second", "2.", 0),
                Triple("Tick", "✓", 0),
                Triple("Arrow", "➤", 0),
                Triple("Star", "•", 0),
                Triple("Private", "•", 0),
                Triple("Boxed", null, 0),
                Triple("Lettered", "c)", 0),
            ),
            shown,
        )
    }

    @Test
    fun `right-to-left paragraphs say so, and left and right are the slide's sides`() {
        val body = para("سلام", """<a:pPr algn="r" rtl="1"/>""") + para("Plain") + para("Left", """<a:pPr algn="l"/>""")
        val lists = """<a:lvl1pPr rtl="1"/>"""
        val deck = Pptx.read(pptx(listOf("slide1.xml" to slide(shape(body, type = "") + shape(para("از قالب"), type = "body", lists = lists)))))
        val shown = deck.slides.single().paragraphs()
        assertEquals(listOf(DocAlign.RIGHT, DocAlign.START, DocAlign.LEFT, DocAlign.START), shown.map { it.align })
        // Said by the paragraph, by the shape's list style, or left to the text itself.
        assertEquals(listOf(true, null, null, true), shown.map { it.rtl })
    }

    @Test
    fun `a shape's own list style turns its bullets off`() {
        val lists = """<a:lvl1pPr><a:buNone/></a:lvl1pPr>"""
        val deck = Pptx.read(pptx(listOf("slide1.xml" to slide(shape(para("Quiet"), type = "body", lists = lists)))))
        assertNull(deck.slides.single().paragraphs().single().marker)
    }

    @Test
    fun `runs keep their formatting, breaks become new lines, and page furniture is left out`() {
        val text = """<a:p><a:r><a:rPr b="1"/><a:t>Bold</a:t></a:r><a:r><a:rPr b="0" i="1" u="sng"/><a:t> and</a:t></a:r><a:br/><a:r><a:rPr strike="sngStrike"/><a:t>struck</a:t></a:r></a:p>"""
        val number = """<a:p><a:fld id="{1}" type="slidenum"><a:t>‹#›</a:t></a:fld></a:p>"""
        val page = """<a:p><a:r><a:t>Page </a:t></a:r><a:fld id="{2}" type="slidenum"><a:t>‹#›</a:t></a:fld></a:p>"""
        val deck = Pptx.read(pptx(listOf("slide1.xml" to slide(shape(text) + shape(number, type = "sldNum") + shape(page)))))
        val spans = deck.slides.single().paragraphs().first().spans
        assertEquals(
            listOf(
                DocSpan("Bold", bold = true),
                // A line break goes with the run before it.
                DocSpan(" and\n", italic = true, underline = true),
                DocSpan("struck", strike = true),
            ),
            spans,
        )
        // The slide number placeholder is gone, and a slide number field in text is skipped.
        assertEquals(listOf("Bold and\nstruck", "Page "), deck.slides.single().paragraphs().map { it.text })
    }

    @Test
    fun `pictures, tables and what newer PowerPoint writes twice`() {
        val picture = """<p:pic><p:nvPicPr><p:cNvPr id="4" name="Picture"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>""" +
            """<p:blipFill><a:blip r:embed="rId2"><a:extLst><a:ext uri="{28A0092B}"/></a:extLst></a:blip></p:blipFill>""" +
            """<p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="457200"/></a:xfrm></p:spPr></p:pic>"""
        val table = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="5" name="Table"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>""" +
            """<p:xfrm><a:off x="0" y="0"/><a:ext cx="4000000" cy="1000000"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>""" +
            """<a:tblGrid><a:gridCol w="100"/><a:gridCol w="300"/></a:tblGrid>""" +
            """<a:tr h="10"><a:tc><a:txBody><a:bodyPr/>${para("A")}</a:txBody></a:tc><a:tc><a:txBody><a:bodyPr/>${para("B")}</a:txBody></a:tc></a:tr>""" +
            """<a:tr h="10"><a:tc gridSpan="2"><a:txBody><a:bodyPr/>${para("Both")}</a:txBody></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:p/></a:txBody></a:tc></a:tr>""" +
            """</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""
        val twice = """<mc:AlternateContent><mc:Choice Requires="p14">${shape(para("Choice"))}</mc:Choice><mc:Fallback>${shape(para("Fallback"))}</mc:Fallback></mc:AlternateContent>"""
        val deck = Pptx.read(
            pptx(
                listOf("slide1.xml" to slide(picture + table + twice)),
                mapOf("ppt/slides/_rels/slide1.xml.rels" to slideRels("image" to "../media/image1.png")),
            ),
        )
        val blocks = deck.slides.single().blocks
        assertEquals(DocImage("ppt/media/image1.png", 914400, 457200), blocks[0])
        val grid = blocks[1] as DocTable
        assertEquals(listOf(100f, 300f), grid.columns)
        assertEquals(listOf(listOf(1, 1), listOf(2)), grid.rows.map { row -> row.map { it.span } })
        assertEquals("Both", (grid.rows[1][0].blocks.single() as DocParagraph).text)
        // Table text has no bullets, whatever the shape.
        assertNull((grid.rows[0][0].blocks.single() as DocParagraph).marker)
        assertEquals(listOf("Choice"), blocks.drop(2).filterIsInstance<DocParagraph>().map { it.text })
    }

    @Test
    fun `speaker notes, charts and SmartArt text`() {
        val chart = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="Chart"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm/>""" +
            """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId9"/></a:graphicData></a:graphic></p:graphicFrame>"""
        val smartArt = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="7" name="Diagram"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm/>""" +
            """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/diagram"><dgm:relIds r:dm="rId3" r:lo="rId8" r:qs="rId8" r:cs="rId8"/></a:graphicData></a:graphic></p:graphicFrame>"""
        val data = """<?xml version="1.0"?><dgm:dataModel $ns><dgm:ptLst>""" +
            """<dgm:pt modelId="0" type="doc"><dgm:prSet/><dgm:spPr/><dgm:t><a:bodyPr/><a:p><a:endParaRPr/></a:p></dgm:t></dgm:pt>""" +
            """<dgm:pt modelId="1"><dgm:prSet/><dgm:spPr/><dgm:t><a:bodyPr/>${para("Alpha")}</dgm:t></dgm:pt>""" +
            """<dgm:pt modelId="2" type="parTrans"><dgm:prSet/><dgm:spPr/><dgm:t><a:bodyPr/>${para("Joint")}</dgm:t></dgm:pt>""" +
            """<dgm:pt modelId="3" type="node"><dgm:prSet/><dgm:spPr/><dgm:t><a:bodyPr/>${para("Beta")}</dgm:t></dgm:pt>""" +
            """</dgm:ptLst></dgm:dataModel>"""
        val notes = """<?xml version="1.0"?><p:notes $ns><p:cSld><p:spTree>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="i"/><p:cNvSpPr/><p:nvPr><p:ph type="sldImg"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>""" +
            shape(para("Say hello first"), type = "body") +
            // Notes LibreOffice writes in a text box rather than the placeholder.
            shape(para("Then the numbers")) +
            shape(para("3"), type = "sldNum") +
            "</p:spTree></p:cSld></p:notes>"
        val deck = Pptx.read(
            pptx(
                listOf("slide1.xml" to slide(shape(para("Results"), type = "title") + chart + smartArt)),
                mapOf(
                    "ppt/slides/_rels/slide1.xml.rels" to slideRels("notesSlide" to "../notesSlides/notesSlide1.xml", "diagramData" to "../diagrams/data1.xml"),
                    "ppt/notesSlides/notesSlide1.xml" to notes,
                    "ppt/diagrams/data1.xml" to data,
                ),
            ),
        )
        val slide = deck.slides.single()
        assertEquals(
            listOf("Results", "[Chart that can't be shown here]", "Alpha", "Beta"),
            slide.paragraphs().map { it.text },
        )
        assertEquals(listOf(null, null, "•", "•"), slide.paragraphs().map { it.marker })
        assertEquals(listOf("Say hello first", "Then the numbers"), slide.notes.map { it.text })
        assertTrue(slide.notes.all { it.marker == null })
    }

    @Test
    fun `a long presentation stops at the most text and says so`() {
        val slides = (1..5).map { "slide$it.xml" to slide(shape(para("x".repeat(40)), type = "")) }
        val deck = Pptx.read(pptx(slides), maxChars = 100)
        assertTrue(deck.truncated)
        assertEquals(5, deck.total)
        assertTrue(deck.slides.size < 5)
    }

    // ---- What a slide is built on ----------------------------------------

    /** A placeholder of [type] - none for one that doesn't say - numbered [idx], with its own list style if given. */
    private fun placeholder(type: String?, idx: Int?, paragraphs: String, lists: String = ""): String {
        val ph = "<p:ph" + (type?.let { """ type="$it"""" } ?: "") + (idx?.let { """ idx="$it"""" } ?: "") + "/>"
        return """<p:sp><p:nvSpPr><p:cNvPr id="2" name="s"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr/>""" +
            """<p:txBody><a:bodyPr/><a:lstStyle>$lists</a:lstStyle>$paragraphs</p:txBody></p:sp>"""
    }

    /** A master whose body text is bulleted - a dot, a dash, then a square from a font of pictures - and reads [direction]. */
    private fun master(direction: String = "") = """<?xml version="1.0"?><p:sldMaster $ns><p:cSld><p:spTree>""" +
        placeholder("title", null, "<a:p/>") + placeholder("body", 1, "<a:p/>") +
        """</p:spTree></p:cSld><p:txStyles><p:titleStyle><a:lvl1pPr><a:buNone/></a:lvl1pPr></p:titleStyle><p:bodyStyle>""" +
        """<a:lvl1pPr$direction><a:buFont typeface="Arial"/><a:buChar char="•"/></a:lvl1pPr>""" +
        """<a:lvl2pPr$direction><a:buFont typeface="Arial"/><a:buChar char="–"/></a:lvl2pPr>""" +
        """<a:lvl3pPr$direction><a:buFont typeface="Wingdings"/><a:buChar char="§"/></a:lvl3pPr>""" +
        """</p:bodyStyle><p:otherStyle><a:lvl1pPr/></p:otherStyle></p:txStyles></p:sldMaster>"""

    /** A layout like PowerPoint's Comparison: a heading with no bullets, content with the master's, and a numbered list. */
    private val layout = """<?xml version="1.0"?><p:sldLayout $ns><p:cSld><p:spTree>""" +
        placeholder("title", null, "<a:p/>") +
        placeholder("body", 1, "<a:p/>", """<a:lvl1pPr><a:buNone/></a:lvl1pPr><a:lvl2pPr><a:buNone/></a:lvl2pPr>""") +
        placeholder(null, 2, "<a:p/>") +
        placeholder("body", 3, "<a:p/>", """<a:lvl1pPr><a:buAutoNum type="arabicPeriod"/></a:lvl1pPr>""") +
        """</p:spTree></p:cSld></p:sldLayout>"""

    /** A deck of one slide of [shapes], built on [layout] and a [master]. */
    private fun built(shapes: String, master: String? = master()) = Pptx.read(
        pptx(
            listOf("slide1.xml" to slide(shapes)),
            listOfNotNull(
                "ppt/slides/_rels/slide1.xml.rels" to slideRels("slideLayout" to "../slideLayouts/slideLayout1.xml"),
                "ppt/slideLayouts/slideLayout1.xml" to layout,
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to slideRels("slideMaster" to "../slideMasters/slideMaster1.xml"),
                master?.let { "ppt/slideMasters/slideMaster1.xml" to it },
            ).toMap(),
        ),
    ).slides.single()

    private fun levels(vararg texts: String) = texts.withIndex().joinToString("") { (level, text) -> para(text, """<a:pPr lvl="$level"/>""") }

    @Test
    fun `a layout takes the bullets off text its master would bullet`() {
        val slide = built(
            placeholder("title", null, para("Comparison")) +
                placeholder("body", 1, levels("Heading", "Under it")) +
                placeholder(null, 2, levels("Point", "Finer point", "Finest")) +
                placeholder("body", 3, para("First") + para("Second")),
        )
        assertEquals(
            listOf(
                null to "Comparison",
                // "• Heading" before: every line of body text had a bullet.
                null to "Heading", null to "Under it",
                // The master's own, a level each - the last a picture from Wingdings, shown as the square it is.
                "•" to "Point", "–" to "Finer point", "▪" to "Finest",
                "1." to "First", "2." to "Second",
            ),
            slide.paragraphs().map { it.marker to it.text },
        )
    }

    @Test
    fun `a paragraph's own bullet, and its shape's, come before the layout's`() {
        val slide = built(
            placeholder("body", 1, para("Ticked", """<a:pPr><a:buChar char="✓"/></a:pPr>""") + para("Plain")) +
                placeholder(null, 2, para("None here") + para("Nor here"), """<a:lvl1pPr><a:buNone/></a:lvl1pPr>"""),
        )
        assertEquals(listOf("✓", null, null, null), slide.paragraphs().map { it.marker })
    }

    @Test
    fun `a placeholder the layout has no counterpart for is built on the master`() {
        // Numbered 7, which the layout has nothing at: found by what it is, then by the master.
        val slide = built(placeholder(null, 7, para("Point")) + placeholder("pic", 9, para("Caption")) + shape(para("A text box")))
        assertEquals(listOf("•", "•", null), slide.paragraphs().map { it.marker })
    }

    @Test
    fun `a slide whose layout or master can't be read is read without it`() {
        // No master: the layout still takes bullets off, and gives none.
        val noMaster = built(placeholder("body", 1, para("Heading")) + placeholder(null, 2, para("Point")), master = null)
        assertEquals(listOf(null, null), noMaster.paragraphs().map { it.marker })
        // A layout that isn't there: body text is bulleted, as almost every master has it.
        val noLayout = Pptx.read(
            pptx(
                listOf("slide1.xml" to slide(placeholder("body", 1, levels("Point", "Finer")))),
                mapOf("ppt/slides/_rels/slide1.xml.rels" to slideRels("slideLayout" to "../slideLayouts/gone.xml")),
            ),
        ).slides.single()
        assertEquals(listOf("•", "◦"), noLayout.paragraphs().map { it.marker })
    }

    @Test
    fun `a master made to read right to left makes its text so, and one that says left to right leaves it to the text`() {
        val persian = built(placeholder(null, 2, para("نکته")), master = master(""" rtl="1""""))
        assertEquals(true, persian.paragraphs().single().rtl)
        val english = built(placeholder(null, 2, para("نکته")), master = master(""" rtl="0""""))
        assertNull(english.paragraphs().single().rtl)
        // The paragraph's own word is the last one.
        val own = built(placeholder(null, 2, para("Point", """<a:pPr rtl="0"/>""")), master = master(""" rtl="1""""))
        assertEquals(false, own.paragraphs().single().rtl)
    }

    // ---- Files made to break a reader ------------------------------------

    private fun table(rows: String) =
        """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="5" name="Table"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm/>""" +
            """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>$rows</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""

    @Test
    fun `a table cell said to span two thousand million columns spans what a table has`() {
        val rows = """<a:tr h="10"><a:tc gridSpan="2000000000"><a:txBody><a:bodyPr/>${para("A")}</a:txBody></a:tc></a:tr>"""
        val grid = Pptx.read(pptx(listOf("slide1.xml" to slide(table(rows))))).slides.single().blocks.single() as DocTable
        assertEquals(256, grid.rows.single().single().span)
        assertEquals(256, grid.columns.size)
    }

    @Test
    fun `a slide holding more than is shown at once keeps that much, and the slides after it`() {
        val row = "<a:tr h=\"10\">" + """<a:tc><a:txBody><a:bodyPr/>${para("c")}</a:txBody></a:tc>""".repeat(20) + "</a:tr>"
        val deck = Pptx.read(
            pptx(
                listOf(
                    "slide1.xml" to slide(table(row.repeat(400)) + shape(para("Under the table"))),
                    "slide2.xml" to slide(shape(para("Next slide"))),
                ),
            ),
        )
        assertTrue(deck.truncated)
        val grid = deck.slides[0].blocks.filterIsInstance<DocTable>().single()
        // Each cell is one and holds one paragraph.
        assertTrue(grid.rows.size < 400)
        assertTrue(grid.rows.sumOf { it.size * 2 + 1 } <= Docx.MAX_TOGETHER + 41)
        assertEquals(listOf("Next slide"), deck.slides[1].paragraphs().map { it.text })
    }

    @Test
    fun `numbered bullets in each of PowerPoint's schemes`() {
        assertEquals("1.", autoNumber("arabicPeriod", 1))
        assertEquals("(2)", autoNumber("arabicParenBoth", 2))
        assertEquals("3)", autoNumber("arabicParenR", 3))
        assertEquals("4", autoNumber("arabicPlain", 4))
        assertEquals("b.", autoNumber("alphaLcPeriod", 2))
        assertEquals("C)", autoNumber("alphaUcParenR", 3))
        assertEquals("iv.", autoNumber("romanLcPeriod", 4))
        assertEquals("IX.", autoNumber("romanUcPeriod", 9))
    }

    @Test(expected = java.io.IOException::class)
    fun `a zip that isn't a presentation fails to read`() {
        val file = File(temp.root, "not.pptx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        Pptx.read(file)
    }
}
