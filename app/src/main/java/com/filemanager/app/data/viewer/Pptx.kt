package com.filemanager.app.data.viewer

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/*
 * Reading a PowerPoint presentation (.pptx) for the viewer.
 *
 * ppt/presentation.xml lists the slides in order. Each slide is a part of
 * its own, and its .rels says where its pictures and speaker notes are. What
 * comes out is each slide's content in reading order - its title, text,
 * pictures and tables - the way the Word viewer reads a document, rather
 * than the slide's design: positions, colours, themes and animations are
 * left to PowerPoint.
 */

/** One slide: what is on it, in reading order, and the speaker's notes. */
class PptxSlide(
    val number: Int,
    val blocks: List<DocBlock>,
    val notes: List<DocParagraph>,
    /** Left out of the slide show, though still in the file. */
    val hidden: Boolean,
)

/** [total] is how many slides the file has, more than [slides] when it is too long to show all. */
class PptxDeck(val slides: List<PptxSlide>, val total: Int, val truncated: Boolean)

object Pptx {

    /** Stop after this many characters, as for a Word document. */
    const val MAX_CHARS = 2_000_000

    const val MAX_SLIDES = 2_000

    fun read(file: File, maxChars: Int = MAX_CHARS): PptxDeck = ZipFile(file).use { zip ->
        val parts = OoxmlParts(zip)
        val main = parts.mainPart("ppt/presentation.xml")
        val presentation = PresentationHandler()
        if (!parts.parse(main, presentation)) throw IOException("Not a PowerPoint presentation")
        val links = parts.links(main)
        val slideParts = presentation.slides.mapNotNull { id -> links[id]?.takeIf { it.kind == "slide" }?.target }
        val budget = TextBudget(maxChars)
        val slides = ArrayList<PptxSlide>()
        for ((index, part) in slideParts.withIndex()) {
            if (index >= MAX_SLIDES || budget.spent) break
            val slide = readSlide(parts, part, index + 1, budget)
            // A slide cut short before anything on it was read goes with the rest left out.
            if (budget.spent && slide.blocks.isEmpty()) break
            slides += slide
        }
        PptxDeck(slides, total = slideParts.size, truncated = budget.spent || slides.size < slideParts.size)
    }

    private fun readSlide(parts: OoxmlParts, part: String, number: Int, budget: TextBudget): PptxSlide {
        val links = parts.links(part)
        val slide = SlideHandler(links, budget, notes = false)
        parse(parts, part, slide, budget)
        // The title first, wherever the slide keeps it; the rest as it comes.
        val blocks = slide.pieces.sortedByDescending { it.title }.flatMap { piece ->
            piece.blocks + piece.diagram?.let { readDiagram(parts, it, budget) }.orEmpty()
        }
        val notes = links.values.firstOrNull { it.kind == "notesSlide" }?.let { link ->
            val page = SlideHandler(emptyMap(), budget, notes = true)
            parse(parts, link.target, page, budget)
            page.pieces.flatMap { it.blocks }.filterIsInstance<DocParagraph>()
        }.orEmpty()
        return PptxSlide(number, blocks, notes, slide.hidden)
    }

    /** SmartArt keeps its text in a part of its own: each box's text, as a list. */
    private fun readDiagram(parts: OoxmlParts, part: String, budget: TextBudget): List<DocBlock> {
        val handler = DiagramHandler(budget)
        parse(parts, part, handler, budget)
        return handler.paragraphs
    }

    private fun parse(parts: OoxmlParts, part: String, handler: OoxmlHandler, budget: TextBudget) {
        if (budget.spent) return
        try {
            parts.parse(part, handler)
        } catch (e: SAXException) {
            if (!budget.spent) throw e
        }
    }
}

/** The characters still to be read, shared by every part of the file. */
private class TextBudget(private val max: Int) {
    private var used = 0
    var spent = false
        private set

    fun spend(chars: Int) {
        used += chars
        if (used > max) {
            spent = true
            throw ReadEnough()
        }
    }
}

private class PresentationHandler : OoxmlHandler() {
    /** The slides' relationship ids, in the presentation's order. */
    val slides = ArrayList<String>()

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (localName == "sldId") attributes.relationship("id")?.let { slides += it }
    }
}

private const val MARKUP_COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006"

/** A placeholder's own bullets come from the slide master: on for body text. */
private val BULLETED = setOf("body", "obj")

/** What every slide repeats at its foot: page number, date, footer. */
private val FURNITURE = setOf("sldNum", "dt", "ftr", "hdr")

/** Fonts of pictures rather than letters, whose bullet characters show as some other letter in any other font. */
private val SYMBOL_FONTS = setOf("symbol", "webdings", "starbats", "starsymbol", "opensymbol", "marlett", "zapfdingbats", "mt extra")

