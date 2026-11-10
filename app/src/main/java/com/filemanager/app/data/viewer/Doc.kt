package com.filemanager.app.data.viewer

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/*
 * Reading a Word document of the kind before 2007 (.doc) for the viewer.
 *
 * A .doc is a compound file (see CompoundFile) whose WordDocument stream
 * holds the text and a table stream says where: a piece table maps the
 * text's characters to where they are kept, 8-bit or UTF-16. Paragraph
 * properties - in a table or not, the style, the alignment, a list - and
 * character formatting are in runs of their own, keyed by file offset. What
 * comes out is what the Word viewer shows for a .docx: headings, paragraphs,
 * lists and tables in reading order, with bold, italic, underline and
 * strikethrough. Pictures, fonts and the page layout are left to Word.
 *
 * A file called .doc is often something else - RTF, or a .docx renamed - so
 * what is in it decides how it is read, not its name. [MS-DOC] is the format.
 */
object Doc {

    fun read(file: File, maxChars: Int = Docx.MAX_CHARS): DocxDocument {
        val head = ByteArray(8)
        RandomAccessFile(file, "r").use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
        }
        return when {
            head.startsWith(OLE) -> WordBinary(maxChars).read(file)
            head.startsWith(ZIP) -> Docx.read(file, maxChars)
            head.startsWith(RTF) -> Rtf.read(file, maxChars)
            else -> throw IOException("Not a Word document")
        }
    }

    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val RTF = "{\\rtf".toByteArray()
}

/** What a paragraph's properties say, as far as reading it goes. */
private class ParagraphLook(
    val istd: Int = 0,
    val inTable: Boolean = false,
    /** The mark that ends a table row, which holds no text. */
    val rowEnd: Boolean = false,
    val align: DocAlign? = null,
    /** The list it is an item of, 1 up, or 0 for none; and how deep. */
    val list: Int = 0,
    val level: Int = 0,
    val rtl: Boolean? = null,
)

private class CharacterLook(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val hidden: Boolean = false,
)

/** A run of the file, from [start] up to [end] - offsets in the WordDocument stream - and what it has. */
private class FcRun<T>(val start: Int, val end: Int, val value: T)

private class TextPiece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean) {
    fun fcOf(cp: Int) = fc + (cp - cpStart) * (if (compressed) 1 else 2)
}

/** One level of a list: how its numbers are written, and from what number. */
private class NumberedLevel(val format: Int, val start: Int, val template: String)

private class WordBinary(private val maxChars: Int) {

    private val windows1252: Charset = Charset.forName("windows-1252")

    fun read(file: File): DocxDocument = CompoundFile(file).use { container ->
        val word = container.stream("WordDocument") ?: throw IOException("Not a Word document")
        if (u16(word, 0) != 0xA5EC) throw IOException("Not a Word document")
        val nFib = u16(word, 2)
        val flags = u16(word, 0x0A)
        // Encrypted or scrambled with a password: nothing readable without it.
        if (flags and 0x0100 != 0) throw SecurityException("This document is protected with a password")
        if (nFib < 0x00C1) return@use readWord95(word, flags)

        val table = container.stream(if (flags and 0x0200 != 0) "1Table" else "0Table")
            ?: throw IOException("Damaged Word document")
        // The FIB's parts follow one another, each saying its own length.
        var at = 0x20
        at += 2 + u16(word, at) * 2
        val longs = at + 2
        at = longs + u16(word, at) * 4
        val pairs = u16(word, at)
        val fcLcb = at + 2
        fun fc(index: Int) = if (index < pairs) s32(word, fcLcb + index * 8) else 0
        fun lcb(index: Int) = if (index < pairs) s32(word, fcLcb + index * 8 + 4) else 0

        val ccpText = s32(word, longs + 3 * 4).coerceAtLeast(0)
        val pieces = readPieces(table, fc(33), lcb(33))
        val headings = readStyles(table, fc(1), lcb(1))
        val paragraphs = readRuns(word, table, fc(13), lcb(13), paragraphs = true, ::paragraphLook)
        val characters = readRuns(word, table, fc(12), lcb(12), paragraphs = false, ::characterLook)
        val lists = Lists(table, fc(73), lcb(73), fc(74), lcb(74))
        Assembly(word, pieces, ccpText, paragraphs, characters, headings, lists).build()
    }

