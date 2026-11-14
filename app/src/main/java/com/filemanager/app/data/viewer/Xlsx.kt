package com.filemanager.app.data.viewer

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
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
    /**
     * Every worksheet's part by its name in lower case - the hidden ones
     * too: a report's formulas count and add up a sheet of data that is
     * often kept out of sight.
     */
    private val everySheet: Map<String, String> = emptyMap(),
) {
    fun sheet(index: Int): Worksheet = ZipFile(file).use { zip ->
        val handler = SheetHandler(strings, styles, date1904)
        val parts = OoxmlParts(zip)
        try {
            parts.parse(sheetParts[index], handler)
        } catch (e: SAXException) {
            if (!handler.stopped) throw e
        }
        val cutShort = strings.truncated || parts.cutShort
        // With some of the workbook left out, cells that had something are
        // empty, and a formula reading them would be worked out wrong.
        if (!cutShort) {
            var sheetsRead = 0
            var cellsRead = 0
            lateinit var solving: Solving
            solving = Solving { name ->
                val part = everySheet[name]
                if (part == null || ++sheetsRead > MAX_OTHER_SHEETS) return@Solving null
                val other = SheetHandler(strings, styles, date1904)
                try {
                    parts.parse(part, other)
                } catch (e: SAXException) {
                    if (!other.stopped) return@Solving null
                }
                cellsRead += other.cellCount
                // Not all of it read, or more than is held for the sake of
                // another sheet's formulas: what it holds is not known.
                if (parts.cutShort || cellsRead > Xlsx.MAX_CELLS) null else other.formulaCells(solving)
            }
            handler.solve(solving, sheetNames[index])
        }
        val sheet = handler.build(cutShort)
        // Read as far as a file is, with nothing to show for it: not an empty sheet.
        if (parts.cutShort && sheet.rows.isEmpty()) throw TooLarge()
        sheet
    }

    private companion object {
        /** Other sheets read for the sake of one sheet's formulas. */
        const val MAX_OTHER_SHEETS = 8
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

    /** As many characters as Excel lets a cell hold. */
    const val MAX_CELL_TEXT = 32_767

    /**
     * The text of one sheet's cells, which each hold their own: a hundred
     * characters in every cell that is shown.
     */
    const val MAX_SHEET_TEXT = 24_000_000

    /** Different texts in a workbook; one each for every cell that can be shown, several times over. */
    const val MAX_STRINGS = 2_000_000

    /** Fonts, fills and cell formats: Excel's own limit is 65,490 formats. */
    const val MAX_STYLES = 100_000

    /** Sheets shown as tabs; no workbook kept by hand has as many. */
    const val MAX_SHEETS = 1_000

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
            handler.strings(cutShort = parts.cutShort)
        } ?: SharedStrings.NONE
        if (parts.cutShort && sheets.isEmpty()) throw TooLarge()
        val everySheet = HashMap<String, String>()
        for ((name, id) in workbook.every) {
            links[id]?.takeIf { it.kind == "worksheet" }?.let { everySheet.putIfAbsent(name.lowercase(), it.target) }
        }
        Spreadsheet(file, sheets.map { it.first }, sheets.map { it.second }, strings, styles, workbook.date1904, everySheet)
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
    /** The text numbered [index], as much of it as a cell holds. */
    operator fun get(index: Int): String? {
        if (index < 0 || index >= count) return null
        val start = if (index == 0) 0 else ends[index - 1]
        return unescape(all.substring(start, minOf(ends[index], start + Xlsx.MAX_CELL_TEXT)))
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
                if (count >= Xlsx.MAX_STRINGS) {
                    stopped = true
                    throw ReadEnough()
                }
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

    fun strings(cutShort: Boolean) = SharedStrings(all.toString(), ends, count, truncated = stopped || cutShort)
}

// ---- Workbook ----------------------------------------------------------------

private const val MAX_SHEET_NAME = 100

private class WorkbookHandler : OoxmlHandler() {
    /** Each sheet's name and relationship id, in the workbook's order, without the hidden ones. */
    val sheets = ArrayList<Pair<String, String>>()

    /** The same of every sheet, hidden or not: a formula can read a sheet that isn't shown. */
    val every = ArrayList<Pair<String, String>>()
    var date1904 = false
        private set

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        when (localName) {
            "sheet" -> {
                val state = attributes.attr("state")
                val id = attributes.relationship("id") ?: return
                // Excel's names are 31 characters at most.
                val name = attributes.attr("name")?.take(MAX_SHEET_NAME)
                if (name != null && every.size < Xlsx.MAX_SHEETS * 2) every += name to id
                if (state == "hidden" || state == "veryHidden") return
                if (sheets.size >= Xlsx.MAX_SHEETS) return
                sheets += (name ?: "Sheet ${sheets.size + 1}") to id
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

private const val MAX_FORMATS = 10_000
private const val MAX_FORMAT_CODE = 1_000

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
                val code = attributes.attr("formatCode") ?: return
                // Excel's are 255 characters at most, and a few hundred to a workbook.
                if (code.length <= MAX_FORMAT_CODE && (codes.size < MAX_FORMATS || id in codes)) codes[id] = code
            }
            "fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs", "colors" -> list = localName
            "font" -> if (list == "fonts" && fonts.size < Xlsx.MAX_STYLES) font = Font().also { fonts += it }
            "b" -> font?.bold = attributes.on()
            "i" -> font?.italic = attributes.on()
            "u" -> font?.underline = attributes.attr("val") != "none"
            "strike" -> font?.strike = attributes.on()
            "sz" -> font?.let { f -> attributes.attr("val")?.toFloatOrNull()?.let { f.size = it } }
            // "auto" is the usual colour, and the old palette's 64 is too.
            "color" -> font?.let { f ->
                if (!attributes.attr("auto").isTrue() && attributes.attr("indexed") != "64") f.color = colorRef(attributes)
            }
            "fill" -> if (list == "fills" && fills.size < Xlsx.MAX_STYLES) {
                inFill = true
                solid = false
                fillColor = null
            }
            "patternFill" -> if (inFill) solid = attributes.attr("patternType") == "solid"
            "fgColor" -> if (inFill) fillColor = colorRef(attributes)
            "xf" -> if (list == "cellXfs" && xfs.size < Xlsx.MAX_STYLES) {
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

/**
 * What working out one sheet's formulas shares with the other sheets they
 * read: how many cells have been looked at, how far one formula resting on
 * another has been followed, and which time round the formulas it is.
 * [read] gets another sheet by its name in lower case, or null if it can't
 * be had; each is asked for once.
 */
private class Solving(private val read: (String) -> FormulaCells?) {
    var looked = 0
    var depth = 0
    var round = 0
    private val sheets = HashMap<String, FormulaCells?>()

    fun sheet(name: String): FormulaCells? {
        val key = name.lowercase()
        if (key !in sheets) sheets[key] = read(key)
        return sheets[key]
    }

    /** The sheet being worked out, under its own name: already read, and not to be read again. */
    fun known(name: String, cells: FormulaCells) {
        sheets[name.lowercase()] = cells
    }
}

private class SheetHandler(
    private val strings: SharedStrings,
    private val styles: Styles,
    private val date1904: Boolean,
) : OoxmlHandler() {

    private enum class Kind { TEXT, NUMBER, LOGICAL, ERROR }

    private class Raw(
        val row: Int,
        val column: Int,
        var text: String,
        val look: CellLook,
        var kind: Kind,
        var number: Double?,
        /** The number the cell holds, for a formula that reads it; NaN for a cell that holds none. */
        var value: Double = Double.NaN,
        /** A formula that came with no result, worked out once the sheet is read, and how its result is to be written. */
        val formula: String? = null,
        val format: NumberFormat? = null,
    ) {
        /** For such a formula, how far working it out has got. */
        var state = WAITING

        /** What it comes to; null, once [state] is [DONE], if it uses something not worked out here. */
        var result: CellValue? = null

        /** The round of working out in which it was last put off, so as not to be tried twice in one. */
        var putOff = -1
    }

    private class Columns(val first: Int, val last: Int, val width: Float?, val hidden: Boolean)

    private class Merge(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int) {
        fun contains(row: Int, column: Int) = row in firstRow..lastRow && column in firstColumn..lastColumn
    }

    /** Stopped reading at the most rows or cells to show. */
    var stopped = false
        private set
    private var truncated = false

    /** The first row not all read, once reading [stopped]; and whether cells past the last column shown were left out. */
    private var unreadFromRow = Int.MAX_VALUE
    private var columnsCut = false
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
    private var rowsSeen = 0
    private var cellsSeen = 0
    private var textChars = 0L
    private var type: String? = null
    private var style = 0
    private var inValue = false
    private var inInline = false
    private var inText = false
    private var inFormula = false
    private var formulaKind: String? = null
    private val formula = StringBuilder()
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
                    // A row comes once: more of them than there are rows to
                    // show is the same few over and over, read to no end.
                    if (row > Xlsx.MAX_ROWS || ++rowsSeen > Xlsx.MAX_ROWS) stop()
                    val height = attributes.attr("ht")?.toFloatOrNull()
                    height?.let { heights[row] = it * 4f / 3f }
                    if (height != null && attributes.attr("customHeight").isTrue()) setHeights += row
                    // A row with no height is as good as hidden.
                    if (attributes.attr("hidden").isTrue() || height == 0f) hiddenRows += row
                }
                "c" -> {
                    if (++cellsSeen > MAX_CELLS_READ) stop()
                    column = attributes.attr("r")?.let(::columnOf) ?: (column + 1)
                    type = attributes.attr("t")
                    style = attributes.attr("s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    inline.setLength(0)
                    formula.setLength(0)
                    formulaKind = null
                }
                "f" -> {
                    inFormula = true
                    formulaKind = attributes.attr("t")
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
                // No more of them than a sheet has columns.
                if (columnRanges.size < MAX_COLUMN_RANGES) columnRanges += Columns(first, last, width, attributes.attr("hidden").isTrue())
            }
            "mergeCell" -> if (merges.size < MAX_MERGES) attributes.attr("ref")?.let(::mergeOf)?.let { merges += it }
            "drawing" -> hasDrawings = true
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (!inSheetData) return
        when (localName) {
            "sheetData" -> inSheetData = false
            "c" -> finishCell()
            "f" -> inFormula = false
            "v" -> inValue = false
            "is" -> inInline = false
            "t" -> inText = false
            "rPh" -> phonetic--
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        // No more than a cell holds: what is past it would never be shown.
        val into = if (inValue) value else if (inText) inline else if (inFormula) formula else return
        val room = (if (inFormula) MAX_FORMULA else Xlsx.MAX_CELL_TEXT) - into.length
        if (room > 0) into.appendRange(ch, start, start + minOf(length, room))
    }

    private fun stop(): Nothing {
        stopped = true
        truncated = true
        // The row being read is not all in, and nothing after it is.
        unreadFromRow = row
        throw ReadEnough()
    }

    private fun finishCell() {
        if (column > Xlsx.MAX_COLUMNS) {
            truncated = true
            columnsCut = true
            return
        }
        val look = styles.look(style)
        val format = styles.format(style)
        val raw = value.toString()
        var number: Double? = null
        var held = Double.NaN
        // A formula and nothing beside it: written by a program, which
        // leaves the working out to Excel. Text that is empty is a result -
        // a formula can come to no text at all - a number that is missing
        // is not.
        val unsolved = formula.isNotEmpty() && raw.isBlank() && (type == null || type == "n")
        val (text, kind) = when (type) {
            "s" -> format.text(raw.trim().toIntOrNull()?.let { strings[it] }.orEmpty()) to Kind.TEXT
            "inlineStr" -> format.text(unescape(inline.toString())) to Kind.TEXT
            "str" -> format.text(unescape(raw)) to Kind.TEXT
            "b" -> (if (raw.isBlank()) "" else if (raw.trim() == "1") "TRUE" else "FALSE") to Kind.LOGICAL
            "e" -> raw.trim() to Kind.ERROR
            "d" -> isoDate(raw.trim())?.let {
                held = it
                format.number(it, date1904) to Kind.NUMBER
            } ?: (raw.trim() to Kind.TEXT)
            // "1e999" and "NaN" are written as a number is, and are none.
            else -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { value ->
                if (format === NumberFormat.GENERAL) number = value
                held = value
                format.number(value, date1904) to Kind.NUMBER
            } ?: ("" to Kind.TEXT)
        }
        // Kept empty when it carries a fill, or is part of a title centred across it.
        if (!unsolved && text.isEmpty() && look.fill == null && !look.centerAcross) return
        // Each cell holds its text itself, so one long text in every cell
        // of a sheet is that text a quarter of a million times.
        textChars += text.length + if (unsolved) formula.length else 0
        if (cells.size >= Xlsx.MAX_CELLS || textChars > Xlsx.MAX_SHEET_TEXT) stop()
        cells += when {
            !unsolved -> Raw(row, column, text, look, kind, number, held)
            // One formula for many cells, each with its own answer: shown as it is written.
            formulaKind == "array" || formulaKind == "dataTable" -> Raw(row, column, "=$formula", look, Kind.TEXT, null)
            else -> Raw(row, column, "", look, Kind.TEXT, null, formula = formula.toString(), format = format)
        }
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
    /*
     * Formulas that came with no result are worked out here, once every
     * cell they might read is in: see Formulas. One that uses something not
     * worked out is shown as it is written, with its = - what the file says,
     * rather than a blank where a total should be.
     */
    private inner class Solver(private val solving: Solving) : FormulaCells {
        private val byRow = HashMap<Int, ArrayList<Raw>>()
        private val byCell = HashMap<Long, Raw>()
        override val lastRow: Int = cells.maxOfOrNull { it.row } ?: 0
        override val lastColumn: Int = cells.maxOfOrNull { it.column } ?: 0

        // A sheet cut short at the most rows or columns shown: a total that
        // reaches past them would be the total of the part that was read.
        override val readThroughRow: Int = if (unreadFromRow == Int.MAX_VALUE) Int.MAX_VALUE else unreadFromRow - 1
        override val readThroughColumn: Int = if (columnsCut) Xlsx.MAX_COLUMNS else Int.MAX_VALUE
        private val formulas = Formulas(this)

        override fun sheet(name: String): FormulaCells? = solving.sheet(name)

        init {
            for (cell in cells) {
                byRow.getOrPut(cell.row) { ArrayList() } += cell
                byCell[key(cell.row, cell.column)] = cell
            }
        }

        override fun at(row: Int, column: Int): CellValue {
            look()
            return byCell[key(row, column)]?.let(::valueOf) ?: CellValue.Empty
        }

        override fun within(firstRow: Int, firstColumn: Int, lastRow: Int, lastColumn: Int, each: (CellValue) -> Unit) {
            for (row in firstRow..lastRow) {
                look()
                for (cell in byRow[row] ?: continue) {
                    if (cell.column < firstColumn || cell.column > lastColumn) continue
                    look()
                    valueOf(cell).let { if (it != CellValue.Empty) each(it) }
                }
            }
        }

        /** One more cell looked at, of as many as are for one sheet's formulas. */
        private fun look() {
            if (++solving.looked > MAX_FORMULA_CELLS) throw NotWorkedOut()
            // The screen that asked for the sheet has gone: nobody is waiting for this.
            if (solving.looked and 0xFFFF == 0 && Thread.currentThread().isInterrupted) throw InterruptedIOException()
        }

        private fun valueOf(cell: Raw): CellValue = when {
            cell.formula == null -> when {
                !cell.value.isNaN() -> CellValue.Number(cell.value)
                cell.text.isEmpty() -> CellValue.Empty
                cell.kind == Kind.LOGICAL -> CellValue.Logical(cell.text == "TRUE")
                cell.kind == Kind.ERROR -> CellValue.Error(cell.text)
                else -> CellValue.Text(cell.text)
            }
            cell.state == DONE -> cell.result ?: throw NotWorkedOut()
            // Resting on itself, by however long a way round.
            cell.state == WORKING -> throw NotWorkedOut()
            // A chain of formulas each resting on the next is followed only
            // so far in one go: the far end is reached first, another time.
            cell.putOff == solving.round || solving.depth >= MAX_FORMULA_DEPTH -> throw NotWorkedOut(Formulas.LATER)
            else -> {
                workOut(cell)
                cell.result ?: throw NotWorkedOut()
            }
        }

        private fun workOut(cell: Raw) {
            cell.state = WORKING
            solving.depth++
            try {
                cell.result = formulas.evaluate(cell.formula.orEmpty())
                cell.state = DONE
            } catch (e: NotWorkedOut) {
                cell.state = WAITING
                cell.putOff = solving.round
                throw e
            } finally {
                solving.depth--
            }
        }

        fun solve(unsolved: List<Raw>) {
            // Down the sheet and then back up it: a running total rests on
            // the row before it, a list totalled at its head on the rows
            // after - either way round, every formula finds the ones it
            // rests on already worked out.
            var order = unsolved
            while (solving.round < MAX_FORMULA_ROUNDS && order.isNotEmpty()) {
                val later = ArrayList<Raw>()
                for (cell in order) {
                    if (cell.state != WAITING) continue
                    if (cell.putOff == solving.round) {
                        later += cell
                        continue
                    }
                    try {
                        workOut(cell)
                    } catch (e: NotWorkedOut) {
                        later += cell
                    }
                }
                order = later.asReversed()
                solving.round++
            }
            for (cell in unsolved) write(cell)
        }

        private fun write(cell: Raw) {
            val format = cell.format ?: NumberFormat.GENERAL
            fun number(value: Double) {
                cell.text = format.number(value, date1904)
                cell.kind = Kind.NUMBER
                cell.value = value
                if (format === NumberFormat.GENERAL) cell.number = value
            }
            when (val result = cell.result.takeIf { cell.state == DONE }) {
                // Shown as it is written: see build.
                null -> Unit
                is CellValue.Number -> number(result.value)
                CellValue.Empty -> number(0.0)
                is CellValue.Text -> cell.text = format.text(result.text)
                is CellValue.Logical -> {
                    cell.text = if (result.value) "TRUE" else "FALSE"
                    cell.kind = Kind.LOGICAL
                }
                is CellValue.Error -> {
                    cell.text = result.code
                    cell.kind = Kind.ERROR
                }
            }
        }
    }

    /** How many cells were kept: what it costs to hold this sheet for the sake of another's formulas. */
    val cellCount: Int get() = cells.size

    /** This sheet's cells as a formula on another sheet sees them. */
    fun formulaCells(solving: Solving): FormulaCells = Solver(solving)

    /**
     * Works out the formulas that came with no result. [name] is the
     * sheet's own, by which its formulas may call it as they call another.
     */
    fun solve(solving: Solving, name: String) {
        val unsolved = cells.filter { it.formula != null }
        if (unsolved.isEmpty()) return
        val solver = Solver(solving)
        solving.known(name, solver)
        solver.solve(unsolved)
    }

    fun build(textTruncated: Boolean): Worksheet {
        val unsolved = cells.filter { it.formula != null }
        if (unsolved.isNotEmpty()) {
            // One not worked out - none are, when some of the workbook was
            // left out - is shown as it is written.
            for (cell in unsolved) if (cell.state != DONE || cell.result == null) cell.text = "=" + cell.formula
            // One that came to no text at all is an empty cell like any other.
            cells.removeAll { it.formula != null && it.text.isEmpty() && it.look.fill == null && !it.look.centerAcross }
        }
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
        var mergedRows = 0
        val begun = HashSet<Long>()
        for (merge in merges) {
            // One merged cell to a corner: the same said again is the same.
            if (!begun.add(key(merge.firstRow, merge.firstColumn))) continue
            val columnRange = merge.firstColumn..minOf(merge.lastColumn, lastColumn)
            val first = columnRange.firstOrNull { shownColumn[it] >= 0 } ?: continue
            val span = columnRange.count { shownColumn[it] >= 0 }
            // Merged cells lie side by side, never over one another: a sheet
            // with more of them down its rows than it could hold that way, or
            // more in one row than there are columns, is damaged - or made to
            // take all the memory there is - and the rest are left unmerged.
            val down = minOf(merge.lastRow, lastRow) - merge.firstRow + 1
            if (down <= 0 || mergedRows + down > MAX_MERGED_ROWS) continue
            // Counted whether or not it is kept: looking at it is the work.
            mergedRows += down
            val rows = (merge.firstRow..minOf(merge.lastRow, lastRow)).mapNotNull { r -> shownRow[r].takeIf { it >= 0 } }
            if (rows.isEmpty() || rows.any { (covering[it]?.size ?: 0) >= Xlsx.MAX_COLUMNS }) continue
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

        /** As long as Excel lets a formula be. */
        const val MAX_FORMULA = 8_192

        /** Cells looked at in working out one sheet's formulas, past which the rest are shown as written. */
        const val MAX_FORMULA_CELLS = 5_000_000

        /** How many formulas, each resting on the next, are followed in one go. */
        const val MAX_FORMULA_DEPTH = 40

        /** Times round the formulas left over from the time before. */
        const val MAX_FORMULA_ROUNDS = 6

        const val WAITING = 0
        const val WORKING = 1
        const val DONE = 2

        /** Cells read, empty ones too, past which a sheet is cut short. */
        const val MAX_CELLS_READ = 4_000_000

        /** Excel's own most columns: no sheet says how wide its columns are more times than that. */
        const val MAX_COLUMN_RANGES = 16_384

        const val MAX_MERGES = 50_000

        /** The rows all of a sheet's merged cells cover, added up. */
        const val MAX_MERGED_ROWS = 300_000

        /** The widest a sheet is drawn, comfortably within what one layout can measure. */
        const val MAX_SHEET_WIDTH = 40_000f
    }
}