private val BULLETS = listOf("•", "◦", "▪")

private class TextRun {
    var bold = false
    var italic = false
    var underline = false
    var strike = false

    fun read(attributes: Attributes) {
        bold = attributes.attr("b").isTrue()
        italic = attributes.attr("i").isTrue()
        underline = attributes.attr("u").let { it != null && it != "none" }
        strike = attributes.attr("strike").let { it != null && it != "noStrike" }
    }

    fun span(text: String) = DocSpan(text, bold, italic, underline, strike)
}

/** Adds [text] to [spans], joining it to the last span when the formatting is the same. */
private fun addText(spans: MutableList<DocSpan>, text: String, run: TextRun) {
    val last = spans.lastOrNull()
    val span = run.span(text)
    if (last != null && last.copy(text = "") == span.copy(text = "")) {
        spans[spans.size - 1] = last.copy(text = last.text + text)
    } else {
        spans += span
    }
}

private class SlideHandler(
    private val links: Map<String, OoxmlLink>,
    private val budget: TextBudget,
    /** A notes page: only the notes, not the slide's picture or page number. */
    private val notes: Boolean,
) : OoxmlHandler() {

    /** What one shape adds to the slide. */
    class Piece(val title: Boolean, val blocks: List<DocBlock>, val diagram: String?)

    private sealed interface Bullet {
        data object None : Bullet
        data class Glyph(val text: String) : Bullet
        data class Number(val scheme: String, val start: Int) : Bullet
    }

    private class Shape {
        /** The placeholder's type - "title", "body" - or "obj" for one that doesn't say; null if not a placeholder. */
        var placeholder: String? = null
        /** Right to left or not, as the shape's own list style says, by level. */
        val levelRtl = arrayOfNulls<Boolean>(9)
        var picture: String? = null
        var width = 0L
        var height = 0L
        var chart = false
        var diagram: String? = null
        val blocks = ArrayList<DocBlock>()
        /** Bullets the shape's own list style sets, by level. */
        val levelBullets = arrayOfNulls<Bullet>(9)
        val counts = IntArray(9)
    }

    private class Paragraph {
        var level = 0
        var align = DocAlign.START
        var rtl: Boolean? = null
        var bullet: Bullet? = null
        val spans = ArrayList<DocSpan>()
    }

    private enum class CellKind { NORMAL, EMPTY, SKIPPED }

    private class Cell(val span: Int, val kind: CellKind) {
        val blocks = ArrayList<DocBlock>()
    }

    private class Table {
        val columns = ArrayList<Float>()
        val rows = ArrayList<MutableList<DocCell>>()
    }

    val pieces = ArrayList<Piece>()
    var hidden = false
        private set

    private val path = ArrayList<String>()
    private val shapes = ArrayList<Shape>()
    private var table: Table? = null
    private var cell: Cell? = null
    private var paragraph: Paragraph? = null
    private var run = TextRun()
    private var inText = false
    private var slideNumber = false
    private var symbolFont = false
    private var listLevel = -1
    private var skipDepth = 0

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (skipDepth > 0) {
            skipDepth++
            return
        }
        // What a newer PowerPoint writes twice, once for older readers.
        if (uri == MARKUP_COMPATIBILITY && localName == "Fallback") {
            skipDepth = 1
            return
        }
        val parent = path.lastOrNull()
        val grandparent = path.getOrNull(path.size - 2)
        path += localName
        val shape = shapes.lastOrNull()
        when (localName) {
            "sld" -> hidden = attributes.attr("show").let { it == "0" || it == "false" }
            "sp", "pic", "graphicFrame", "cxnSp" -> shapes += Shape()
            "ph" -> shape?.placeholder = attributes.attr("type") ?: "obj"
            // A picture, or a shape filled with one - not a bullet, table cell or text drawn with a picture.
            "blip" -> if (shape != null && table == null && parent == "blipFill" && (grandparent == "pic" || grandparent == "spPr")) {
                attributes.relationship("embed")?.let { links[it]?.target }?.let { shape.picture = it }
            }
            "ext" -> if (shape != null && parent == "xfrm" && (grandparent == "spPr" || grandparent == "graphicFrame")) {
                shape.width = attributes.attr("cx")?.toLongOrNull() ?: 0
                shape.height = attributes.attr("cy")?.toLongOrNull() ?: 0
            }
            "graphicData" -> if (attributes.attr("uri")?.endsWith("/chart") == true) shape?.chart = true
            "relIds" -> attributes.relationship("dm")?.let { links[it]?.target }?.let { shape?.diagram = it }
            "tbl" -> table = Table()
            "gridCol" -> table?.columns?.add(attributes.attr("w")?.toFloatOrNull() ?: 0f)
            "tr" -> table?.rows?.add(ArrayList())
            "tc" -> cell = Cell(
                span = attributes.attr("gridSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                kind = when {
                    attributes.attr("hMerge").isTrue() -> CellKind.SKIPPED
                    attributes.attr("vMerge").isTrue() -> CellKind.EMPTY
                    else -> CellKind.NORMAL
                },
            )
            "p" -> if (parent == "txBody") paragraph = Paragraph()
            "pPr" -> if (parent == "p") paragraph?.let { p ->
                p.level = attributes.attr("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                // Left and right are the slide's sides, even in a right-to-left
                // paragraph, which PowerPoint writes as algn="r" rtl="1".
                p.align = when (attributes.attr("algn")) {
                    "l" -> DocAlign.LEFT
                    "r" -> DocAlign.RIGHT
                    "ctr" -> DocAlign.CENTER
                    "just", "dist", "justLow", "thaiDist" -> DocAlign.JUSTIFY
                    else -> DocAlign.START
                }
                p.rtl = attributes.attr("rtl")?.let { it.isTrue() }
                symbolFont = false
            }
            "buFont" -> symbolFont = attributes.attr("typeface").orEmpty().lowercase().let { it.startsWith("wingdings") || it in SYMBOL_FONTS } ||
                attributes.attr("charset") == "2"
            "buNone" -> setBullet(parent, Bullet.None)
            "buChar" -> setBullet(parent, Bullet.Glyph(glyph(attributes.attr("char").orEmpty())))
            "buAutoNum" -> setBullet(
                parent,
                Bullet.Number(attributes.attr("type") ?: "arabicPeriod", attributes.attr("startAt")?.toIntOrNull() ?: 1),
            )
            "r" -> run = TextRun()
            "fld" -> {
                run = TextRun()
                slideNumber = attributes.attr("type") == "slidenum"
            }
            "rPr" -> if (parent == "r" || parent == "fld") run.read(attributes)
            "t" -> if ((parent == "r" || parent == "fld") && !slideNumber) inText = true
            "br" -> if (parent == "p") text("\n")
            else -> if (parent == "lstStyle" && localName.startsWith("lvl") && localName.endsWith("pPr")) {
                listLevel = localName.removePrefix("lvl").removeSuffix("pPr").toIntOrNull()?.minus(1)?.takeIf { it in 0..8 } ?: -1
                if (listLevel >= 0) attributes.attr("rtl")?.let { shape?.levelRtl?.set(listLevel, it.isTrue()) }
                symbolFont = false
            }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        if (path.isNotEmpty()) path.removeAt(path.size - 1)
        when (localName) {
            "t" -> inText = false
            "fld" -> slideNumber = false
            "p" -> if (path.lastOrNull() == "txBody") finishParagraph()
            "tc" -> finishCell()
            "tbl" -> finishTable()
            "sp", "pic", "graphicFrame", "cxnSp" -> finishShape()
            else -> if (path.lastOrNull() == "lstStyle" && localName.startsWith("lvl")) listLevel = -1
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) text(String(ch, start, length))
    }

    private fun text(text: String) {
        val p = paragraph ?: return
        budget.spend(text.length)
        addText(p.spans, text, run)
    }

    /** A bullet set on the paragraph itself, or by the shape's list style for one level. */
    private fun setBullet(parent: String?, bullet: Bullet) {
        when {
            parent == "pPr" -> paragraph?.bullet = bullet
            listLevel >= 0 -> shapes.lastOrNull()?.levelBullets?.set(listLevel, bullet)
        }
    }

    /**
     * A bullet character, unless it is a picture in a symbol font, which
     * would show as a stray letter, or a character of a font's own, which
     * no other font has.
     */
    private fun glyph(char: String): String {
        if (char.isEmpty() || char.any { it in '\uE000'..'\uF8FF' }) return "•"
        if (!symbolFont) return char
        return when (char) {
            "l" -> "●"
            "n" -> "■"
            "q" -> "❑"
            "u" -> "◆"
            "v" -> "❖"
            "Ø" -> "➢"
            "ü" -> "✓"
            "§" -> "▪"
            else -> "•"
        }
    }

    private fun finishParagraph() {
        val p = paragraph ?: return
        paragraph = null
        val shape = shapes.lastOrNull()
        val inCell = cell != null
        val counts = shape?.counts ?: IntArray(9)
        for (deeper in p.level + 1 until 9) counts[deeper] = 0
        // Empty paragraphs space a slide out; there is nothing in them to read.
        if (p.spans.all { it.text.isBlank() }) return

        val placeholder = shape?.placeholder
        val bullet = p.bullet ?: shape?.levelBullets?.get(p.level)
            ?: if (!notes && !inCell && placeholder in BULLETED) Bullet.Glyph(BULLETS[p.level % BULLETS.size]) else null
        if (bullet !is Bullet.Number) counts[p.level] = 0
        val marker = when (bullet) {
            null, Bullet.None -> null
            is Bullet.Glyph -> bullet.text
            is Bullet.Number -> autoNumber(bullet.scheme, bullet.start + counts[p.level]++)
        }
        val style = if (inCell) {
            DocStyle.BODY
        } else {
            when (placeholder) {
                "ctrTitle" -> DocStyle.TITLE
                "title" -> DocStyle.HEADING1
                "subTitle" -> DocStyle.SUBTITLE
                else -> DocStyle.BODY
            }
        }
        val rtl = p.rtl ?: shape?.levelRtl?.get(p.level)
        val block = DocParagraph(p.spans.toList(), style, p.align, marker, p.level, rtl)
        val into = cell?.blocks ?: shape?.blocks ?: return
        into += block
    }

    private fun finishCell() {
        val c = cell ?: return
        cell = null
        val t = table ?: return
        if (t.rows.isEmpty()) t.rows.add(ArrayList())
        when (c.kind) {
            CellKind.SKIPPED -> Unit
            CellKind.EMPTY -> t.rows.last() += DocCell(emptyList(), c.span)
            CellKind.NORMAL -> t.rows.last() += DocCell(c.blocks.toList(), c.span)
        }
    }

    private fun finishTable() {
        val t = table ?: return
        table = null
        val width = t.rows.maxOfOrNull { row -> row.sumOf { it.span } } ?: 0
        if (width == 0) return
        val weights = if (t.columns.size == width && t.columns.all { it > 0f }) t.columns.toList() else List(width) { 1f }
        shapes.lastOrNull()?.blocks?.add(DocTable(weights, t.rows.map { it.toList() }))
    }

    private fun finishShape() {
        if (shapes.isEmpty()) return
        val shape = shapes.removeAt(shapes.size - 1)
        val placeholder = shape.placeholder
        if (notes) {
            // The notes themselves, in their placeholder or a text box: not the slide's picture or the page number.
            if (placeholder != "sldImg" && placeholder !in FURNITURE) {
                pieces += Piece(title = false, blocks = shape.blocks.toList(), diagram = null)
            }
            return
        }
        if (placeholder in FURNITURE) return
        val blocks = ArrayList<DocBlock>()
        shape.picture?.let { blocks += DocImage(it, shape.width, shape.height) }
        blocks += shape.blocks
        if (shape.chart) blocks += DocParagraph(listOf(DocSpan("[Chart that can't be shown here]", italic = true)))
        if (blocks.isEmpty() && shape.diagram == null) return
        pieces += Piece(title = placeholder == "title" || placeholder == "ctrTitle", blocks = blocks, diagram = shape.diagram)
    }
}

/** A numbered bullet's marker: "arabicPeriod" makes 1., "alphaLcParenR" a), "romanUcPeriod" I. */
internal fun autoNumber(scheme: String, n: Int): String {
    val format = when {
        scheme.startsWith("alphaLc") -> "lowerLetter"
        scheme.startsWith("alphaUc") -> "upperLetter"
        scheme.startsWith("romanLc") -> "lowerRoman"
        scheme.startsWith("romanUc") -> "upperRoman"
        else -> "decimal"
    }
    val number = formatNumber(n, format)
    return when {
        scheme.endsWith("ParenBoth") -> "($number)"
        scheme.endsWith("ParenR") -> "$number)"
        scheme.endsWith("Plain") -> number
        else -> "$number."
    }
}

/** The text of each box of a SmartArt graphic, from its data part, as bullets. */
private class DiagramHandler(private val budget: TextBudget) : OoxmlHandler() {
    val paragraphs = ArrayList<DocBlock>()

    private val path = ArrayList<String>()
    private var inNode = false
    private var spans: ArrayList<DocSpan>? = null
    private var run = TextRun()
    private var inText = false

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        val parent = path.lastOrNull()
        path += localName
        when (localName) {
            // Connections between boxes are points too, with empty text.
            "pt" -> inNode = attributes.attr("type").let { it == null || it == "node" }
            "p" -> if (inNode) spans = ArrayList()
            "r" -> run = TextRun()
            "rPr" -> if (parent == "r") run.read(attributes)
            "t" -> if (parent == "r" && spans != null) inText = true
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (path.isNotEmpty()) path.removeAt(path.size - 1)
        when (localName) {
            "pt" -> inNode = false
            "t" -> inText = false
            "p" -> spans?.let { text ->
                spans = null
                if (text.any { it.text.isNotBlank() }) paragraphs += DocParagraph(text.toList(), marker = BULLETS[0])
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val into = spans ?: return
        if (!inText) return
        budget.spend(length)
        addText(into, String(ch, start, length), run)
    }
}
