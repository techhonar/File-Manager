package com.filemanager.app.data.viewer

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipFile

/*
 * Reading an Excel workbook (.xlsx) for the viewer.
 *
 * xl/workbook.xml lists the sheets and each sheet's cells are a part of
 * their own. Most text is kept once for the whole workbook, in
 * sharedStrings.xml, and styles.xml says how each cell looks: a date or a
 * percentage, bold, on a coloured fill. A cell shows the value Excel last
 * worked out for it - formulas are not worked out again - and charts,
 * pictures and borders are left to Excel.
 *
 * A sheet is read when it is shown: a workbook can hold far more than a
 * phone wants in memory at once.
 */

/**
 * Where a cell's text sits across it. [LEFT] and [RIGHT] are the sides of the
 * cell whichever way the sheet reads, as Excel's left and right are; [START]
 * and [END] follow the sheet - a number is at the end, the right in an
 * English sheet and the left in a Persian one.
 */
enum class CellAlign { START, END, LEFT, RIGHT, CENTER }

enum class CellVertical { TOP, CENTER, BOTTOM }

/** How a cell is shown. A workbook defines a handful, which its cells share. */
class CellLook(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** The font's size against the workbook's usual one: 2f is twice as big. */
    val scale: Float = 1f,
    /** The fill, as ARGB, or null for none. */
    val fill: Int? = null,
    /** How the workbook aligns the text, or null to align it by what the cell holds. */
    val align: CellAlign? = null,
    val vertical: CellVertical = CellVertical.BOTTOM,
    val wrap: Boolean = false,
    /**
     * Centred across the empty cells after it that are set the same way:
     * Excel's Center Across Selection, a title over a table without merging.
     */
    val centerAcross: Boolean = false,
    /** The text's own colour, as ARGB, or null for the usual one. Drawn only on a fill, which it was chosen for. */
    val color: Int? = null,
) {
    companion object {
        val PLAIN = CellLook()
    }
}

/**
 * A cell as shown: in column [column] of the sheet's shown columns, and
 * [span] of them wide when merged with the cells beside it.
 */
class SheetCell(
    val column: Int,
    val text: String,
    val look: CellLook,
    val align: CellAlign,
    val span: Int = 1,
    /** A merged cell going on into the row below: no line is drawn under it. */
    val continues: Boolean = false,
    /** A merged cell come down from the row above: its text is shown there. */
    val continued: Boolean = false,
    /**
     * Text that runs on over the empty cells beside it when too long for its
     * own, as Excel lets text do: away from the side it is aligned to, or
     * both ways when centred.
     */
    val spills: Boolean = false,
    /** A number, date or time: shown as ### rather than cut short when its column is too narrow. */
    val numeric: Boolean = false,
    /**
     * The value of a number in the General format, which Excel rounds to fit
     * its column - 0.333333 in a narrow one, 0.333333333 in a wide one -
     * rather than showing ###. Null for anything else.
     */
    val number: Double? = null,
)

/**
 * A row's [height], like a column's width, is in Excel's pixels, which the
 * viewer shows about the size of sp.
 *
 * [fits] is set for a row whose height nobody chose: Excel makes such a row
 * as tall as its wrapped text needs, working that out as it opens the file
 * when the file doesn't say - as one written by a program, not by Excel,
 * doesn't. Such a row is shown at least [height] tall, and taller if the
 * text needs it.
 */
class SheetRow(val number: Int, val height: Float, val cells: List<SheetCell>, val fits: Boolean = false)

/** [name] is the column's letters: A, B, ..., AA. */
class SheetColumn(val name: String, val width: Float)

class Worksheet(
    val columns: List<SheetColumn>,
    val rows: List<SheetRow>,
    /** Column A on the right, as sheets in Persian, Arabic and Hebrew often are. */
    val rightToLeft: Boolean,
    /** Charts or pictures, which the viewer leaves out. */
    val hasDrawings: Boolean,
    /** Rows, columns or text past what is shown were left out. */
    val truncated: Boolean,
)