    /** Word 6 and 95, when written in one go: 8-bit text from fcMin to fcMac, its paragraphs all plain. */
    private fun readWord95(word: ByteArray, flags: Int): DocxDocument {
        if (flags and 0x0004 != 0) throw IOException("A Word 95 document saved in parts can't be shown here")
        val from = s32(word, 0x18).coerceIn(0, word.size)
        val to = s32(word, 0x1C).coerceIn(from, word.size)
        val text = String(word, from, minOf(to - from, maxChars), windows1252)
        val blocks = text.split('\r').map { line ->
            val clean = line.filter { it == '\t' || it >= ' ' }
            DocParagraph(if (clean.isEmpty()) emptyList() else listOf(DocSpan(clean)))
        }
        return DocxDocument(blocks, truncated = to - from > maxChars)
    }

    /** The piece table, from the CLX in the table stream. */
    private fun readPieces(table: ByteArray, fcClx: Int, lcbClx: Int): List<TextPiece> {
        var at = fcClx
        val end = (fcClx + lcbClx).coerceAtMost(table.size)
        while (at in 0 until end) {
            when (table[at].toInt()) {
                // Properties for pieces, in older files: read past.
                1 -> at += 3 + u16(table, at + 1)
                2 -> {
                    val size = s32(table, at + 1)
                    val count = (size - 4) / 12
                    val cps = at + 5
                    if (count <= 0 || cps + size > table.size) throw IOException("Damaged Word document")
                    val pcds = cps + (count + 1) * 4
                    return (0 until count).map { i ->
                        val raw = s32(table, pcds + i * 8 + 2)
                        val compressed = raw and 0x40000000 != 0
                        val fc = raw and 0x3FFFFFFF
                        TextPiece(s32(table, cps + i * 4), s32(table, cps + (i + 1) * 4), if (compressed) fc / 2 else fc, compressed)
                    }
                }
                else -> break
            }
        }
        throw IOException("Damaged Word document")
    }

    /**
     * Which styles are headings: by their built-in number, 1 to 9 for
     * Heading 1 to 9, 62 for Title and 74 for Subtitle - followed through
     * "based on", so a house style built on Heading 1 is still one.
     */
    private fun readStyles(table: ByteArray, fc: Int, lcb: Int): Map<Int, DocStyle> {
        if (lcb <= 4 || fc < 0 || fc + lcb > table.size) return emptyMap()
        val count = u16(table, fc + 2)
        val builtIn = IntArray(count) { -1 }
        val basedOn = IntArray(count) { NO_STYLE }
        var at = fc + 2 + u16(table, fc)
        for (istd in 0 until count) {
            if (at + 2 > fc + lcb) break
            val size = u16(table, at)
            if (size >= 4) {
                builtIn[istd] = u16(table, at + 2) and 0x0FFF
                basedOn[istd] = u16(table, at + 4) shr 4
            }
            at += 2 + size
        }
        val styles = HashMap<Int, DocStyle>()
        for (istd in 0 until count) {
            var current = istd
            var hops = 0
            while (current in 0 until count && hops++ < 8) {
                val style = when (builtIn[current]) {
                    1 -> DocStyle.HEADING1
                    2 -> DocStyle.HEADING2
                    3 -> DocStyle.HEADING3
                    in 4..9 -> DocStyle.HEADING4
                    62 -> DocStyle.TITLE
                    74 -> DocStyle.SUBTITLE
                    else -> null
                }
                if (style != null) {
                    styles[istd] = style
                    break
                }
                current = basedOn[current]
            }
        }
        return styles
    }

