package com.filemanager.app.data.viewer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/*
 * Reading a Rich Text Format document for the viewer: an .rtf, or a .doc
 * that is one, as many saved by other programs are.
 *
 * RTF is text with control words: \par ends a paragraph, \b turns bold on,
 * braces group what a control word applies to. Groups that are not the
 * document's text - colours, pictures, a field's instructions - are read
 * past; the stylesheet is read only for which styles are headings, the font
 * table for which alphabet each font writes, and the marker a list
 * paragraph carries in its \listtext is kept as its bullet or number.
 *
 * Letters outside English are written either as Unicode (\u1587) or as
 * bytes (\'d3) in the code page of the font in use - Cyrillic, Arabic,
 * Greek - so which font is in use decides what a byte means.
 */
object Rtf {

    /** Larger than this, a file is mostly pictures; it is not read for its text. */
    private const val MAX_BYTES = 128L * 1024 * 1024

    fun read(file: File, maxChars: Int = Docx.MAX_CHARS): DocxDocument {
        if (file.length() > MAX_BYTES) throw IOException("Too large")
        return RtfParser(file.readBytes(), maxChars).parse()
    }
}

/** Destinations that hold no text of the document's own, read past whole. */
private val RTF_SKIPPED = setOf(
    "colortbl", "info", "pict", "object", "objdata", "header", "footer", "headerl", "headerr",
    "headerf", "footerl", "footerr", "footerf", "footnote", "annotation", "fldinst", "listtable",
    "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
    "latentstyles", "datastore", "pgdsctbl", "filetbl", "revtbl", "nonshppict", "bkmkstart", "bkmkend",
    "atnid", "atnauthor", "fldtype", "mmathPr", "shpinst", "xe", "tc", "docvar", "template", "falt",
    "panose", "fname", "leveltext", "levelnumbers", "listname", "picprop", "blipuid", "userprops",
)

private class RtfGroup(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strike: Boolean = false,
    var hidden: Boolean = false,
    /** Deleted with changes tracked: still in the file, not in the document. */
    var deleted: Boolean = false,
    /** Neither text nor a list marker: read past. */
    var skip: Boolean = false,
    /** How many characters stand in for one written as \u, for older readers. */
    var uc: Int = 1,
    var destination: String? = null,
    /** The font in use, by its number in the font table; null for the document's usual one. */
    var font: Int? = null,
    /** No control word or text in it yet: the first says what the group is. */
    var atStart: Boolean = true,
    /** Started with \*: a destination to read past unless known. */
    var ignorable: Boolean = false,
) {
    fun child() = RtfGroup(bold, italic, underline, strike, hidden, deleted, skip, uc, destination, font)
}

private class RtfParser(private val input: ByteArray, private val maxChars: Int) {
    private val groups = arrayListOf(RtfGroup())
    private val state: RtfGroup get() = groups.last()
    private var charset: Charset = Charset.forName("windows-1252")
    private val pending = ByteArrayOutputStream()
    private var skipFallback = 0

    private val out = DocBuilder()
    private var written = 0

    // The paragraph being read.
    private var align = DocAlign.START
    private var inTable = false
    private var rtl: Boolean? = null
    private var style = 0
    private var level = 0
    private var marker: String? = null
    private val markerText = StringBuilder()

    // The stylesheet: each paragraph style's number and its name.
    private val headings = HashMap<Int, DocStyle>()
    private var stylesheetDepth = -1
    private var styleNumber = 0
    private val styleName = StringBuilder()

    // The font table: the code page of each font that isn't the document's own.
    private val fontCharsets = HashMap<Int, Charset>()
    private var fontTableDepth = -1
    private var fontNumber = 0
    private var defaultFont: Int? = null

    fun parse(): DocxDocument {
        var i = 0
        while (i < input.size && written < maxChars) {
            when (val byte = input[i].toInt() and 0xFF) {
                '{'.code -> {
                    flush()
                    groups += state.child()
                    i++
                }
                '}'.code -> {
                    flush()
                    endGroup()
                    i++
                }
                '\\'.code -> i = control(i)
                '\r'.code, '\n'.code -> i++
                else -> {
                    state.atStart = false
                    if (skipFallback > 0) skipFallback-- else pending.write(byte)
                    i++
                }
            }
        }
        flush()
        if (out.hasText()) endParagraph(endsCell = false)
        return out.build(truncated = written >= maxChars)
    }