/** A workbook's sheets, each read when [sheet] is asked for it. */
class Spreadsheet internal constructor(
    private val file: File,
    val sheetNames: List<String>,
    private val sheetParts: List<String>,
    private val strings: SharedStrings,
    private val styles: Styles,
    private val date1904: Boolean,
) {
    fun sheet(index: Int): Worksheet = ZipFile(file).use { zip ->
        val handler = SheetHandler(strings, styles, date1904)
        try {
            OoxmlParts(zip).parse(sheetParts[index], handler)
        } catch (e: SAXException) {
            if (!handler.stopped) throw e
        }
        handler.build(strings.truncated)
    }
}

object Xlsx {

    /** Rows past this are left out: a phone shows the beginning of a sheet of data, not all of it. */
    const val MAX_ROWS = 10_000

    /** Columns past this are left out - IV, as far as Excel itself went until 2007. */
    const val MAX_COLUMNS = 256

    /** Cells with something in them, past which a sheet is left out. */
    const val MAX_CELLS = 250_000

    /** The workbook's text, past which it is left out. */
    const val MAX_TEXT = 8_000_000

    fun read(file: File): Spreadsheet = ZipFile(file).use { zip ->
        val parts = OoxmlParts(zip)
        val main = parts.mainPart("xl/workbook.xml")
        val workbook = WorkbookHandler()
        if (!parts.parse(main, workbook)) throw IOException("Not an Excel workbook")
        val links = parts.links(main)
        fun linked(kind: String) = links.values.firstOrNull { it.kind == kind }?.target

        // Chart sheets and macro sheets have no cells to show.
        val sheets = workbook.sheets.mapNotNull { (name, id) ->
            links[id]?.takeIf { it.kind == "worksheet" }?.let { name to it.target }
        }
        val theme = linked("theme")?.let { part -> ThemeHandler().also { parts.parse(part, it) }.colors() }.orEmpty()
        val styles = linked("styles")?.let { part -> WorkbookStylesHandler().also { parts.parse(part, it) }.styles(theme) }
            ?: Styles.PLAIN
        val strings = linked("sharedStrings")?.let { part ->
            val handler = SharedStringsHandler(MAX_TEXT)
            try {
                parts.parse(part, handler)
            } catch (e: SAXException) {
                if (!handler.stopped) throw e
            }
            handler.strings()
        } ?: SharedStrings.NONE
        Spreadsheet(file, sheets.map { it.first }, sheets.map { it.second }, strings, styles, workbook.date1904)
    }
}

// ---- Text --------------------------------------------------------------------

/** The workbook's shared strings, kept end to end in one string rather than as an object each. */
internal class SharedStrings(
    private val all: String,
    private val ends: IntArray,
    private val count: Int,
    val truncated: Boolean,
) {
    operator fun get(index: Int): String? {
        if (index < 0 || index >= count) return null
        val start = if (index == 0) 0 else ends[index - 1]
        return unescape(all.substring(start, ends[index]))
    }

    companion object {
        val NONE = SharedStrings("", IntArray(0), 0, truncated = false)
    }
}

private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

/** Text with Excel's escapes for characters XML can't hold put back: _x000D_ is a carriage return. */
internal fun unescape(text: String): String =
    if ("_x" !in text) text else ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