    /**
     * Paragraph or character properties, from the pages ("FKPs") the
     * PlcBte at [fc] in the table stream points to, as runs of file offsets.
     */
    private fun <T> readRuns(
        word: ByteArray,
        table: ByteArray,
        fc: Int,
        lcb: Int,
        paragraphs: Boolean,
        parse: (ByteArray, Int, Int) -> T,
    ): List<FcRun<T>> {
        if (lcb < 12 || fc < 0 || fc + lcb > table.size) return emptyList()
        val count = (lcb - 4) / 8
        val runs = ArrayList<FcRun<T>>()
        for (i in 0 until count) {
            val page = (s32(table, fc + (count + 1) * 4 + i * 4) and 0x3FFFFF) * PAGE
            if (page < 0 || page + PAGE > word.size) continue
            val crun = word[page + PAGE - 1].toInt() and 0xFF
            for (j in 0 until crun) {
                val start = s32(word, page + j * 4)
                val end = s32(word, page + (j + 1) * 4)
                // Paragraph pages give 13 bytes a run, character pages one.
                val offset = if (paragraphs) {
                    (word[page + (crun + 1) * 4 + j * 13].toInt() and 0xFF) * 2
                } else {
                    (word[page + (crun + 1) * 4 + j].toInt() and 0xFF) * 2
                }
                if (offset == 0) continue
                runs += FcRun(start, end, parse(word, page + offset, page + PAGE - 1))
            }
        }
        return runs.sortedBy { it.start }
    }

    private fun paragraphLook(word: ByteArray, at: Int, limit: Int): ParagraphLook {
        var size = word[at].toInt() and 0xFF
        val start: Int
        if (size != 0) {
            size = size * 2 - 1
            start = at + 1
        } else {
            size = (word[at + 1].toInt() and 0xFF) * 2
            start = at + 2
        }
        val end = minOf(start + size, limit)
        val istd = u16(word, start)
        var inTable = false
        var rowEnd = false
        var physical: DocAlign? = null
        var logical: DocAlign? = null
        var list = 0
        var level = 0
        var rtl: Boolean? = null
        sprms(word, start + 2, end) { sprm, operand ->
            when (sprm) {
                0x2416 -> inTable = word[operand].toInt() != 0
                0x2417 -> rowEnd = word[operand].toInt() != 0
                0x6649 -> if (s32(word, operand) > 0) inTable = true
                // Left and right as on the page, and - newer - as the text reads.
                0x2403 -> physical = when (word[operand].toInt()) {
                    1 -> DocAlign.CENTER
                    2 -> DocAlign.RIGHT
                    3, 4 -> DocAlign.JUSTIFY
                    else -> DocAlign.LEFT
                }
                0x2461 -> logical = when (word[operand].toInt()) {
                    1 -> DocAlign.CENTER
                    2 -> DocAlign.END
                    3, 4 -> DocAlign.JUSTIFY
                    else -> DocAlign.START
                }
                0x460B -> list = u16(word, operand)
                0x260A -> level = (word[operand].toInt() and 0xFF).coerceIn(0, 8)
                0x2441 -> rtl = word[operand].toInt() != 0
            }
        }
        return ParagraphLook(istd, inTable, rowEnd, logical ?: physical, list, level, rtl)
    }

    private fun characterLook(word: ByteArray, at: Int, limit: Int): CharacterLook {
        val size = word[at].toInt() and 0xFF
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var hidden = false
        // On, off, or the style's - which for body text is off - or its opposite.
        fun toggle(operand: Int) = when (word[operand].toInt() and 0xFF) {
            1, 0x81 -> true
            else -> false
        }
        sprms(word, at + 1, minOf(at + 1 + size, limit)) { sprm, operand ->
            when (sprm) {
                0x0835 -> bold = toggle(operand)
                0x0836 -> italic = toggle(operand)
                0x0837, 0x2A53 -> strike = toggle(operand)
                0x2A3E -> underline = word[operand].toInt() != 0
                0x083C -> hidden = toggle(operand)
            }
        }
        return CharacterLook(bold, italic, underline, strike, hidden)
    }

