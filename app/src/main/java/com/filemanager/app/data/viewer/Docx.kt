package com.filemanager.app.data.viewer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/*
 * Reading a Word document (.docx) for the viewer.
 *
 * A .docx is a zip of XML files. The text is in word/document.xml; styles.xml
 * says which paragraph styles are headings, numbering.xml how list items are
 * numbered, and document.xml.rels where each picture is. What comes out is
 * the document's reading order - headings, paragraphs, lists, tables and
 * pictures - rather than Word's page layout: fonts, columns and page breaks
 * are left to Word.
 */

/** How a paragraph is set, reduced to the styles that change how it reads. */
enum class DocStyle { TITLE, SUBTITLE, HEADING1, HEADING2, HEADING3, HEADING4, BODY }

enum class DocAlign { START, CENTER, END, JUSTIFY }

/** A stretch of text with one formatting. */
data class DocSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
)

sealed interface DocBlock

data class DocParagraph(
    val spans: List<DocSpan>,
    val style: DocStyle = DocStyle.BODY,
    val align: DocAlign = DocAlign.START,
    /** What a list item starts with - "•", "1.", "a)" - or null outside a list. */
    val marker: String? = null,
    /** How deep in its list, from 0. */
    val level: Int = 0,
) : DocBlock {
    val text: String get() = spans.joinToString("") { it.text }
}

/** A picture: its entry in the zip, and its size in EMUs (914400 an inch), 0 if unknown. */
data class DocImage(val entry: String, val widthEmu: Long, val heightEmu: Long) : DocBlock

/** A table cell, spanning [span] of the table's columns. */
data class DocCell(val blocks: List<DocBlock>, val span: Int)

/** [columns] are relative widths, one per column of the table's grid. */
data class DocTable(val columns: List<Float>, val rows: List<List<DocCell>>) : DocBlock

class DocxDocument(val blocks: List<DocBlock>, val truncated: Boolean)

object Docx {

    /**
     * Stop after this many characters, around a thousand pages. The whole
     * document is held to be shown, and a generated one can be far larger.
     */
    const val MAX_CHARS = 2_000_000

    fun read(file: File, maxChars: Int = MAX_CHARS): DocxDocument = ZipFile(file).use { zip ->
        fun parse(name: String, handler: DefaultHandler) {
            val entry = zip.getEntry(name) ?: return
            zip.getInputStream(entry).use { parseXml(it, handler) }
        }

        val images = RelsHandler().also { parse("word/_rels/document.xml.rels", it) }.targets
        val styles = StylesHandler().also { parse("word/styles.xml", it) }.styles()
        val numbering = NumberingHandler().also { parse("word/numbering.xml", it) }.numbering()

        val document = zip.getEntry("word/document.xml") ?: throw IOException("Not a Word document")
        val body = BodyHandler(images, styles, numbering, maxChars)
        try {
            zip.getInputStream(document).use { parseXml(it, body) }
        } catch (e: SAXException) {
            if (!body.truncated) throw e
        }
        DocxDocument(body.blocks, body.truncated)
    }
}

// ---- Numbering -------------------------------------------------------------

/** One level of a list definition: "decimal" and "%1." make 1., 2., 3. */
internal class ListLevel(val format: String, val text: String, val start: Int)

/**
 * Numbers list items as they come, per list. Word would continue a list
 * across separate lists sharing a definition; counting each list on its own
 * gets the common case right, where every list starts again at 1.
 */
internal class Numbering(private val lists: Map<String, Map<Int, ListLevel>>) {

    private val counts = HashMap<String, IntArray>()

    /** The marker for the next item of list [numId] at [level], or null if there is no such list. */
    fun next(numId: String, level: Int): String? {
        val levels = lists[numId] ?: return null
        val at = level.coerceIn(0, 8)
        val count = counts.getOrPut(numId) { IntArray(9) }
        count[at]++
        for (deeper in at + 1 until 9) count[deeper] = 0

        val definition = levels[at] ?: return BULLETS[at % BULLETS.size]
        return when (definition.format) {
            // The level's own text is a glyph from the Symbol font, which
            // shows as a box anywhere else.
            "bullet" -> BULLETS[at % BULLETS.size]
            "none" -> ""
            else -> PLACEHOLDER.replace(definition.text) { match ->
                val k = match.groupValues[1].toInt() - 1
                val outer = levels[k]
                val n = (outer?.start ?: 1) + maxOf(count[k], 1) - 1
                formatNumber(n, outer?.format ?: "decimal")
            }
        }
    }

    companion object {
        private val BULLETS = listOf("•", "◦", "▪")
        private val PLACEHOLDER = Regex("%([1-9])")
    }
}