    /** A control word or symbol at [at], acted on; where reading goes on. */
    private fun control(at: Int): Int {
        val next = input.getOrNull(at + 1)?.toInt()?.and(0xFF) ?: return at + 1
        if (next.toChar().isLetter() && next < 0x80) {
            var end = at + 1
            while (end < input.size && (input[end].toInt() and 0xFF).let { it < 0x80 && it.toChar().isLetter() }) end++
            val name = String(input, at + 1, end - at - 1, Charsets.US_ASCII)
            var param: Int? = null
            var digits = end
            if (digits < input.size && input[digits] == '-'.code.toByte()) digits++
            while (digits < input.size && (input[digits].toInt() and 0xFF) in '0'.code..'9'.code) digits++
            if (digits > end && !(digits == end + 1 && input[end] == '-'.code.toByte())) {
                param = String(input, end, digits - end, Charsets.US_ASCII).toIntOrNull()
                end = digits
            }
            // A space ends a control word, and is part of it.
            if (end < input.size && input[end] == ' '.code.toByte()) end++
            // Raw bytes of a picture follow, as many as it says: not RTF, and
            // any brace or backslash among them is not one.
            if (name == "bin") return minOf(input.size.toLong(), end.toLong() + (param ?: 0).coerceAtLeast(0)).toInt()
            word(name, param)
            return end
        }
        when (next.toChar()) {
            '\'' -> {
                val hex = String(input, at + 2, minOf(2, input.size - at - 2), Charsets.US_ASCII).toIntOrNull(16)
                state.atStart = false
                if (hex != null) {
                    if (skipFallback > 0) skipFallback-- else pending.write(hex)
                }
                return at + 4
            }
            '*' -> {
                state.ignorable = true
                return at + 2
            }
            '\\', '{', '}' -> {
                flush()
                emit(next.toChar())
            }
            '~' -> {
                flush()
                emit('\u00A0')
            }
            '_' -> {
                flush()
                emit('-')
            }
            // A backslash at the end of a line is a \par - in the text, that is.
            '\r', '\n' -> {
                flush()
                if (!state.skip && stylesheetDepth < 0 && state.destination != "listtext") endParagraph(endsCell = false)
            }
        }
        return at + 2
    }

    private fun word(name: String, param: Int?) {
        flush()
        val group = state
        if (group.atStart) {
            group.atStart = false
            when {
                name == "stylesheet" -> {
                    group.skip = true
                    group.destination = name
                    stylesheetDepth = groups.lastIndex
                    return
                }
                name == "fonttbl" -> {
                    group.skip = true
                    group.destination = name
                    fontTableDepth = groups.lastIndex
                    return
                }
                name == "listtext" -> {
                    group.destination = name
                    markerText.setLength(0)
                }
                name == "fldrslt" -> group.skip = false
                name in RTF_SKIPPED || group.ignorable -> {
                    group.skip = true
                    group.destination = name
                    return
                }
            }
        }
        if (stylesheetDepth >= 0) {
            if (name == "s") styleNumber = param ?: 0
            return
        }
        if (fontTableDepth >= 0) {
            when (name) {
                "f" -> fontNumber = param ?: 0
                "fcharset" -> fontCharset(param)?.let { fontCharsets[fontNumber] = it }
            }
            return
        }
        if (group.skip) return
        // A list item's marker is written as a little paragraph of its own,
        // \pard and all: only its characters count, not its properties.
        if (group.destination == "listtext") {
            if (name == "u") param?.let { code ->
                emit((if (code < 0) code + 65536 else code).toChar())
                skipFallback = group.uc
            }
            if (name == "f") group.font = param
            return
        }
        val on = param == null || param != 0
        when (name) {
            "par", "sect" -> endParagraph(endsCell = false)
            "cell", "nestcell" -> endParagraph(endsCell = true)
            "row", "nestrow" -> out.endRow()
            "intbl" -> inTable = true
            "pard" -> {
                align = DocAlign.START
                inTable = false
                rtl = null
                style = 0
                level = 0
            }
            "s" -> style = param ?: 0
            "ilvl" -> level = (param ?: 0).coerceIn(0, 8)
            "ql" -> align = DocAlign.LEFT
            "qc" -> align = DocAlign.CENTER
            "qr" -> align = DocAlign.RIGHT
            "qj", "qd" -> align = DocAlign.JUSTIFY
            "rtlpar" -> rtl = true
            "ltrpar" -> rtl = false
            "plain" -> {
                group.bold = false
                group.italic = false
                group.underline = false
                group.strike = false
                group.hidden = false
                group.deleted = false
                group.font = null
            }
            "f" -> group.font = param
            "deff" -> defaultFont = param
            "b" -> group.bold = on
            "i" -> group.italic = on
            "ul", "uld", "uldb", "ulw", "ulwave", "uldash", "ulth" -> group.underline = on
            "ulnone" -> group.underline = false
            "strike", "striked" -> group.strike = on
            "v" -> group.hidden = on
            "deleted" -> group.deleted = on
            "uc" -> group.uc = (param ?: 1).coerceAtLeast(0)
            "u" -> {
                val code = param ?: return
                emit((if (code < 0) code + 65536 else code).toChar())
                skipFallback = group.uc
            }
            "ansicpg" -> param?.let { page -> charsetNamed("windows-$page", "cp$page", "x-windows-$page")?.let { charset = it } }
            "mac" -> charsetNamed("x-MacRoman", "MacRoman")?.let { charset = it }
            "pc" -> charsetNamed("IBM437")?.let { charset = it }
            "pca" -> charsetNamed("IBM850")?.let { charset = it }
            "tab" -> emit('\t')
            "line" -> emit('\n')
            "emdash" -> emit('—')
            "endash" -> emit('–')
            "bullet" -> emit('•')
            "lquote" -> emit('‘')
            "rquote" -> emit('’')
            "ldblquote" -> emit('“')
            "rdblquote" -> emit('”')
            "emspace", "enspace", "qmspace" -> emit(' ')
        }
    }