    private companion object {
        const val PAGE = 512
        const val NO_STYLE = 0x0FFF

        /**
         * Each property change ("sprm") in [from] until [to], with where its
         * operand starts. How long an operand is, is in the sprm's top bits,
         * but for a few with lengths of their own.
         */
        inline fun sprms(bytes: ByteArray, from: Int, to: Int, each: (sprm: Int, operand: Int) -> Unit) {
            var at = from
            while (at + 2 <= to) {
                val sprm = u16(bytes, at)
                val operand = at + 2
                val size = when ((sprm shr 13) and 7) {
                    0, 1 -> 1
                    2, 4, 5 -> 2
                    3 -> 4
                    7 -> 3
                    else -> when (sprm) {
                        // Table rows: a 2-byte length, one more than what follows it.
                        0xD608 -> u16(bytes, operand) + 1
                        0xC615 -> {
                            val length = bytes.getOrElse(operand) { 0 }.toInt() and 0xFF
                            if (length != 255) {
                                length + 1
                            } else {
                                var q = operand + 1
                                q += 1 + (bytes.getOrElse(q) { 0 }.toInt() and 0xFF) * 4
                                q += 1 + (bytes.getOrElse(q) { 0 }.toInt() and 0xFF) * 3
                                q - operand
                            }
                        }
                        else -> (bytes.getOrElse(operand) { 0 }.toInt() and 0xFF) + 1
                    }
                }
                if (operand + size > to || operand >= bytes.size) return
                each(sprm, operand)
                at = operand + size
            }
        }
    }

    /**
     * The document's lists: for each list a paragraph names, how each level
     * is numbered - 1., a), i. - or bulleted, counted as the paragraphs come.
     */
    private inner class Lists(table: ByteArray, fcLst: Int, lcbLst: Int, fcLfo: Int, lcbLfo: Int) {
        private val byId = HashMap<Int, List<NumberedLevel>>()
        private val listIds = ArrayList<Int>()
        private val counts = HashMap<Int, IntArray>()

        init {
            runCatching {
                if (lcbLst > 2 && fcLst >= 0 && fcLst + lcbLst <= table.size) {
                    val count = u16(table, fcLst)
                    var at = fcLst + 2
                    val lists = (0 until count).map { i ->
                        val lstf = fcLst + 2 + i * 28
                        // One level for a simple list, nine otherwise.
                        Pair(s32(table, lstf), if (table[lstf + 26].toInt() and 0x01 != 0) 1 else 9)
                    }
                    at += count * 28
                    for ((id, levels) in lists) {
                        val parsed = ArrayList<NumberedLevel>()
                        repeat(levels) {
                            val start = s32(table, at)
                            val format = table[at + 4].toInt() and 0xFF
                            val chpx = table[at + 24].toInt() and 0xFF
                            val papx = table[at + 25].toInt() and 0xFF
                            at += 28 + papx + chpx
                            val chars = u16(table, at)
                            val template = String(table, at + 2, (chars * 2).coerceAtMost(table.size - at - 2), Charsets.UTF_16LE)
                            at += 2 + chars * 2
                            parsed += NumberedLevel(format, start, template)
                        }
                        byId[id] = parsed
                    }
                }
                if (lcbLfo > 4 && fcLfo >= 0 && fcLfo + lcbLfo <= table.size) {
                    val count = s32(table, fcLfo)
                    for (i in 0 until count) listIds += s32(table, fcLfo + 4 + i * 16)
                }
            }
        }

        /** The marker for the next item of list [list] (1 up) at [level]. */
        fun next(list: Int, level: Int): String {
            val id = listIds.getOrNull(list - 1)
            val levels = id?.let { byId[it] } ?: return BULLETS[level % BULLETS.size]
            val at = level.coerceIn(0, levels.lastIndex)
            val count = counts.getOrPut(list) { IntArray(9) }
            count[at]++
            for (deeper in at + 1 until 9) count[deeper] = 0
            val definition = levels[at]
            if (definition.format == BULLET) return BULLETS[at % BULLETS.size]
            if (definition.format == NONE) return ""
            // The template's characters 0 to 8 stand for the numbers of those levels.
            return buildString {
                for (char in definition.template) {
                    if (char.code <= 8) {
                        val outer = levels.getOrNull(char.code)
                        val n = (outer?.start ?: 1) + maxOf(count[char.code], 1) - 1
                        append(formatNumber(n, numberFormat(outer?.format ?: 0)))
                    } else {
                        append(char)
                    }
                }
            }.ifEmpty { BULLETS[at % BULLETS.size] }
        }

        private fun numberFormat(nfc: Int) = when (nfc) {
            1 -> "upperRoman"
            2 -> "lowerRoman"
            3 -> "upperLetter"
            4 -> "lowerLetter"
            22 -> "decimalZero"
            else -> "decimal"
        }
    }