internal fun formatNumber(n: Int, format: String): String = when (format) {
    "lowerLetter" -> letters(n)
    "upperLetter" -> letters(n).uppercase()
    "lowerRoman" -> roman(n).lowercase()
    "upperRoman" -> roman(n)
    "decimalZero" -> n.toString().padStart(2, '0')
    else -> n.toString()
}

/** Word's lettering: a to z, then aa, bb and so on. */
private fun letters(n: Int): String {
    if (n < 1) return n.toString()
    val letter = 'a' + (n - 1) % 26
    return letter.toString().repeat((n - 1) / 26 + 1)
}

private fun roman(n: Int): String {
    if (n !in 1..3999) return n.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var rest = n
    return buildString {
        for (i in values.indices) {
            while (rest >= values[i]) {
                append(symbols[i])
                rest -= values[i]
            }
        }
    }
}

// ---- Styles ----------------------------------------------------------------

/**
 * The style a Word style name stands for. By name rather than id: the id is
 * translated with Word ("berschrift1" in German), the name stays "heading 1".
 */
internal fun styleNamed(name: String?): DocStyle? {
    val n = name?.trim()?.lowercase() ?: return null
    if (n == "title") return DocStyle.TITLE
    if (n == "subtitle") return DocStyle.SUBTITLE
    val level = HEADING.matchEntire(n)?.groupValues?.get(1)?.toInt() ?: return null
    return when (level) {
        1 -> DocStyle.HEADING1
        2 -> DocStyle.HEADING2
        3 -> DocStyle.HEADING3
        else -> DocStyle.HEADING4
    }
}

private val HEADING = Regex("heading (\\d)")

/** Where a relationship's target is inside the zip, relative to word/. */
internal fun resolveTarget(target: String): String {
    val path = if (target.startsWith("/")) target.drop(1) else "word/$target"
    val parts = ArrayList<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

// ---- XML -------------------------------------------------------------------

private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val W_STRICT = "http://purl.oclc.org/ooxml/wordprocessingml/main"
private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val R_STRICT = "http://purl.oclc.org/ooxml/officeDocument/relationships"
private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"

private fun isW(uri: String) = uri == W || uri == W_STRICT

private fun Attributes.w(name: String): String? = getValue(W, name) ?: getValue(W_STRICT, name)

private fun Attributes.rel(name: String): String? = getValue(R, name) ?: getValue(R_STRICT, name)

/** An attribute with no namespace, which some parsers only find by its plain name. */
private fun Attributes.plain(name: String): String? = getValue("", name) ?: getValue(name)

/** A toggle such as <w:b/>: on unless its value says off. */
private fun Attributes.isOn(): Boolean = when (w("val")?.lowercase()) {
    "0", "false", "off", "none" -> false
    else -> true
}

private fun parseXml(input: InputStream, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    factory.newSAXParser().parse(input, handler)
}

/** Never fetches anything a document points to outside itself. */
private abstract class XmlHandler : DefaultHandler() {
    override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
}

private class RelsHandler : XmlHandler() {
    val targets = HashMap<String, String>()

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (localName != "Relationship") return
        if (attributes.plain("TargetMode") == "External") return
        val id = attributes.plain("Id") ?: return
        val target = attributes.plain("Target") ?: return
        targets[id] = resolveTarget(target)
    }
}

/** What a paragraph style gives its paragraphs: how they read, and any list they are items of. */
internal class StyleInfo(val style: DocStyle, val list: String?, val level: Int)

private class StylesHandler : XmlHandler() {
    private val names = HashMap<String, String>()
    private val basedOn = HashMap<String, String>()
    private val lists = HashMap<String, String>()
    private val levels = HashMap<String, Int>()
    private var current: String? = null

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (!isW(uri)) return
        val id = current
        when (localName) {
            "style" -> current = attributes.w("styleId")
            "name" -> if (id != null) attributes.w("val")?.let { names[id] = it }
            "basedOn" -> if (id != null) attributes.w("val")?.let { basedOn[id] = it }
            // Word's own List Bullet and List Number styles number their
            // paragraphs themselves, so the paragraphs carry no numbering.
            "numId" -> if (id != null) attributes.w("val")?.let { lists[id] = it }
            "ilvl" -> if (id != null) attributes.w("val")?.toIntOrNull()?.let { levels[id] = it }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (isW(uri) && localName == "style") current = null
    }

    /**
     * Every style id and what it gives, following "based on" - a house style
     * built on Heading 1 is still a heading.
     */
    fun styles(): Map<String, StyleInfo> = names.keys.associateWith { id ->
        StyleInfo(
            style = inherited(id) { styleNamed(names[it]) } ?: DocStyle.BODY,
            list = inherited(id) { lists[it] }?.takeIf { it != "0" },
            level = inherited(id) { levels[it] } ?: 0,
        )
    }