private class SharedStringsHandler(private val maxChars: Int) : OoxmlHandler() {
    private val all = StringBuilder()
    private var ends = IntArray(256)
    private var count = 0
    private var inItem = false
    private var inText = false
    // Phonetic guides to Japanese text, not part of what the cell shows.
    private var phonetic = 0
    var stopped = false
        private set

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        when (localName) {
            "si" -> inItem = true
            "rPh" -> phonetic++
            "t" -> if (inItem && phonetic == 0) inText = true
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        when (localName) {
            "si" -> {
                if (count == ends.size) ends = ends.copyOf(count * 2)
                ends[count++] = all.length
                inItem = false
            }
            "rPh" -> phonetic--
            "t" -> inText = false
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (!inText) return
        if (all.length + length > maxChars) {
            stopped = true
            throw ReadEnough()
        }
        all.appendRange(ch, start, start + length)
    }

    fun strings() = SharedStrings(all.toString(), ends, count, truncated = stopped)
}

// ---- Workbook ----------------------------------------------------------------

private class WorkbookHandler : OoxmlHandler() {
    /** Each sheet's name and relationship id, in the workbook's order, without the hidden ones. */
    val sheets = ArrayList<Pair<String, String>>()
    var date1904 = false
        private set

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        when (localName) {
            "sheet" -> {
                val state = attributes.attr("state")
                if (state == "hidden" || state == "veryHidden") return
                val id = attributes.relationship("id") ?: return
                sheets += (attributes.attr("name") ?: "Sheet ${sheets.size + 1}") to id
            }
            "workbookPr" -> date1904 = attributes.attr("date1904").isTrue()
        }
    }
}

// ---- Styles ------------------------------------------------------------------

/** Each cell style's look and number format, by the number a cell gives in its s attribute. */
internal class Styles(private val looks: List<CellLook>, private val formats: List<NumberFormat>) {
    fun look(index: Int): CellLook = looks.getOrNull(index) ?: CellLook.PLAIN
    fun format(index: Int): NumberFormat = formats.getOrNull(index) ?: NumberFormat.GENERAL

    companion object {
        val PLAIN = Styles(emptyList(), emptyList())
    }
}

/** A colour as a style gives it: its own, one of the theme's, or one of the old 64. Tinted lighter or darker. */
private class ColorRef(val rgb: Int?, val theme: Int?, val indexed: Int?, val tint: Double) {
    fun resolve(themeColors: List<Int>): Int? {
        val base = rgb ?: theme?.let { themeColors.getOrNull(it) } ?: indexed?.let { INDEXED_COLORS.getOrNull(it) }
        return base?.let { if (tint == 0.0) it else tinted(it, tint) }
    }
}

private fun colorRef(attributes: Attributes) = ColorRef(
    rgb = attributes.attr("rgb")?.let(::opaque),
    theme = attributes.attr("theme")?.toIntOrNull(),
    indexed = attributes.attr("indexed")?.toIntOrNull(),
    tint = attributes.attr("tint")?.toDoubleOrNull() ?: 0.0,
)

/** "FF4472C4" or "4472C4" as an opaque colour. Excel draws every fill opaque, whatever alpha a file says. */
private fun opaque(hex: String): Int? = hex.takeLast(6).toIntOrNull(16)?.let { it or (0xFF shl 24) }

