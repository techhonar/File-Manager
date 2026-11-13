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
        val templates = Templates(parts)
        val slides = ArrayList<PptxSlide>()
        for ((index, part) in slideParts.withIndex()) {
            if (index >= MAX_SLIDES || budget.spent || parts.cutShort) break
            val slide = readSlide(parts, part, index + 1, budget, templates)
            // A slide cut short before anything on it was read goes with the rest left out.
            if ((budget.spent || parts.cutShort) && slide.blocks.isEmpty()) break
            slides += slide
        }
        // Read as far as a file is, with nothing to show for it: not a presentation with no slides.
        if (parts.cutShort && slides.isEmpty()) throw TooLarge()
        PptxDeck(slides, total = slideParts.size, truncated = budget.spent || budget.leftOut || parts.cutShort || slides.size < slideParts.size)
    }

    private fun readSlide(parts: OoxmlParts, part: String, number: Int, budget: TextBudget, templates: Templates): PptxSlide {
        budget.nextSlide()
        val links = parts.links(part)
        val slide = SlideHandler(links, budget, notes = false, template = templates.of(links))
        parse(parts, part, slide, budget)
        // The title first, wherever the slide keeps it; the rest as it comes.
        val blocks = slide.pieces.sortedByDescending { it.title }.flatMap { piece ->
            piece.blocks + piece.diagram?.let { readDiagram(parts, it, budget) }.orEmpty()
        }
        val notes = links.values.firstOrNull { it.kind == "notesSlide" }?.let { link ->
            val page = SlideHandler(emptyMap(), budget, notes = true, template = null)
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

/**
 * The characters still to be read, and the paragraphs, pictures and table
 * cells still to be kept, shared by every part of the file.
 */
private class TextBudget(private val max: Int) {
    private var used = 0
    private var pieces = 0
    private var onSlide = 0
    var spent = false
        private set

    /** Something on a slide was left out: it held more than is shown at once. */
    var leftOut = false
        private set

    fun spend(chars: Int) {
        used += chars
        if (used > max) {
            spent = true
            throw ReadEnough()
        }
    }

    /**
     * One more paragraph, stretch of text, picture, row or cell kept -
     * [laidOut] with its slide, unless it is only text in a paragraph that is.
     */
    fun piece(laidOut: Boolean = true) {
        if (++pieces > Docx.MAX_PIECES) {
            spent = true
            throw ReadEnough()
        }
        if (laidOut) onSlide++
    }

    fun nextSlide() {
        onSlide = 0
    }

    /** The slide being read holds as much as is shown at once. */
    val crowded: Boolean get() = onSlide >= Docx.MAX_TOGETHER

    /** Says that something was [leftOut]. */
    fun leaveOut() {
        leftOut = true
    }
}

/** Slides counted, ten times as many as are read. */
private const val MAX_SLIDE_IDS = Pptx.MAX_SLIDES * 10

/** More columns than any table on a slide has. */
private const val MAX_TABLE_COLUMNS = 256

private class PresentationHandler : OoxmlHandler() {
    /** The slides' relationship ids, in the presentation's order. */
    val slides = ArrayList<String>()

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (localName == "sldId" && slides.size < MAX_SLIDE_IDS) attributes.relationship("id")?.let { slides += it }
    }
}

private const val MARKUP_COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006"

/**
 * Placeholders that are bulleted when nothing says otherwise - which is only
 * in a file with no layout or master to say: body text.
 */
private val BULLETED = setOf("body", "obj")

/** What every slide repeats at its foot: page number, date, footer. */
private val FURNITURE = setOf("sldNum", "dt", "ftr", "hdr")

/** Fonts of pictures rather than letters, whose bullet characters show as some other letter in any other font. */
private val SYMBOL_FONTS = setOf("symbol", "webdings", "starbats", "starsymbol", "opensymbol", "marlett", "zapfdingbats", "mt extra")

private val BULLETS = listOf("•", "◦", "▪")

/** A paragraph's bullet, as the paragraph itself or a list style sets it. */
private sealed interface Bullet {
    data object None : Bullet
    data class Glyph(val text: String) : Bullet
    data class Number(val scheme: String, val start: Int) : Bullet
}

/** Whether [typeface] or [charset] is a font of pictures, as a buFont names it. */
private fun isSymbolFont(typeface: String?, charset: String?): Boolean =
    typeface.orEmpty().lowercase().let { it.startsWith("wingdings") || it in SYMBOL_FONTS } || charset == "2"

/**
 * A bullet character, unless it is a picture in a symbol font, which would
 * show as a stray letter, or a character of a font's own, which no other
 * font has.
 */
private fun bulletGlyph(char: String, symbolFont: Boolean): String {
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

/** The level a list style's lvl1pPr to lvl9pPr is for, from 0; -1 for anything else. */
private fun listLevelOf(name: String): Int =
    if (!name.startsWith("lvl") || !name.endsWith("pPr")) -1
    else name.removePrefix("lvl").removeSuffix("pPr").toIntOrNull()?.minus(1)?.takeIf { it in 0..8 } ?: -1

/** Bullets and reading direction by list level, as one list style gives them: null where it says nothing. */
private class ListStyle {
    val bullets = arrayOfNulls<Bullet>(9)
    val rtl = arrayOfNulls<Boolean>(9)
}

/*
 * A slide says little about its own text. Whether a placeholder's
 * paragraphs are bulleted, and with what, is said by the layout the slide
 * is built on and by that layout's master: a master gives body text a
 * bullet for each level, and a layout takes them away again for the text
 * under a section's title or beside a picture. Read without them, every
 * line of body text had a bullet - "• Chapter two" on a section header.
 */

/** What one layout or master says of the text in its placeholders. */
private class TemplateHandler : OoxmlHandler() {
    class Placeholder(val type: String, val idx: String?) {
        val style = ListStyle()
    }

    val placeholders = ArrayList<Placeholder>()

    /** A master's own styles, by name: titleStyle, bodyStyle, otherStyle. */
    val textStyles = HashMap<String, ListStyle>()

    private val path = ArrayList<String>()
    private var placeholder: Placeholder? = null
    private var style: ListStyle? = null
    private var level = -1
    private var symbolFont = false

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        val parent = path.lastOrNull()
        path += localName
        when (localName) {
            "sp", "pic", "graphicFrame" -> placeholder = null
            "ph" -> if (placeholders.size < MAX_PLACEHOLDERS) {
                placeholder = Placeholder(attributes.attr("type") ?: "obj", attributes.attr("idx")).also { placeholders += it }
            }
            "lstStyle" -> style = if (parent == "txBody") placeholder?.style else null
            "titleStyle", "bodyStyle", "otherStyle" -> if (parent == "txStyles") style = textStyles.getOrPut(localName) { ListStyle() }
            "buFont" -> symbolFont = isSymbolFont(attributes.attr("typeface"), attributes.attr("charset"))
            "buNone" -> set(Bullet.None)
            "buChar" -> set(Bullet.Glyph(bulletGlyph(attributes.attr("char").orEmpty(), symbolFont)))
            "buAutoNum" -> set(Bullet.Number(attributes.attr("type") ?: "arabicPeriod", attributes.attr("startAt")?.toIntOrNull() ?: 1))
            else -> {
                val at = listLevelOf(localName)
                if (at >= 0 && (parent == "lstStyle" || parent in TEXT_STYLES)) {
                    level = at
                    symbolFont = false
                    attributes.attr("rtl")?.let { style?.rtl?.set(at, it.isTrue()) }
                }
            }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (path.isNotEmpty()) path.removeAt(path.size - 1)
        when (localName) {
            "lstStyle", "titleStyle", "bodyStyle", "otherStyle" -> {
                style = null
                level = -1
            }
            "sp", "pic", "graphicFrame" -> placeholder = null
            else -> if (listLevelOf(localName) >= 0) level = -1
        }
    }

    private fun set(bullet: Bullet) {
        if (level >= 0) style?.bullets?.set(level, bullet)
    }

    private companion object {
        val TEXT_STYLES = setOf("titleStyle", "bodyStyle", "otherStyle")

        /** A layout has a dozen; one with thousands is not a layout. */
        const val MAX_PLACEHOLDERS = 1_000
    }
}

/** What a slide's placeholders take from its layout, and from the master the layout is built on. */
private class Template(private val layout: TemplateHandler, private val master: TemplateHandler?) {
    private val found = HashMap<Pair<String, String?>, List<ListStyle>>()

    /** The bullet a placeholder of [type] and [idx] is given at [level], or null if neither layout nor master says. */
    fun bullet(type: String, idx: String?, level: Int): Bullet? =
        styles(type, idx).firstNotNullOfOrNull { it.bullets[level] }

    /** Whether such a placeholder reads right to left at [level], or null if neither says. */
    fun rtl(type: String, idx: String?, level: Int): Boolean? =
        styles(type, idx).firstNotNullOfOrNull { it.rtl[level] }

    /**
     * The styles such a placeholder is built on, nearest first: its
     * counterpart on the layout - the one with its number, or else its type -
     * then the master's placeholder for that kind of text, then the master's
     * style for it.
     */
    private fun styles(type: String, idx: String?): List<ListStyle> = found.getOrPut(type to idx) {
        val number = idx ?: "0"
        val onLayout = layout.placeholders.firstOrNull { (it.idx ?: "0") == number && sameKind(it.type, type) }
            ?: layout.placeholders.firstOrNull { it.type == type }
        // A master has a title, a body and the footers; everything else on a slide is built on its body.
        val kind = when (type) {
            "title", "ctrTitle" -> "title"
            "dt", "ftr", "sldNum" -> type
            else -> "body"
        }
        val textStyle = when (kind) {
            "title" -> "titleStyle"
            "body" -> "bodyStyle"
            else -> "otherStyle"
        }
        listOfNotNull(
            onLayout?.style,
            master?.placeholders?.firstOrNull { it.type == kind }?.style,
            master?.textStyles?.get(textStyle),
        )
    }

    /** Titles are built on titles, and text on text: a slide's number is not taken for a layout's that is something else. */
    private fun sameKind(a: String, b: String): Boolean = (a in TITLES) == (b in TITLES)

    private companion object {
        val TITLES = setOf("title", "ctrTitle")
    }
}

/** A presentation's layouts and masters, each read once however many slides are built on it. */
private class Templates(private val parts: OoxmlParts) {
    private val read = HashMap<String, TemplateHandler?>()
    private val built = HashMap<String, Template?>()

    /** What the slide with these [links] is built on, or null if it names no layout that can be read. */
    fun of(links: Map<String, OoxmlLink>): Template? {
        val layoutPart = links.values.firstOrNull { it.kind == "slideLayout" }?.target ?: return null
        if (layoutPart !in built) {
            built[layoutPart] = styles(layoutPart)?.let { layout ->
                val masterPart = try {
                    parts.links(layoutPart).values.firstOrNull { it.kind == "slideMaster" }?.target
                } catch (e: SAXException) {
                    null
                }
                Template(layout, masterPart?.let(::styles))
            }
        }
        return built[layoutPart]
    }

    private fun styles(part: String): TemplateHandler? {
        if (part !in read) {
            // One that can't be read is done without: the slide is read as one with no layout.
            read[part] = if (read.size >= MAX_TEMPLATES) null else TemplateHandler().takeIf { handler ->
                try {
                    parts.parse(part, handler)
                } catch (e: SAXException) {
                    false
                }
            }
        }
        return read[part]
    }

    private companion object {
        /** Layouts and masters read: a presentation has a few dozen. */
        const val MAX_TEMPLATES = 500
    }
}

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
private fun addText(spans: MutableList<DocSpan>, text: String, run: TextRun, budget: TextBudget) {
    val last = spans.lastOrNull()
    val span = run.span(text)
    if (last != null && last.copy(text = "") == span.copy(text = "")) {
        spans[spans.size - 1] = last.copy(text = last.text + text)
    } else {
        budget.piece(laidOut = false)
        spans += span
    }
}

private class SlideHandler(
    private val links: Map<String, OoxmlLink>,
    private val budget: TextBudget,
    /** A notes page: only the notes, not the slide's picture or page number. */
    private val notes: Boolean,
    /** What the slide's layout and master say of its placeholders' text; null if it has none that can be read. */
    private val template: Template?,
) : OoxmlHandler() {

    /** What one shape adds to the slide. */
    class Piece(val title: Boolean, val blocks: List<DocBlock>, val diagram: String?)

    private class Shape {
        /** The placeholder's type - "title", "body" - or "obj" for one that doesn't say; null if not a placeholder. */
        var placeholder: String? = null
        /** The placeholder's number, which its counterpart on the layout shares. */
        var placeholderIdx: String? = null
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
        // A slide is laid out all at once: past what that can be, the rest
        // of its paragraphs and table rows are left out, whole.
        val tooMany = when (localName) {
            "tr" -> table != null && budget.crowded
            "tc" -> table != null && (table?.rows?.lastOrNull()?.size ?: 0) >= MAX_TABLE_COLUMNS
            "p" -> parent == "txBody" && budget.crowded
            else -> false
        }
        if (tooMany) {
            budget.leaveOut()
            skipDepth = 1
            return
        }
        path += localName
        val shape = shapes.lastOrNull()
        when (localName) {
            "sld" -> hidden = attributes.attr("show").let { it == "0" || it == "false" }
            "sp", "pic", "graphicFrame", "cxnSp" -> shapes += Shape()
            "ph" -> shape?.let {
                it.placeholder = attributes.attr("type") ?: "obj"
                it.placeholderIdx = attributes.attr("idx")
            }
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
            "gridCol" -> table?.columns?.let { if (it.size < MAX_TABLE_COLUMNS) it.add(attributes.attr("w")?.toFloatOrNull() ?: 0f) }
            "tr" -> table?.rows?.let { rows ->
                budget.piece()
                rows.add(ArrayList())
            }
            "tc" -> cell = Cell(
                span = attributes.attr("gridSpan")?.toIntOrNull()?.coerceIn(1, MAX_TABLE_COLUMNS) ?: 1,
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
            "buFont" -> symbolFont = isSymbolFont(attributes.attr("typeface"), attributes.attr("charset"))
            "buNone" -> setBullet(parent, Bullet.None)
            "buChar" -> setBullet(parent, Bullet.Glyph(bulletGlyph(attributes.attr("char").orEmpty(), symbolFont)))
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
            else -> if (parent == "lstStyle" && listLevelOf(localName) >= 0) {
                listLevel = listLevelOf(localName)
                attributes.attr("rtl")?.let { shape?.levelRtl?.set(listLevel, it.isTrue()) }
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
        addText(p.spans, text, run, budget)
    }

    /** A bullet set on the paragraph itself, or by the shape's list style for one level. */
    private fun setBullet(parent: String?, bullet: Bullet) {
        when {
            parent == "pPr" -> paragraph?.bullet = bullet
            listLevel >= 0 -> shapes.lastOrNull()?.levelBullets?.set(listLevel, bullet)
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
        // What a placeholder's text is built on: its layout and master. Not
        // a table's text, which is no placeholder's, nor a notes page's.
        val built = template?.takeIf { !notes && !inCell && placeholder != null }
        val bullet = p.bullet ?: shape?.levelBullets?.get(p.level) ?: when {
            built != null -> built.bullet(placeholder.orEmpty(), shape?.placeholderIdx, p.level)
            // No layout to say: body text is bulleted, as almost every master has it.
            !notes && !inCell && placeholder in BULLETED -> Bullet.Glyph(BULLETS[p.level % BULLETS.size])
            else -> null
        }
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
        // A layout made for Persian or Arabic makes its text read right to
        // left. One that says left to right says only what every English
        // template says, and is no reason to stop looking at the text itself.
        val rtl = p.rtl ?: shape?.levelRtl?.get(p.level)
            ?: built?.rtl(placeholder.orEmpty(), shape?.placeholderIdx, p.level)?.takeIf { it }
        val block = DocParagraph(p.spans.toList(), style, p.align, marker, p.level, rtl)
        val into = cell?.blocks ?: shape?.blocks ?: return
        budget.piece()
        into += block
    }

    private fun finishCell() {
        val c = cell ?: return
        cell = null
        val t = table ?: return
        if (t.rows.isEmpty()) t.rows.add(ArrayList())
        if (c.kind == CellKind.SKIPPED) return
        budget.piece()
        t.rows.last() += DocCell(if (c.kind == CellKind.EMPTY) emptyList() else c.blocks.toList(), c.span)
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
        // A picture or a chart past what a slide shows at once goes with the rest left out.
        val room = !budget.crowded
        if (!room && (shape.picture != null || shape.chart)) budget.leaveOut()
        shape.picture?.takeIf { room }?.let {
            budget.piece()
            blocks += DocImage(it, shape.width, shape.height)
        }
        blocks += shape.blocks
        if (shape.chart && room) {
            budget.piece()
            blocks += DocParagraph(listOf(DocSpan("[Chart that can't be shown here]", italic = true)))
        }
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
                if (text.any { it.text.isNotBlank() }) {
                    if (budget.crowded) {
                        budget.leaveOut()
                    } else {
                        budget.piece()
                        paragraphs += DocParagraph(text.toList(), marker = BULLETS[0])
                    }
                }
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val into = spans ?: return
        if (!inText) return
        budget.spend(length)
        addText(into, String(ch, start, length), run, budget)
    }
}