    private fun <T> inherited(id: String, own: (String) -> T?): T? {
        var at: String? = id
        var hops = 0
        while (at != null && hops++ < 8) {
            own(at)?.let { return it }
            at = basedOn[at]
        }
        return null
    }
}

private class NumberingHandler : XmlHandler() {
    private class Level(var format: String = "decimal", var text: String = "%1.", var start: Int = 1)

    private val definitions = HashMap<String, HashMap<Int, Level>>()
    private val lists = HashMap<String, String>()
    private var definition: String? = null
    private var level: Level? = null
    private var list: String? = null

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (!isW(uri)) return
        when (localName) {
            "abstractNum" -> definition = attributes.w("abstractNumId")
            // Only a definition's own levels: a list's overrides are ignored.
            "lvl" -> level = definition?.let { id ->
                Level().also {
                    definitions.getOrPut(id) { HashMap() }[attributes.w("ilvl")?.toIntOrNull() ?: 0] = it
                }
            }
            "numFmt" -> level?.format = attributes.w("val") ?: "decimal"
            "lvlText" -> level?.text = attributes.w("val").orEmpty()
            "start" -> level?.start = attributes.w("val")?.toIntOrNull() ?: 1
            "num" -> list = attributes.w("numId")
            "abstractNumId" -> list?.let { id -> attributes.w("val")?.let { lists[id] = it } }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (!isW(uri)) return
        when (localName) {
            "abstractNum" -> definition = null
            "lvl" -> level = null
            "num" -> list = null
        }
    }

    fun numbering() = Numbering(
        lists.mapValues { (_, id) ->
            definitions[id].orEmpty().mapValues { (_, l) -> ListLevel(l.format, l.text, l.start) }
        },
    )
}

private class StopReading : SAXException("Read enough of the document")