    /** The text put together paragraph by paragraph, into blocks. */
    private inner class Assembly(
        private val word: ByteArray,
        private val pieces: List<TextPiece>,
        private val ccpText: Int,
        private val paragraphs: List<FcRun<ParagraphLook>>,
        private val characters: List<FcRun<CharacterLook>>,
        private val headings: Map<Int, DocStyle>,
        private val lists: Lists,
    ) {
        private val out = DocBuilder()
        private var written = 0
        private var truncated = false

        fun build(): DocxDocument {
            // Fields: their code is left out, their result kept. Each open
            // field is true until its separator.
            val fields = ArrayList<Boolean>()
            var characterRun: FcRun<CharacterLook>? = null
            for (piece in pieces) {
                val from = piece.cpStart.coerceAtLeast(0)
                val to = minOf(piece.cpEnd, ccpText)
                if (from >= to) continue
                val chars = decode(piece, from, to)
                for ((i, char) in chars.withIndex()) {
                    if (written >= maxChars) {
                        truncated = true
                        break
                    }
                    val fc = piece.fcOf(from + i)
                    when (char.code) {
                        0x13 -> { fields += true; continue }
                        0x14 -> { if (fields.isNotEmpty()) fields[fields.lastIndex] = false; continue }
                        0x15 -> { if (fields.isNotEmpty()) fields.removeAt(fields.lastIndex); continue }
                    }
                    if (fields.any { it }) continue
                    if (characterRun == null || fc < characterRun.start || fc >= characterRun.end) {
                        characterRun = find(characters, fc)
                    }
                    val look = characterRun?.value ?: PLAIN
                    written++
                    when (char.code) {
                        0x0D, 0x07, 0x0C, 0x0E -> endParagraph(fc, char.code)
                        0x1F -> Unit
                        // Pictures, drawings, note marks and the like: not text.
                        in 0x00..0x1F -> when (char.code) {
                            0x09 -> out.append('\t', look.bold, look.italic, look.underline, look.strike)
                            0x0B -> out.append('\n', look.bold, look.italic, look.underline, look.strike)
                            0x1E -> out.append('-', look.bold, look.italic, look.underline, look.strike)
                        }
                        else -> if (!look.hidden) out.append(char, look.bold, look.italic, look.underline, look.strike)
                    }
                }
                if (truncated) break
            }
            return out.build(truncated)
        }

        /** The piece's characters from [from] up to [to], as far as the stream has them. */
        private fun decode(piece: TextPiece, from: Int, to: Int): String {
            val start = piece.fcOf(from)
            if (start < 0 || start >= word.size) return ""
            return if (piece.compressed) {
                String(word, start, minOf(to - from, word.size - start), windows1252)
            } else {
                String(word, start, minOf((to - from) * 2, (word.size - start) and 1.inv()), Charsets.UTF_16LE)
            }
        }

        private fun endParagraph(fc: Int, mark: Int) {
            val props = find(paragraphs, fc)?.value
            val inTable = props?.inTable ?: (mark == 0x07)
            // The mark at the end of a table row holds no text of its own.
            if (inTable && props?.rowEnd == true) {
                out.endRow()
                return
            }
            out.paragraph(
                style = props?.let { headings[it.istd] } ?: DocStyle.BODY,
                align = props?.align ?: DocAlign.START,
                level = if (props != null && props.list > 0) props.level else 0,
                rtl = props?.rtl,
                inTable = inTable,
                endsCell = mark == 0x07,
                marker = { props?.takeIf { it.list > 0 }?.let { lists.next(it.list, it.level) } },
            )
        }

        private fun <T> find(runs: List<FcRun<T>>, fc: Int): FcRun<T>? {
            var low = 0
            var high = runs.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val run = runs[middle]
                when {
                    fc < run.start -> high = middle - 1
                    fc >= run.end -> low = middle + 1
                    else -> return run
                }
            }
            return null
        }
    }
}