    private fun endGroup() {
        if (groups.size == 1) return
        val ended = groups.removeAt(groups.lastIndex)
        when {
            // A style in the stylesheet: its number, and its name up to the ";".
            stylesheetDepth >= 0 && groups.size == stylesheetDepth + 1 -> {
                styleNamed(styleName.toString().substringBefore(';'))?.let { headings[styleNumber] = it }
                styleName.setLength(0)
                styleNumber = 0
            }
            stylesheetDepth >= 0 && groups.size == stylesheetDepth -> stylesheetDepth = -1
            fontTableDepth >= 0 && groups.size == fontTableDepth -> fontTableDepth = -1
            ended.destination == "listtext" && state.destination != "listtext" -> {
                // A bullet from a symbol font comes through as a dot or a letter.
                marker = markerText.toString().trim().let { text ->
                    when {
                        text.isEmpty() -> null
                        text.length == 1 && (text[0] == '·' || text[0] in '\uF000'..'\uF0FF' || text[0].isLetter() && !text[0].isUpperCase()) -> "•"
                        else -> text
                    }
                }
            }
        }
    }

    /**
     * The bytes written as \'hh or as they are, decoded together - two make
     * one character in some code pages - in the code page of the font in use.
     */
    private fun flush() {
        if (pending.size() == 0) return
        val text = String(pending.toByteArray(), fontCharsets[state.font ?: defaultFont] ?: charset)
        pending.reset()
        for (char in text) emit(char)
    }

    private fun charsetNamed(vararg names: String): Charset? =
        names.firstNotNullOfOrNull { name -> runCatching { Charset.forName(name) }.getOrNull() }

    /**
     * The code page of a font whose \fcharset is [number], or null for one
     * that writes in the document's own: 204 is Cyrillic, 178 Arabic and
     * Persian, 161 Greek.
     */
    private fun fontCharset(number: Int?): Charset? = when (number) {
        128 -> charsetNamed("windows-31j", "Shift_JIS")
        129 -> charsetNamed("windows-949", "x-windows-949", "EUC-KR")
        130 -> charsetNamed("x-Johab")
        134 -> charsetNamed("GBK", "GB2312")
        136 -> charsetNamed("windows-950", "x-windows-950", "Big5")
        161 -> charsetNamed("windows-1253")
        162 -> charsetNamed("windows-1254")
        163 -> charsetNamed("windows-1258")
        177 -> charsetNamed("windows-1255")
        178 -> charsetNamed("windows-1256")
        186 -> charsetNamed("windows-1257")
        204 -> charsetNamed("windows-1251")
        222 -> charsetNamed("windows-874", "x-windows-874", "TIS-620")
        238 -> charsetNamed("windows-1250")
        else -> null
    }

    private fun emit(char: Char) {
        val group = state
        group.atStart = false
        when {
            stylesheetDepth >= 0 -> styleName.append(char)
            group.destination == "listtext" -> markerText.append(char)
            group.skip || group.hidden || group.deleted -> Unit
            else -> {
                written++
                out.append(char, group.bold, group.italic, group.underline, group.strike)
            }
        }
    }

    private fun endParagraph(endsCell: Boolean) {
        val listed = marker
        marker = null
        out.paragraph(
            style = headings[style] ?: DocStyle.BODY,
            align = align,
            level = if (listed != null) level else 0,
            rtl = rtl,
            inTable = inTable || endsCell,
            endsCell = endsCell,
            marker = { listed },
        )
    }
}