private class BodyHandler(
    private val images: Map<String, String>,
    private val styles: Map<String, StyleInfo>,
    private val numbering: Numbering,
    private val maxChars: Int,
) : XmlHandler() {

    private class Run {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var hidden = false
    }

    private class SpanBuilder(val run: Run) {
        val text = StringBuilder()
        fun sameFormat(other: Run) = run.bold == other.bold && run.italic == other.italic &&
            run.underline == other.underline && run.strike == other.strike
        fun build() = DocSpan(text.toString(), run.bold, run.italic, run.underline, run.strike)
    }

    private class ParagraphBuilder {
        var style: String? = null
        var align = DocAlign.START
        /** Set when the paragraph says which list it is in, even to say none. */
        var ownList = false
        var list: String? = null
        var level: Int? = null
        /** Text and pictures in the order they come: SpanBuilder or DocImage. */
        val parts = ArrayList<Any>()
    }

    private class TableBuilder {
        val columns = ArrayList<Float>()
        val rows = ArrayList<MutableList<DocCell>>()
        var cellSpan = 1

        fun build(): DocTable {
            val width = rows.maxOfOrNull { row -> row.sumOf { it.span } } ?: 0
            val weights = if (columns.size == width && columns.all { it > 0f }) columns else List(width) { 1f }
            return DocTable(weights, rows)
        }
    }

    val blocks = ArrayList<DocBlock>()
    var truncated = false
        private set

    /** Where finished blocks go: the body, or the table cell being read. */
    private val containers = arrayListOf<MutableList<DocBlock>>(blocks)
    private val tables = ArrayList<TableBuilder>()
    // More than one when a text box's paragraphs sit inside a paragraph.
    private val paragraphs = ArrayList<ParagraphBuilder>()

    private var skipDepth = 0
    private var inParagraphProps = false
    private var inRunProps = false
    private var inText = false
    private var run = Run()
    private var extentCx = 0L
    private var extentCy = 0L
    private var chars = 0

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (skipDepth > 0) {
            skipDepth++
            return
        }
        // What a newer Word writes twice - once for itself and once, in the
        // fallback, for older readers. Reading both would show it twice.
        if (uri == MC && localName == "Fallback") {
            skipDepth = 1
            return
        }
        when (localName) {
            "extent" -> {
                extentCx = attributes.plain("cx")?.toLongOrNull() ?: 0
                extentCy = attributes.plain("cy")?.toLongOrNull() ?: 0
                return
            }
            "blip" -> {
                attributes.rel("embed")?.let(::addImage)
                return
            }
            "imagedata" -> {
                attributes.rel("id")?.let(::addImage)
                return
            }
        }
        if (!isW(uri)) return
        // Deleted text, and formatting from before a tracked change.
        if (localName in SKIPPED) {
            skipDepth = 1
            return
        }

        val paragraph = paragraphs.lastOrNull()
        when (localName) {
            "p" -> paragraphs += ParagraphBuilder()
            "pPr" -> inParagraphProps = true
            "pStyle" -> if (inParagraphProps && paragraph != null) paragraph.style = attributes.w("val")
            "numId" -> if (inParagraphProps && paragraph != null) {
                paragraph.ownList = true
                paragraph.list = attributes.w("val")?.takeIf { it != "0" }
            }
            "ilvl" -> if (inParagraphProps && paragraph != null) {
                paragraph.level = attributes.w("val")?.toIntOrNull() ?: 0
            }
            "jc" -> if (inParagraphProps && paragraph != null) {
                paragraph.align = when (attributes.w("val")) {
                    "center" -> DocAlign.CENTER
                    "right", "end" -> DocAlign.END
                    "both", "distribute" -> DocAlign.JUSTIFY
                    else -> DocAlign.START
                }
            }
            "r" -> run = Run()
            // Inside pPr it formats the paragraph mark, not any text.
            "rPr" -> if (!inParagraphProps) inRunProps = true
            "b" -> if (inRunProps) run.bold = attributes.isOn()
            "i" -> if (inRunProps) run.italic = attributes.isOn()
            "u" -> if (inRunProps) run.underline = attributes.w("val") != "none"
            "strike", "dstrike" -> if (inRunProps) run.strike = attributes.isOn()
            "vanish" -> if (inRunProps) run.hidden = attributes.isOn()
            "t" -> inText = true
            // Tab stops are also "tab", inside pPr.
            "tab" -> if (!inParagraphProps) text("\t")
            "br", "cr" -> if (attributes.w("type") != "page") text("\n")
            "noBreakHyphen" -> text("-")
            "tbl" -> tables += TableBuilder()
            "gridCol" -> tables.lastOrNull()?.columns?.add(attributes.w("w")?.toFloatOrNull() ?: 0f)
            "tr" -> tables.lastOrNull()?.rows?.add(ArrayList())
            "tc" -> {
                containers.add(ArrayList())
                tables.lastOrNull()?.cellSpan = 1
            }
            "gridSpan" -> tables.lastOrNull()?.cellSpan = attributes.w("val")?.toIntOrNull() ?: 1
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        if (!isW(uri)) return
        when (localName) {
            "p" -> if (paragraphs.isNotEmpty()) finish(paragraphs.removeAt(paragraphs.size - 1))
            "pPr" -> inParagraphProps = false
            "rPr" -> inRunProps = false
            "t" -> inText = false
            "tc" -> if (containers.size > 1) {
                val cell = containers.removeAt(containers.size - 1)
                val table = tables.lastOrNull() ?: return
                if (table.rows.isEmpty()) table.rows.add(ArrayList())
                table.rows.last() += DocCell(cell, table.cellSpan.coerceAtLeast(1))
            }
            "tbl" -> if (tables.isNotEmpty()) {
                containers.last() += tables.removeAt(tables.size - 1).build()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText && skipDepth == 0) text(String(ch, start, length))
    }

    private fun text(text: String) {
        val paragraph = paragraphs.lastOrNull() ?: return
        if (run.hidden) return
        chars += text.length
        if (chars > maxChars) {
            truncated = true
            throw StopReading()
        }
        val last = paragraph.parts.lastOrNull()
        if (last is SpanBuilder && last.sameFormat(run)) {
            last.text.append(text)
        } else {
            paragraph.parts += SpanBuilder(run).also { it.text.append(text) }
        }
    }

    private fun addImage(id: String) {
        val entry = images[id] ?: return
        val image = DocImage(entry, extentCx, extentCy)
        extentCx = 0
        extentCy = 0
        val paragraph = paragraphs.lastOrNull()
        if (paragraph != null) paragraph.parts += image else containers.last() += image
    }

    /**
     * A paragraph with pictures in it becomes text, picture, text, in order.
     * An empty paragraph is kept: documents use them for spacing.
     */
    private fun finish(paragraph: ParagraphBuilder) {
        val into = containers.last()
        val styled = paragraph.style?.let { styles[it] }
        val list = if (paragraph.ownList) paragraph.list else styled?.list
        val level = if (list == null) 0 else paragraph.level ?: styled?.level ?: 0
        var marker = list?.let { numbering.next(it, level) }
        val spans = ArrayList<DocSpan>()
        fun flush() {
            into += DocParagraph(spans.toList(), styled?.style ?: DocStyle.BODY, paragraph.align, marker, level)
            marker = null
            spans.clear()
        }
        for (part in paragraph.parts) {
            when (part) {
                is SpanBuilder -> spans += part.build()
                is DocImage -> {
                    if (spans.isNotEmpty()) flush()
                    into += part
                }
            }
        }
        if (spans.isNotEmpty() || paragraph.parts.none { it is DocImage }) flush()
    }

    private companion object {
        val SKIPPED = setOf("del", "moveFrom", "rPrChange", "pPrChange")
    }
}