/**
 * Paragraphs, with their spans, lists and tables, put together into the
 * blocks the Word viewer shows - for the readers that find them one
 * character and one paragraph mark at a time: .doc and RTF.
 */
internal class DocBuilder {
    private val blocks = ArrayList<DocBlock>()
    private val rows = ArrayList<List<DocCell>>()
    private var row = ArrayList<DocCell>()
    private var cell = ArrayList<DocBlock>()
    private val spans = ArrayList<DocSpan>()
    private val text = StringBuilder()
    private var format = DocSpan("")

    /** [char], in the formatting given; a change of formatting starts a new span. */
    fun append(char: Char, bold: Boolean = false, italic: Boolean = false, underline: Boolean = false, strike: Boolean = false) {
        if (format.bold != bold || format.italic != italic || format.underline != underline || format.strike != strike) {
            flushSpan()
            format = DocSpan("", bold, italic, underline, strike)
        }
        text.append(char)
    }

    /**
     * The paragraph so far, ended: into the body, or into the table cell it
     * is in, which it ends when [endsCell]. [marker] is asked only of a
     * paragraph with text in it, so an empty one doesn't count in its list.
     */
    fun paragraph(
        style: DocStyle,
        align: DocAlign,
        level: Int,
        rtl: Boolean?,
        inTable: Boolean,
        endsCell: Boolean,
        marker: () -> String?,
    ) {
        flushSpan()
        val paragraph = DocParagraph(
            spans = spans.toList(),
            style = style,
            align = align,
            marker = if (spans.any { it.text.isNotBlank() }) marker() else null,
            level = level,
            rtl = rtl,
        )
        spans.clear()
        if (!inTable) {
            endTable()
            blocks += paragraph
            return
        }
        cell += paragraph
        if (endsCell) {
            row += DocCell(cell.toList(), span = 1)
            cell = ArrayList()
        }
    }

    fun hasText() = text.isNotEmpty() || spans.isNotEmpty()

    fun endRow() {
        if (cell.isNotEmpty()) {
            row += DocCell(cell.toList(), span = 1)
            cell = ArrayList()
        }
        if (row.isNotEmpty()) rows += row.toList()
        row = ArrayList()
    }

    fun build(truncated: Boolean): DocxDocument {
        flushSpan()
        if (spans.isNotEmpty()) paragraph(DocStyle.BODY, DocAlign.START, 0, null, inTable = false, endsCell = false) { null }
        endTable()
        return DocxDocument(blocks.toList(), truncated)
    }

    private fun flushSpan() {
        if (text.isEmpty()) return
        val span = format.copy(text = text.toString())
        val last = spans.lastOrNull()
        if (last != null && last.copy(text = "") == format) {
            spans[spans.lastIndex] = last.copy(text = last.text + span.text)
        } else {
            spans += span
        }
        text.setLength(0)
    }

    private fun endTable() {
        endRow()
        if (rows.isEmpty()) return
        val width = rows.maxOf { it.size }
        blocks += DocTable(List(width) { 1f }, rows.toList())
        rows.clear()
    }
}

private val PLAIN = CharacterLook()

private val BULLETS = listOf("•", "◦", "▪")

/** A list level's numbers: nfc 23 is a bullet, 255 nothing at all. */
private const val BULLET = 23
private const val NONE = 255