/** [color] made lighter (tint above 0) or darker (below), as Excel tints theme colours. */
internal fun tinted(color: Int, tint: Double): Int {
    val r = (color shr 16 and 0xFF) / 255.0
    val g = (color shr 8 and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    var lightness = (max + min) / 2
    val delta = max - min
    val saturation = if (delta == 0.0) 0.0 else delta / (1 - Math.abs(2 * lightness - 1))
    val hue = when {
        delta == 0.0 -> 0.0
        max == r -> 60 * (((g - b) / delta).mod(6.0))
        max == g -> 60 * ((b - r) / delta + 2)
        else -> 60 * ((r - g) / delta + 4)
    }
    lightness = if (tint < 0) lightness * (1 + tint) else lightness * (1 - tint) + tint
    val chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
    val x = chroma * (1 - Math.abs((hue / 60).mod(2.0) - 1))
    val m = lightness - chroma / 2
    val (r1, g1, b1) = when {
        hue < 60 -> Triple(chroma, x, 0.0)
        hue < 120 -> Triple(x, chroma, 0.0)
        hue < 180 -> Triple(0.0, chroma, x)
        hue < 240 -> Triple(0.0, x, chroma)
        hue < 300 -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    fun channel(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}

/** The 64 colours of Excel 97, which older workbooks pick by number. */
private val INDEXED_COLORS = intArrayOf(
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
    0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
    0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
    0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
    0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
    0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
).map { it or (0xFF shl 24) }

/** An on/off attribute such as b and i: on unless its val says off. */
private fun Attributes.on(): Boolean = attr("val").let { it != "0" && it != "false" }

private class WorkbookStylesHandler : OoxmlHandler() {
    private class Font {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var size = 11f
        var color: ColorRef? = null
    }

    private class Xf(val format: Int, val font: Int, val fill: Int) {
        var align: CellAlign? = null
        var vertical = CellVertical.BOTTOM
        var wrap = false
        var centerAcross = false
    }

    private val codes = HashMap<Int, String>()
    private val fonts = ArrayList<Font>()
    private val fills = ArrayList<ColorRef?>()
    private val xfs = ArrayList<Xf>()

    // Which list is being read: fonts and fills also come up inside
    // conditional formats, and xf inside cell styles, none of which count.
    private var list = ""
    private var font: Font? = null
    private var inFill = false
    private var solid = false
    private var fillColor: ColorRef? = null
    private var xf: Xf? = null

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        when (localName) {
            "numFmt" -> {
                val id = attributes.attr("numFmtId")?.toIntOrNull() ?: return
                codes[id] = attributes.attr("formatCode") ?: return
            }
            "fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "colors" -> list = localName
            "font" -> if (list == "fonts") font = Font().also { fonts += it }
            "b" -> font?.bold = attributes.on()
            "i" -> font?.italic = attributes.on()
            "u" -> font?.underline = attributes.attr("val") != "none"
            "strike" -> font?.strike = attributes.on()
            "sz" -> font?.let { f -> attributes.attr("val")?.toFloatOrNull()?.let { f.size = it } }
            // "auto" is the usual colour, and the old palette's 64 is too.
            "color" -> font?.let { f ->
                if (!attributes.attr("auto").isTrue() && attributes.attr("indexed") != "64") f.color = colorRef(attributes)
            }
            "fill" -> if (list == "fills") {
                inFill = true
                solid = false
                fillColor = null
            }
            "patternFill" -> if (inFill) solid = attributes.attr("patternType") == "solid"
            "fgColor" -> if (inFill) fillColor = colorRef(attributes)
            "xf" -> if (list == "cellXfs") {
                xf = Xf(
                    format = attributes.attr("numFmtId")?.toIntOrNull() ?: 0,
                    font = attributes.attr("fontId")?.toIntOrNull() ?: 0,
                    fill = attributes.attr("fillId")?.toIntOrNull() ?: 0,
                ).also { xfs += it }
            }
            "alignment" -> xf?.let { style ->
                val horizontal = attributes.attr("horizontal")
                // Left and right are the cell's sides even in a right-to-left
                // sheet; general follows what the cell holds.
                style.align = when (horizontal) {
                    "left" -> CellAlign.LEFT
                    "right" -> CellAlign.RIGHT
                    "center", "centerContinuous" -> CellAlign.CENTER
                    else -> null
                }
                style.centerAcross = horizontal == "centerContinuous"
                style.vertical = when (attributes.attr("vertical")) {
                    "top" -> CellVertical.TOP
                    "center", "justify", "distributed" -> CellVertical.CENTER
                    else -> CellVertical.BOTTOM
                }
                style.wrap = attributes.attr("wrapText").isTrue()
            }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        when (localName) {
            "font" -> font = null
            "fill" -> if (inFill) {
                fills += fillColor.takeIf { solid }
                inFill = false
            }
            "xf" -> xf = null
            list -> list = ""
        }
    }

    fun styles(theme: List<Int>): Styles {
        val baseSize = fonts.firstOrNull()?.size?.takeIf { it > 0f } ?: 11f
        val formats = HashMap<Int, NumberFormat>()
        val looks = xfs.map { style ->
            val font = fonts.getOrNull(style.font)
            CellLook(
                bold = font?.bold == true,
                italic = font?.italic == true,
                underline = font?.underline == true,
                strike = font?.strike == true,
                scale = ((font?.size ?: baseSize) / baseSize).coerceIn(0.5f, 4f),
                fill = fills.getOrNull(style.fill)?.resolve(theme),
                align = style.align,
                vertical = style.vertical,
                wrap = style.wrap,
                centerAcross = style.centerAcross,
                color = font?.color?.resolve(theme),
            )
        }
        val numberFormats = xfs.map { style ->
            formats.getOrPut(style.format) { NumberFormat.of(codes[style.format] ?: NumberFormat.builtIn(style.format)) }
        }
        return Styles(looks, numberFormats)
    }
}

/**
 * The theme's colours, in the order styles number them: the light and dark
 * background and text colours first, then the six accents and the links.
 */
private class ThemeHandler : OoxmlHandler() {
    private val named = HashMap<String, Int>()
    private var inScheme = false
    private var current: String? = null

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        when {
            localName == "clrScheme" -> inScheme = true
            inScheme && localName in SCHEME -> current = localName
            localName == "srgbClr" -> current?.let { name -> attributes.attr("val")?.let(::opaque)?.let { named[name] = it } }
            localName == "sysClr" -> current?.let { name -> attributes.attr("lastClr")?.let(::opaque)?.let { named[name] = it } }
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        when {
            localName == "clrScheme" -> inScheme = false
            localName == current -> current = null
        }
    }

    // Light before dark, the other way round from how the theme lists them.
    fun colors(): List<Int> = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        .map { named[it] ?: return emptyList() }

    companion object {
        val SCHEME = setOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
    }
}

// ---- Sheets ------------------------------------------------------------------

/** Column letters as a number, from 1: "A1" is 1, "AB12" is 28. Null if there are none. */
internal fun columnOf(reference: String): Int? {
    var n = 0
    var letters = 0
    for (char in reference) {
        val c = char.uppercaseChar()
        if (c == '$') continue
        if (c !in 'A'..'Z') break
        n = n * 26 + (c - 'A' + 1)
        if (++letters > 3) return null
    }
    return if (letters == 0) null else n
}

/** The row number in a cell reference: 12 in "AB12". */
internal fun rowOf(reference: String): Int? = reference.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()

/** A column's letters from its number: 1 is "A", 28 is "AB". */
internal fun columnName(number: Int): String {
    val letters = StringBuilder()
    var n = number
    while (n > 0) {
        letters.append('A' + (n - 1) % 26)
        n = (n - 1) / 26
    }
    return letters.reverse().toString()
}

private class SheetHandler(
    private val strings: SharedStrings,
    private val styles: Styles,
    private val date1904: Boolean,
) : OoxmlHandler() {

    private enum class Kind { TEXT, NUMBER, LOGICAL, ERROR }

    private class Raw(val row: Int, val column: Int, val text: String, val look: CellLook, val kind: Kind, val number: Double?)

    private class Columns(val first: Int, val last: Int, val width: Float?, val hidden: Boolean)

    private class Merge(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) {
        fun contains(row: Int, column: Int) = row in firstRow..lastRow && column in firstColumn..lastColumn
    }

    /** Stopped reading at the most rows or cells to show. */
    var stopped = false
        private set
    private var truncated = false
    private var rightToLeft = false
    private var sawView = false
    private var hasDrawings = false
    private var defaultWidth = DEFAULT_WIDTH
    private var defaultHeight = DEFAULT_HEIGHT
    private val columnRanges = ArrayList<Columns>()
    private val heights = HashMap<Int, Float>()
    /** Rows whose height somebody set, and the sheet's own if it was set for every row. */
    private val setHeights = HashSet<Int>()
    private var defaultHeightSet = false
    private val hiddenRows = HashSet<Int>()
    private val cells = ArrayList<Raw>()
    private val merges = ArrayList<Merge>()

    private var inSheetData = false
    private var row = 0
    private var column = 0
    private var type: String? = null
    private var style = 0
    private var inValue = false
    private var inInline = false
    private var inText = false
    private var phonetic = 0
    private val value = StringBuilder()
    private val inline = StringBuilder()

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (inSheetData) {
            when (localName) {
                "row" -> {
                    // Rows are numbered from 1: any other number is taken as none.
                    row = attributes.attr("r")?.toIntOrNull()?.takeIf { it > 0 } ?: (row + 1)
                    column = 0
                    if (row > Xlsx.MAX_ROWS) stop()
                    val height = attributes.attr("ht")?.toFloatOrNull()
                    height?.let { heights[row] = it * 4f / 3f }
                    if (height != null && attributes.attr("customHeight").isTrue()) setHeights += row
                    // A row with no height is as good as hidden.
                    if (attributes.attr("hidden").isTrue() || height == 0f) hiddenRows += row
                }
                "c" -> {
                    column = attributes.attr("r")?.let(::columnOf) ?: (column + 1)
                    type = attributes.attr("t")
                    style = attributes.attr("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    inline.setLength(0)
                }
                "v" -> inValue = true
                "is" -> inInline = true
                "rPh" -> phonetic++
                "t" -> if (inInline && phonetic == 0) inText = true
            }
            return
        }
        when (localName) {
            "sheetData" -> inSheetData = true
            "sheetView" -> if (!sawView) {
                sawView = true
                rightToLeft = attributes.attr("rightToLeft").isTrue()
            }
            "sheetFormatPr" -> {
                val width = attributes.attr("defaultColWidth")?.toFloatOrNull()?.let { it * 7f + 5f }
                    ?: attributes.attr("baseColWidth")?.toFloatOrNull()?.let { it * 7f + 8f }
                width?.let { defaultWidth = it }
                attributes.attr("defaultRowHeight")?.toFloatOrNull()?.let { defaultHeight = it * 4f / 3f }
                defaultHeightSet = attributes.attr("customHeight").isTrue()
            }
            "col" -> {
                val first = attributes.attr("min")?.toIntOrNull() ?: return
                val last = attributes.attr("max")?.toIntOrNull() ?: first
                // Characters of the font's widest digit, padding and all: 7 pixels each in Calibri.
                val width = attributes.attr("width")?.toFloatOrNull()?.let { it * 7f }
                columnRanges += Columns(first, last, width, attributes.attr("hidden").isTrue())
            }
            "mergeCell" -> attributes.attr("ref")?.let(::mergeOf)?.let { merges += it }
            "drawing" -> hasDrawings = true
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (!inSheetData) return
        when (localName) {
            "sheetData" -> inSheetData = false
            "c" -> finishCell()
            "v" -> inValue = false
            "is" -> inInline = false
            "t" -> inText = false
            "rPh" -> phonetic--
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inValue) {
            value.appendRange(ch, start, start + length)
        } else if (inText) {
            inline.appendRange(ch, start, start + length)
        }
    }

    private fun stop(): Nothing {
        stopped = true
        truncated = true
        throw ReadEnough()
    }

    private fun finishCell() {
        if (column > Xlsx.MAX_COLUMNS) {
            truncated = true
            return
        }
        val look = styles.look(style)
        val format = styles.format(style)
        val raw = value.toString()
        var number: Double? = null
        val (text, kind) = when (type) {
            "s" -> format.text(raw.trim().toIntOrNull()?.let { strings[it] }.orEmpty()) to Kind.TEXT
            "inlineStr" -> format.text(unescape(inline.toString())) to Kind.TEXT
            "str" -> format.text(unescape(raw)) to Kind.TEXT
            "b" -> (if (raw.isBlank()) "" else if (raw.trim() == "1") "TRUE" else "FALSE") to Kind.LOGICAL
            "e" -> raw.trim() to Kind.ERROR
            "d" -> isoDate(raw.trim())?.let { format.number(it, date1904) to Kind.NUMBER } ?: (raw.trim() to Kind.TEXT)
            else -> raw.trim().toDoubleOrNull()?.let { value ->
                if (format === NumberFormat.GENERAL) number = value
                format.number(value, date1904) to Kind.NUMBER
            } ?: ("" to Kind.TEXT)
        }
        // Kept empty when it carries a fill, or is part of a title centred across it.
        if (text.isEmpty() && look.fill == null && !look.centerAcross) return
        if (cells.size >= Xlsx.MAX_CELLS) stop()
        cells += Raw(row, column, text, look, kind, number)
    }

    /** A date cell's ISO 8601 value as the day number Excel would have kept instead. */
    private fun isoDate(text: String): Double? {
        val dateTime = runCatching { LocalDateTime.parse(text.removeSuffix("Z")) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()
            ?: return null
        val epoch = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
        val days = ChronoUnit.DAYS.between(epoch, dateTime.toLocalDate())
        return days + dateTime.toLocalTime().toSecondOfDay() / 86_400.0
    }

    private fun mergeOf(range: String): Merge? {
        val first = range.substringBefore(':')
        val last = range.substringAfter(':', first)
        val merge = Merge(
            firstRow = rowOf(first) ?: return null,
            firstColumn = columnOf(first) ?: return null,
            lastRow = rowOf(last) ?: return null,
            lastColumn = columnOf(last) ?: return null,
        )
        return merge.takeIf { it.lastRow >= it.firstRow && it.lastColumn >= it.firstColumn }
    }

    /**
     * Where [text] sits by its look, or else as Excel's General puts it: text
     * on the side it reads from - the left for English, the right for
     * Persian, whichever way the sheet reads - and numbers at the sheet's end.
     */
    private fun Kind.align(look: CellLook, text: String): CellAlign = look.align ?: when (this) {
        Kind.TEXT -> if (startsRightToLeft(text)) CellAlign.RIGHT else CellAlign.LEFT
        Kind.NUMBER -> CellAlign.END
        Kind.LOGICAL, Kind.ERROR -> CellAlign.CENTER
    }

    /**
     * The sheet as shown: from A1 to the last row and column with something
     * in them, less the hidden ones, with merged cells as one.
     */
    fun build(textTruncated: Boolean): Worksheet {
        var lastRow = 0
        var lastColumn = 0
        for (cell in cells) {
            lastRow = maxOf(lastRow, cell.row)
            lastColumn = maxOf(lastColumn, cell.column)
        }
        for (merge in merges) {
            lastRow = maxOf(lastRow, minOf(merge.lastRow, Xlsx.MAX_ROWS))
            lastColumn = maxOf(lastColumn, minOf(merge.lastColumn, Xlsx.MAX_COLUMNS))
        }

        val widths = FloatArray(lastColumn + 1) { defaultWidth }
        val hiddenColumns = BooleanArray(lastColumn + 1)
        for (range in columnRanges) {
            for (c in maxOf(range.first, 1)..minOf(range.last, lastColumn)) {
                range.width?.let { widths[c] = it }
                if (range.hidden) hiddenColumns[c] = true
            }
        }
        val shownColumn = IntArray(lastColumn + 1) { -1 }
        val columns = ArrayList<SheetColumn>()
        var total = 0f
        for (c in 1..lastColumn) {
            if (hiddenColumns[c] || widths[c] <= 0f) continue
            val width = minOf(widths[c], MAX_COLUMN_WIDTH)
            if (total + width > MAX_SHEET_WIDTH) {
                truncated = true
                break
            }
            total += width
            shownColumn[c] = columns.size
            columns += SheetColumn(columnName(c), width)
        }

        val shownRow = IntArray(lastRow + 1) { -1 }
        val numbers = ArrayList<Int>()
        for (r in 1..lastRow) {
            if (r in hiddenRows) continue
            shownRow[r] = numbers.size
            numbers += r
        }
        val rowCells = Array(numbers.size) { ArrayList<SheetCell>() }

        // Each merged cell as the shown rows it covers and its first shown column.
        class Shown(val merge: Merge, val column: Int, val span: Int, val rows: List<Int>)
        val shownMerges = ArrayList<Shown>()
        val covering = HashMap<Int, ArrayList<Shown>>()
        for (merge in merges) {
            val columnRange = merge.firstColumn..minOf(merge.lastColumn, lastColumn)
            val first = columnRange.firstOrNull { shownColumn[it] >= 0 } ?: continue
            val span = columnRange.count { shownColumn[it] >= 0 }
            val rows = (merge.firstRow..minOf(merge.lastRow, lastRow)).mapNotNull { r -> shownRow[r].takeIf { it >= 0 } }
            if (rows.isEmpty()) continue
            val shown = Shown(merge, shownColumn[first], span, rows)
            shownMerges += shown
            for (r in rows) covering.getOrPut(r) { ArrayList() } += shown
        }
        val anchors = HashMap<Long, Shown>()
        for (shown in shownMerges) anchors[key(shown.merge.firstRow, shown.merge.firstColumn)] = shown
        val anchorCells = HashMap<Shown, Raw>()

        for (cell in cells) {
            val anchor = anchors[key(cell.row, cell.column)]
            if (anchor != null) {
                anchorCells[anchor] = cell
                continue
            }
            val r = shownRow.getOrElse(cell.row) { -1 }
            val c = shownColumn.getOrElse(cell.column) { -1 }
            if (r < 0 || c < 0) continue
            if (covering[r]?.any { it.merge.contains(cell.row, cell.column) } == true) continue
            rowCells[r] += SheetCell(
                column = c,
                text = cell.text,
                look = cell.look,
                align = cell.kind.align(cell.look, cell.text),
                spills = cell.kind == Kind.TEXT && !cell.look.wrap,
                numeric = cell.kind == Kind.NUMBER,
                number = cell.number,
            )
        }
        for (shown in shownMerges) {
            val anchor = anchorCells[shown]
            val look = anchor?.look ?: CellLook.PLAIN
            val align = anchor?.let { it.kind.align(look, it.text) } ?: CellAlign.START
            for ((i, r) in shown.rows.withIndex()) {
                rowCells[r] += SheetCell(
                    column = shown.column,
                    text = anchor?.text.orEmpty(),
                    look = look,
                    align = align,
                    span = shown.span,
                    continues = i < shown.rows.size - 1,
                    continued = i > 0,
                    numeric = anchor?.kind == Kind.NUMBER,
                    number = anchor?.number,
                )
            }
        }

        val rows = numbers.mapIndexed { i, number ->
            val height = heights[number]
            SheetRow(
                number = number,
                height = height ?: defaultHeight,
                cells = rowCells[i].sortedBy { it.column },
                fits = if (height == null) !defaultHeightSet else number !in setHeights,
            )
        }
        return Worksheet(columns, rows, rightToLeft, hasDrawings, truncated || textTruncated)
    }

    private fun key(row: Int, column: Int) = (row.toLong() shl 32) or column.toLong()

    companion object {
        /** 8.43 characters of Calibri, Excel's usual column. */
        const val DEFAULT_WIDTH = 64f

        /** 15 points. */
        const val DEFAULT_HEIGHT = 20f

        const val MAX_COLUMN_WIDTH = 1000f

        /** The widest a sheet is drawn, comfortably within what one layout can measure. */
        const val MAX_SHEET_WIDTH = 40_000f
    }
}
