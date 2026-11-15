package com.filemanager.app.data.viewer

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/*
 * Reading a PowerPoint 97-2003 presentation - .ppt, and .pps and .pot, which
 * are the same format - for the viewer, as Pptx reads the newer one: each
 * slide's text in reading order, with its levels, bullets and bold, italic
 * and underline, and the speaker's notes. Pictures, charts and the slide's
 * design are left to PowerPoint. [MS-PPT] is the format.
 *
 * It is a compound file (see CompoundFile) whose "PowerPoint Document"
 * stream is a run of records, each an 8-byte header - a version and an
 * instance, a type, a length - and that many bytes, which are more records
 * when the version is 15. The slides are not found by reading the stream
 * from the top: every time the file was saved, what changed was added at
 * its end, with a table of where each object now is, and the "Current User"
 * stream says where the last of those tables is.
 *
 * A slide's text is in the shapes of its drawing, which is Office's Escher
 * format. A placeholder's - a title, a body - is often not in the shape but
 * in the slide list, which the shape points to by number.
 *
 * Nothing in the file is trusted: every record is held inside the one it
 * is in, every walk is counted, and what can't be understood is left out
 * rather than guessed at.
 */
internal object PowerPointBinary {

    private const val DOCUMENT_STREAM = "PowerPoint Document"
    private const val CURRENT_USER_STREAM = "Current User"
    private const val PICTURES_STREAM = "Pictures"

    /** What a picture's name begins with, followed by where in the "Pictures" stream it is. */
    const val PICTURE_PREFIX = "ppt-picture:"

    /**
     * The picture called [entry] in [file], as bytes the platform can decode:
     * a JPEG or PNG as it is, a DIB made a .bmp. Null for anything else - the
     * metafiles clip art is kept as - and for what is not a picture.
     */
    fun picture(file: File, entry: String): ByteArray? {
        if (!entry.startsWith(PICTURE_PREFIX)) return null
        val offset = entry.removePrefix(PICTURE_PREFIX).toLongOrNull() ?: return null
        return CompoundFile(file).use { container ->
            val size = container.size(PICTURES_STREAM) ?: return@use null
            if (offset < 0 || offset + 8 > size) return@use null
            val head = container.read(PICTURES_STREAM, offset, 8) ?: return@use null
            val length = if (head.size == 8) s32(head, 4) else -1
            if (length < 0 || length > MAX_PICTURE || offset + 8 + length > size) return@use null
            val body = container.read(PICTURES_STREAM, offset + 8, length) ?: return@use null
            blipImage(u16(head, 2), u16(head, 0) ushr 4, body)
        }
    }

    /**
     * The image in a blip - a picture's record in the "Pictures" stream: one
     * or two ids of its own, a tag, and then the image, which is only a
     * JPEG or a PNG as it is, or a DIB without the header of a .bmp.
     */
    fun blipImage(type: Int, instance: Int, body: ByteArray): ByteArray? {
        // A second id when the instance is odd.
        val start = if (instance and 1 == 1) 33 else 17
        if (body.size <= start) return null
        return when (type) {
            0xF01D, 0xF02A -> body.copyOfRange(start, body.size).takeIf { it.size > 4 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
            0xF01E -> body.copyOfRange(start, body.size).takeIf { it.size > 8 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte() }
            0xF01F -> bitmapOf(body.copyOfRange(start, body.size))
            else -> null
        }
    }

    /** A DIB - the header and the pixels - as a .bmp: with the file header in front, saying where the pixels begin. */
    private fun bitmapOf(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val headerSize = s32(dib, 0)
        if (headerSize < 12 || headerSize > dib.size) return null
        val bits = u16(dib, 14)
        val compression = s32(dib, 16)
        var colours = s32(dib, 32)
        if (colours <= 0 && bits <= 8) colours = 1 shl bits
        colours = colours.coerceIn(0, 256)
        val masks = if (compression == 3 && headerSize == 40) 12 else 0
        val pixels = 14 + headerSize + colours * 4 + masks
        if (pixels > 14 + dib.size) return null
        val file = ByteArray(14 + dib.size)
        file[0] = 'B'.code.toByte()
        file[1] = 'M'.code.toByte()
        for (i in 0 until 4) {
            file[2 + i] = (file.size ushr (8 * i)).toByte()
            file[10 + i] = (pixels ushr (8 * i)).toByte()
        }
        dib.copyInto(file, 14)
        return file
    }

    /** The presentation's stream is held whole to be read: not one of more than this. */
    private const val MAX_STREAM = 64L * 1024 * 1024

    fun read(file: File, maxChars: Int): PptxDeck = CompoundFile(file).use { container ->
        val size = container.size(DOCUMENT_STREAM) ?: throw IOException("Not a PowerPoint presentation")
        if (size > MAX_STREAM) throw TooLarge()
        val document = container.stream(DOCUMENT_STREAM) ?: throw IOException("Not a PowerPoint presentation")
        read(document, container.stream(CURRENT_USER_STREAM), maxChars)
    }

    /** The slides of a "PowerPoint Document" stream, and its "Current User" stream if it has one. */
    fun read(document: ByteArray, currentUser: ByteArray?, maxChars: Int): PptxDeck =
        PptReader(document, currentUser, maxChars).read()
}

// Record types - PowerPoint's.
private const val DOCUMENT = 0x03E8
private const val SLIDE = 0x03EE
private const val SLIDE_ATOM = 0x03EF
private const val NOTES = 0x03F0
private const val ENVIRONMENT = 0x03F2
private const val SLIDE_PERSIST = 0x03F3
private const val MAIN_MASTER = 0x03F8
private const val SLIDE_SHOW_INFO = 0x03F9
private const val PP_DRAWING_GROUP = 0x040B
private const val PP_DRAWING = 0x040C
private const val FONT_COLLECTION = 0x07D5
private const val OE_PLACEHOLDER = 0x0BC3
private const val OUTLINE_REF = 0x0F9E
private const val TEXT_HEADER = 0x0F9F
private const val TEXT_CHARS = 0x0FA0
private const val STYLE_TEXT_PROP = 0x0FA1
private const val TX_MASTER_STYLE = 0x0FA3
private const val TEXT_BYTES = 0x0FA8
private const val FONT_ENTITY = 0x0FB7
private const val SLIDE_LIST = 0x0FF0
private const val USER_EDIT = 0x0FF5
private const val CURRENT_USER = 0x0FF6
private const val PERSIST_DIRECTORY = 0x1772

// And Office's Escher drawing's.
private const val DG_CONTAINER = 0xF002
private const val SPGR_CONTAINER = 0xF003
private const val SP_CONTAINER = 0xF004
private const val DGG_CONTAINER = 0xF000
private const val BSTORE_CONTAINER = 0xF001
private const val BSE = 0xF007
private const val SP_ATOM = 0xF00A
private const val OPT = 0xF00B
private const val CLIENT_TEXTBOX = 0xF00D
private const val CHILD_ANCHOR = 0xF00F
private const val CLIENT_ANCHOR = 0xF010

/** The shape type of a plain rectangle: what the boxes of a table's cells are. */
private const val SHAPE_RECTANGLE = 1

/** Two edges this near are one line: a unit is 1/576 of an inch, and a saved edge is rounded. */
private const val EDGE_TOLERANCE = 2
private const val CLIENT_DATA = 0xF011

/** The property of a shape that says which picture it shows, by number, from 1. */
private const val PIB = 0x0104

private const val MAX_BLIPS = 100_000
private const val MAX_PICTURE = 32 * 1024 * 1024

/** An EMU - the unit a picture is sized in - to a unit of the 576 a page inch is in. */
private const val EMU_PER_UNIT = 914_400.0 / 576

/** In a "Current User" stream, what says the presentation is password protected. */
private val ENCRYPTED_TOKEN = 0xF3D1C4DF.toInt()

/** Text types, as a TextHeaderAtom says them. */
private const val TEXT_TITLE = 0
private const val TEXT_BODY = 1
private const val TEXT_NOTES = 2
private const val TEXT_OTHER = 4
private const val TEXT_CENTER_BODY = 5
private const val TEXT_CENTER_TITLE = 6
private const val TEXT_HALF_BODY = 7
private const val TEXT_QUARTER_BODY = 8

/** Which placeholders hold what, as an OEPlaceholderAtom numbers them: the master's and the page's own. */
private val TITLE_PLACEHOLDERS = setOf(1, 3, 13, 15, 17)
private const val CENTERED_TITLE_PLACEHOLDER = 15
private const val SUBTITLE_PLACEHOLDER = 16

/**
 * What every page repeats and has no text of its own to read: a master's
 * notes image and notes, and the date, number, footer and header - which a
 * page uses the master's numbers for - and a page of notes's image of its slide.
 */
private val FURNITURE_PLACEHOLDERS = setOf(5, 6, 7, 8, 9, 10, 11)

/**
 * The records that mark where in a text a field stands - the page number,
 * the date, a header or a footer - which the text holds as one character:
 * what the field comes to is for PowerPoint to say.
 */
private val FIELD_MARKS = setOf(0x0FD8, 0x0FF7, 0x0FF8, 0x0FF9, 0x0FFA)

private const val MAX_DEPTH = 40

/** Records walked, over the whole file: a hundred slides of five thousand shapes are a few million. */
private const val MAX_VISITS = 20_000_000
private const val MAX_EDITS = 1_000

/** Objects the file says where they are: a presentation of two thousand slides has some ten thousand. */
private const val MAX_PERSIST = 200_000
private const val MAX_FONTS = 4_096
private const val MAX_FIELDS = 10_000

/** Runs of text the slide lists hold, in all: what is said once in a list is said once however many slides say they are there. */
private const val MAX_LISTED = 200_000

/** Runs of formatting in one text: one a paragraph, and no text has a hundred thousand of those. */
private const val MAX_RUNS = 100_000

/** Where the bytes ran out: thrown inside a parse, and caught at its edge. */
private object PptCut : RuntimeException("Ran out of bytes") {
    override fun fillInStackTrace(): Throwable = this
}

/** [bytes] from [at] to [end], read as numbers; [PptCut] if there are not enough of them. */
private class PptCursor(private val bytes: ByteArray, var at: Int, private val end: Int) {
    fun has(count: Int) = count >= 0 && at.toLong() + count <= end

    fun read16(): Int {
        if (!has(2)) throw PptCut
        return u16(bytes, at).also { at += 2 }
    }

    fun read32(): Int {
        if (!has(4)) throw PptCut
        return s32(bytes, at).also { at += 4 }
    }

    fun skip(count: Int) {
        if (!has(count)) throw PptCut
        at += count
    }
}

/** A paragraph's properties as a text or a master gives them; each is null where it says nothing. */
private class PptParagraphProps {
    var level = 0
    var bullet: Boolean? = null
    var bulletChar: Int? = null
    var bulletFont: Int? = null
    var align: Int? = null
    var rightToLeft: Boolean? = null
}

/** What is told of the characters of a run. */
private class PptCharacterProps(val bold: Boolean, val italic: Boolean, val underline: Boolean)

/** Properties for [count] characters, from where the one before ended. */
private class StyleRun<T>(val end: Long, val props: T)

private class PptStyle(val paragraphs: List<StyleRun<PptParagraphProps>>, val characters: List<StyleRun<PptCharacterProps>>)

/** One run of text, with its type and the formatting that goes with it. */
private class PptText(val type: Int) {
    var text = ""
    var style: ByteArray? = null

    /** Where in [text] the fields stand; null for a text with none. */
    var fields: HashSet<Int>? = null
        private set

    fun addField(position: Int) {
        val set = fields ?: HashSet<Int>().also { fields = it }
        if (set.size < MAX_FIELDS) set += position
    }
}

/** What a shape says of its text: it is there, or it is in the slide list, at this number. */
private sealed interface PptPiece {
    class Own(val run: PptText) : PptPiece
    class Listed(val index: Int) : PptPiece
}

/** A picture a shape shows: where in the "Pictures" stream it is, and the size it is drawn, in EMUs. */
private class PptPicture(val offset: Long, val widthEmu: Long, val heightEmu: Long)

/**
 * A table, rebuilt from the boxes of its cells: the widths of its columns, in whatever units
 * the boxes are, and each row's cells, which [PptCell] says - a cell with no shape is an empty place.
 */
private class PptTable(val columns: List<Float>, val rows: List<List<PptCell>>)

private class PptCell(val shape: PptShape?, val span: Int)

/**
 * A shape, with its box - left, top, right, bottom - if it has one. A table is a shape too, in the place of
 * the group of shapes it was made of.
 */
private class PptShape(
    val placeholder: Int,
    val pieces: List<PptPiece>,
    val picture: PptPicture?,
    val type: Int = 0,
    val box: IntArray? = null,
    val table: PptTable? = null,
)

/** One slide - or master, or page of notes - in a slide list: where it is kept, what it is called, and the text the list holds for it. */
private class PptEntry(val persistId: Int, val id: Long) {
    val texts = ArrayList<PptText>()
}

/** What a page shows, in order: a run of text, and whose it is, or a picture. */
private sealed interface PptContent {
    class Text(val run: PptText, val placeholder: Int) : PptContent
    class Picture(val picture: PptPicture) : PptContent

    /** A table: each cell's text, and how many columns it is across. */
    class Table(val columns: List<Float>, val rows: List<List<Pair<List<Text>, Int>>>) : PptContent
}

/**
 * The bytes of one "PowerPoint Document" stream, read for the text of its
 * slides. [user] is the "Current User" stream, which says where to start.
 */
private class PptReader(private val d: ByteArray, private val user: ByteArray?, private val maxChars: Int) {

    private class Rec(val type: Int, val version: Int, val instance: Int, val at: Int, val length: Int) {
        val data: Int get() = at + 8
        val end: Int get() = at + 8 + length
    }

    private val persist = HashMap<Int, Int>()
    private var documentId = 0
    private val fonts = ArrayList<String>()

    /** Where each picture is in the "Pictures" stream, by its number less one; -1 for one that isn't there. */
    private val blips = ArrayList<Long>()
    private val masterStyles = HashMap<Long, Map<Int, List<PptParagraphProps>>>()
    private var visits = 0
    private var listed = 0
    private var pieces = 0
    private var used = 0L
    private var spent = false
    private var cut = false

    // ---- Records ------------------------------------------------------------

    /** The record at [at], if it and all it holds lie within [limit]. */
    private fun rec(at: Int, limit: Int = d.size): Rec? {
        if (at < 0 || at > limit - 8) return null
        val head = u16(d, at)
        val length = s32(d, at + 4)
        if (length < 0 || at.toLong() + 8 + length > limit) return null
        return Rec(u16(d, at + 2), head and 0xF, head ushr 4, at, length)
    }

    private fun tick() {
        if (++visits > MAX_VISITS) throw TooLarge()
        // The screen that asked for this has gone: nobody is waiting for it.
        if (visits and 0xFFF == 0 && Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    private inline fun forEachChild(parent: Rec, action: (Rec) -> Unit) {
        var at = parent.data
        while (at + 8 <= parent.end) {
            val child = rec(at, parent.end) ?: return
            tick()
            action(child)
            at = child.end
        }
    }

    private fun u32(at: Int): Long = s32(d, at).toLong() and 0xFFFFFFFFL

    // ---- Where everything is ------------------------------------------------

    /**
     * The table of where each object is: the last one the file has, and what
     * the saves before it kept that the last did not change. A presentation
     * saved forty times has forty, and the slides are in whichever of them
     * was the last to say where they are.
     */
    private fun findObjects() {
        var edit = -1
        if (user != null && user.size >= 20 && u16(user, 2) == CURRENT_USER) {
            if (s32(user, 12) == ENCRYPTED_TOKEN) throw SecurityException("This presentation is protected with a password")
            edit = s32(user, 16)
        }
        // Without a usable "Current User", or one that points nowhere: the
        // last edit at the top level of the stream.
        if (edit <= 0 || rec(edit)?.type != USER_EDIT) edit = lastEditAtTopLevel()

        var first = true
        val seen = HashSet<Int>()
        while (edit > 0 && seen.add(edit) && seen.size <= MAX_EDITS) {
            val atom = rec(edit) ?: break
            if (atom.type != USER_EDIT || atom.length < 28) break
            // A session for the encryption's keys, where there is one.
            if (atom.length >= 32 && s32(d, atom.data + 28) != 0) throw SecurityException("This presentation is protected with a password")
            if (first) {
                documentId = s32(d, atom.data + 16)
                first = false
            }
            readPersistDirectory(s32(d, atom.data + 12))
            edit = s32(d, atom.data + 8)
        }
    }

    private fun lastEditAtTopLevel(): Int {
        var last = -1
        var at = 0
        var count = 0
        while (count++ < MAX_VISITS) {
            val record = rec(at) ?: break
            if (record.type == USER_EDIT) last = at
            at = record.end
        }
        return last
    }

    private fun readPersistDirectory(at: Int) {
        val atom = rec(at) ?: return
        if (atom.type != PERSIST_DIRECTORY) return
        var p = atom.data
        while (p + 4 <= atom.end && persist.size < MAX_PERSIST) {
            val head = s32(d, p)
            p += 4
            val first = head and 0xFFFFF
            val count = head ushr 20
            var i = 0
            while (i < count && p + 4 <= atom.end) {
                // The later save wins: the earlier ones are read after it.
                persist.putIfAbsent(first + i, s32(d, p))
                p += 4
                i++
            }
        }
    }

    private fun persisted(id: Int, type: Int): Rec? =
        persist[id]?.let { rec(it) }?.takeIf { it.type == type && it.version == 0xF }

    // ---- The slides ---------------------------------------------------------

    fun read(): PptxDeck {
        findObjects()
        val document = persisted(documentId, DOCUMENT) ?: topLevel(DOCUMENT)
            ?: throw IOException("Not a PowerPoint presentation")

        var slides: List<PptEntry>? = null
        var masters: List<PptEntry>? = null
        var notes: List<PptEntry>? = null
        forEachChild(document) { child ->
            when {
                child.type == SLIDE_LIST && child.instance == 0 && slides == null -> slides = readList(child)
                child.type == SLIDE_LIST && child.instance == 1 && masters == null -> masters = readList(child)
                child.type == SLIDE_LIST && child.instance == 2 && notes == null -> notes = readList(child)
                child.type == ENVIRONMENT -> readFonts(child)
                child.type == PP_DRAWING_GROUP -> readBlips(child)
            }
        }

        val listed = slides.orEmpty()
        val out = ArrayList<PptxSlide>()
        for ((index, entry) in listed.withIndex()) {
            if (index >= Pptx.MAX_SLIDES || spent) break
            val slide = slide(index + 1, entry, masters.orEmpty(), notes.orEmpty())
            // A slide cut short before anything on it was read goes with the rest left out.
            if (spent && slide.blocks.isEmpty()) break
            out += slide
        }
        return PptxDeck(out, total = listed.size, truncated = spent || cut || out.size < listed.size)
    }

    private fun topLevel(type: Int): Rec? {
        var at = 0
        var found: Rec? = null
        var count = 0
        while (count++ < MAX_VISITS) {
            val record = rec(at) ?: break
            if (record.type == type && record.version == 0xF) found = record
            at = record.end
        }
        return found
    }

    /**
     * A slide list: for each slide, master or page of notes in order, where
     * it is kept and what it is called, and after that the text the list
     * holds for it.
     */
    private fun readList(list: Rec): List<PptEntry> {
        val entries = ArrayList<PptEntry>()
        var current: PptText? = null
        forEachChild(list) { child ->
            when (child.type) {
                SLIDE_PERSIST -> if (child.length >= 20 && entries.size < Pptx.MAX_SLIDES * 10) {
                    entries += PptEntry(s32(d, child.data), u32(child.data + 12))
                    current = null
                }
                TEXT_HEADER -> {
                    val entry = entries.lastOrNull()
                    if (entry != null && entry.texts.size < Docx.MAX_TOGETHER && listed < MAX_LISTED) {
                        listed++
                        val next = PptText(s32(d, child.data))
                        entry.texts += next
                        current = next
                    }
                }
                TEXT_CHARS -> current?.text = utf16(child)
                TEXT_BYTES -> current?.text = latin1(child)
                STYLE_TEXT_PROP -> current?.style = d.copyOfRange(child.data, child.end)
                in FIELD_MARKS -> if (child.length >= 4) current?.addField(s32(d, child.data))
            }
        }
        return entries
    }

    // Past what is read, text is not even decoded: a record of fifty megabytes is not fifty megabytes of text.
    private fun utf16(atom: Rec): String {
        val length = minOf(atom.length and 1.inv(), (maxChars + 1) * 2)
        if (length < atom.length and 1.inv()) cut = true
        return String(d, atom.data, length, Charsets.UTF_16LE)
    }

    private fun latin1(atom: Rec): String {
        val length = minOf(atom.length, maxChars + 1)
        if (length < atom.length) cut = true
        return String(d, atom.data, length, Charsets.ISO_8859_1)
    }

    /** Where each of the presentation's pictures is kept, in the order a shape numbers them. */
    private fun readBlips(group: Rec) {
        forEachChild(group) { dgg ->
            if (dgg.type != DGG_CONTAINER) return@forEachChild
            forEachChild(dgg) { store ->
                if (store.type != BSTORE_CONTAINER) return@forEachChild
                forEachChild(store) { entry ->
                    if (entry.type == BSE && entry.length >= 36 && blips.size < MAX_BLIPS) {
                        // An entry with no size is one that is no longer used.
                        blips += if (u32(entry.data + 20) == 0L) -1L else u32(entry.data + 28)
                    }
                }
            }
        }
    }

    /** The names of the fonts the presentation uses, in the order a bullet's font is numbered. */
    private fun readFonts(environment: Rec) {
        forEachChild(environment) { collection ->
            if (collection.type != FONT_COLLECTION) return@forEachChild
            forEachChild(collection) { font ->
                if (font.type == FONT_ENTITY && font.length >= 64 && fonts.size < MAX_FONTS) {
                    // 32 characters, ending at the first that is nothing.
                    val name = String(d, font.data, 64, Charsets.UTF_16LE)
                    fonts += name.substringBefore('\u0000')
                }
            }
        }
    }

    private fun slide(number: Int, entry: PptEntry, masters: List<PptEntry>, notePages: List<PptEntry>): PptxSlide {
        val container = persisted(entry.persistId, SLIDE) ?: return PptxSlide(number, emptyList(), emptyList(), hidden = false)
        var masterId = 0L
        var notesId = 0L
        var hidden = false
        var drawing: Rec? = null
        forEachChild(container) { child ->
            when (child.type) {
                SLIDE_ATOM -> if (child.length >= 20) {
                    masterId = u32(child.data + 12)
                    notesId = u32(child.data + 16)
                }
                // Its flags, of which the third is "hidden".
                SLIDE_SHOW_INFO -> if (child.length >= 12) hidden = u16(d, child.data + 10) and 0x4 != 0
                PP_DRAWING -> if (drawing == null) drawing = child
            }
        }

        val styles = stylesOf(masterId, masters)
        val contents = resolve(drawing, entry)
        val blocks = ArrayList<DocBlock>()
        // The title first, wherever the slide keeps it; the rest as it comes.
        for (content in contents.sortedByDescending { isTitle(it) }) {
            if (blocks.size >= Docx.MAX_TOGETHER) {
                cut = true
                break
            }
            when (content) {
                is PptContent.Text -> paragraphs(content, notes = false, styles[content.run.type]).forEach { if (room(it)) blocks += it }
                is PptContent.Picture -> if (++pieces <= Docx.MAX_PIECES) {
                    blocks += DocImage(PowerPointBinary.PICTURE_PREFIX + content.picture.offset, content.picture.widthEmu, content.picture.heightEmu)
                } else {
                    spent = true
                }
                is PptContent.Table -> {
                    val rows = content.rows.map { row ->
                        row.map { (texts, span) ->
                            val cell = ArrayList<DocBlock>()
                            for (text in texts) paragraphs(text, notes = false, styles[text.run.type]).forEach { if (room(it)) cell += it }
                            DocCell(cell, span)
                        }
                    }
                    if (++pieces <= Docx.MAX_PIECES) blocks += DocTable(content.columns, rows) else spent = true
                }
            }
            if (spent) break
        }

        val notes = ArrayList<DocParagraph>()
        // Only of a page of notes the slide has: a slide without one has none,
        // and must not be shown its own text for them.
        val page = if (notesId != 0L) notePages.firstOrNull { it.id == notesId } else null
        if (page != null && !spent) {
            var notesDrawing: Rec? = null
            persisted(page.persistId, NOTES)?.let { container ->
                forEachChild(container) { if (it.type == PP_DRAWING && notesDrawing == null) notesDrawing = it }
            }
            for (content in resolve(notesDrawing, page)) {
                // Notes are text: a picture on a page of notes is not one of the notes.
                if (content !is PptContent.Text) continue
                paragraphs(content, notes = true, null).forEach { if (notes.size < Docx.MAX_TOGETHER && room(it)) notes += it }
                if (spent) break
            }
        }
        return PptxSlide(number, blocks, notes, hidden)
    }

    private fun isTitle(content: PptContent) = content is PptContent.Text &&
        (content.run.type == TEXT_TITLE || content.run.type == TEXT_CENTER_TITLE || content.placeholder in TITLE_PLACEHOLDERS)

    /** [paragraph] counted against what is read: false, and the reading stopped, once there is more than is shown. */
    private fun room(paragraph: DocParagraph): Boolean {
        used += paragraph.text.length
        // As many paragraphs as a document is read to, whatever their length.
        if (used > maxChars || ++pieces > Docx.MAX_PIECES) {
            spent = true
            return false
        }
        return true
    }

    // ---- A slide's shapes ---------------------------------------------------

    /**
     * The text of a page, in the order of its shapes: each shape's own, or
     * what the page's entry in the slide list holds where the shape says so.
     * A page whose drawing could not be read is shown as the list has it.
     */
    private fun resolve(drawing: Rec?, entry: PptEntry): List<PptContent> {
        val out = ArrayList<PptContent>()
        val shapes = drawing?.let { shapesOf(it) }.orEmpty()
        for (shape in shapes) {
            if (shape.placeholder in FURNITURE_PLACEHOLDERS) continue
            val table = shape.table
            if (table != null) {
                if (out.size < Docx.MAX_TOGETHER) out += PptContent.Table(table.columns, table.rows.map { row -> row.map { cell -> textsOf(cell.shape, entry) to cell.span } })
                continue
            }
            // A picture, and then whatever text is set on it.
            shape.picture?.let { if (out.size < Docx.MAX_TOGETHER) out += PptContent.Picture(it) }
            for (piece in shape.pieces) {
                val run = when (piece) {
                    is PptPiece.Own -> piece.run
                    is PptPiece.Listed -> entry.texts.getOrNull(piece.index)
                }
                if (run != null && run.text.isNotEmpty() && out.size < Docx.MAX_TOGETHER) out += PptContent.Text(run, shape.placeholder)
            }
        }
        if (out.isEmpty()) {
            for (run in entry.texts) if (run.text.isNotEmpty()) out += PptContent.Text(run, 0)
        }
        return out
    }

    /** The text a shape holds, as runs: its own, or what the list holds where it says so. */
    private fun textsOf(shape: PptShape?, entry: PptEntry): List<PptContent.Text> {
        val out = ArrayList<PptContent.Text>()
        for (piece in shape?.pieces.orEmpty()) {
            val run = when (piece) {
                is PptPiece.Own -> piece.run
                is PptPiece.Listed -> entry.texts.getOrNull(piece.index)
            }
            if (run != null && run.text.isNotEmpty() && out.size < Docx.MAX_TOGETHER) out += PptContent.Text(run, 0)
        }
        return out
    }

    /**
     * The shapes of a drawing, in order, with a group that is a table as one. A group's shapes are
     * also kept when they are a plain box with nothing in it - an empty cell - which is dropped
     * unless the group turns out to be a table.
     */
    private fun shapesOf(drawing: Rec): List<PptShape> {
        val out = ArrayList<PptShape>()

        /** The shapes of [container] and of the groups in it, flattened, in order. */
        fun collect(container: Rec, depth: Int, into: MutableList<PptShape>, inGroup: Boolean) {
            if (depth > MAX_DEPTH) return
            forEachChild(container) { child ->
                when {
                    child.type == SP_CONTAINER && child.version == 0xF -> shape(child, boxes = inGroup)?.let {
                        if (into.size < Docx.MAX_TOGETHER) into += it else cut = true
                    }
                    (child.type == SPGR_CONTAINER || child.type == DG_CONTAINER) && child.version == 0xF -> collect(child, depth + 1, into, inGroup || depth >= 1)
                }
            }
        }

        fun walk(container: Rec, depth: Int) {
            if (depth > MAX_DEPTH) return
            forEachChild(container) { child ->
                when {
                    child.type == SP_CONTAINER && child.version == 0xF -> shape(child, boxes = false)?.let {
                        // Shapes with text: as many as a slide is shown at once.
                        if (out.size < Docx.MAX_TOGETHER) out += it else cut = true
                    }
                    // A group inside the slide's own: a table, or some shapes that go together.
                    child.type == SPGR_CONTAINER && child.version == 0xF && depth >= 1 -> {
                        val members = ArrayList<PptShape>()
                        collect(child, depth + 1, members, inGroup = true)
                        val table = tableOf(members)
                        if (table != null) {
                            if (out.size < Docx.MAX_TOGETHER) out += table else cut = true
                        } else {
                            for (member in members) {
                                if (member.pieces.isEmpty() && member.picture == null) continue
                                if (out.size < Docx.MAX_TOGETHER) out += member else cut = true
                            }
                        }
                    }
                    (child.type == SPGR_CONTAINER || child.type == DG_CONTAINER) && child.version == 0xF -> walk(child, depth + 1)
                }
            }
        }
        forEachChild(drawing) { if (it.type == DG_CONTAINER && it.version == 0xF) walk(it, 0) }
        return out
    }

    /**
     * [members] as a table, if their boxes are one: at least two rows and two columns, every box
     * between lines the others share, no two over each other, and most places filled. A box that
     * reaches across two columns or down two rows is a merged cell.
     */
    private fun tableOf(members: List<PptShape>): PptShape? {
        val cells = members.filter { it.box != null && (it.pieces.isNotEmpty() || it.type == SHAPE_RECTANGLE) }
        if (cells.size < 4 || cells.size > Docx.MAX_TOGETHER) return null
        val xs = edges(cells.flatMap { listOf(it.box!![0], it.box[2]) })
        val ys = edges(cells.flatMap { listOf(it.box!![1], it.box[3]) })
        val columns = xs.size - 1
        val rows = ys.size - 1
        if (columns < 2 || rows < 2 || columns > Docx.MAX_COLUMNS || rows > Docx.MAX_TOGETHER) return null

        // Who is in each place; a place with two is no table.
        val grid = arrayOfNulls<PptShape>(rows * columns)
        val first = HashMap<PptShape, Int>()
        for (cell in cells) {
            val box = cell.box!!
            val c0 = nearest(xs, box[0])
            val c1 = nearest(xs, box[2])
            val r0 = nearest(ys, box[1])
            val r1 = nearest(ys, box[3])
            if (c1 <= c0 || r1 <= r0) return null
            for (r in r0 until r1) for (c in c0 until c1) {
                if (grid[r * columns + c] != null) return null
                grid[r * columns + c] = cell
            }
            first[cell] = r0 * columns + c0
        }
        // Most places have a cell: a few boxes in a corner are not a table.
        if (grid.count { it != null } * 10 < grid.size * 7) return null

        val out = ArrayList<List<PptCell>>()
        for (r in 0 until rows) {
            val row = ArrayList<PptCell>()
            var c = 0
            while (c < columns) {
                val here = grid[r * columns + c]
                var end = c + 1
                if (here == null) {
                    // A place with nothing: as wide as the places like it beside it.
                    while (end < columns && grid[r * columns + end] == null) end++
                    row += PptCell(null, end - c)
                } else {
                    while (end < columns && grid[r * columns + end] === here) end++
                    // Begun here, or come down from the row above: that one's text is shown there.
                    row += PptCell(here.takeIf { first[here] == r * columns + c }, end - c)
                }
                c = end
            }
            out += row
        }
        val widths = List(columns) { (xs[it + 1] - xs[it]).toFloat().coerceAtLeast(1f) }
        return PptShape(placeholder = 0, pieces = emptyList(), picture = null, table = PptTable(widths, out))
    }

    /**
     * The distinct lines these edges make, ascending: edges with less than [EDGE_TOLERANCE] between one
     * and the next are one line, as the edges that mean a column's side do when each cell's is rounded.
     */
    private fun edges(values: List<Int>): IntArray {
        val sorted = values.sorted()
        val out = ArrayList<Int>()
        var previous = 0
        for ((i, value) in sorted.withIndex()) {
            if (i == 0 || value - previous > EDGE_TOLERANCE) out += value
            previous = value
        }
        return out.toIntArray()
    }

    /** Which of [lines], which are ascending, [value] is on: the nearest. */
    private fun nearest(lines: IntArray, value: Int): Int {
        var low = 0
        var high = lines.size - 1
        // The first line at or after the value, then whichever of it and the one before is nearer.
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle] < value) low = middle + 1 else high = middle
        }
        return if (low > 0 && value.toLong() - lines[low - 1] <= lines[low].toLong() - value) low - 1 else low
    }

    private fun shape(container: Rec, boxes: Boolean): PptShape? {
        var placeholder = 0
        var deleted = false
        var number = 0
        var width = 0L
        var height = 0L
        var type = 0
        var box: IntArray? = null
        val pieces = ArrayList<PptPiece>()
        forEachChild(container) { child ->
            when (child.type) {
                SP_ATOM -> if (child.length >= 8) {
                    deleted = s32(d, child.data + 4) and 0x8 != 0
                    type = child.instance
                }
                // Left, top, right and bottom: four numbers of four bytes.
                CHILD_ANCHOR -> if (child.length >= 16) box = intArrayOf(s32(d, child.data), s32(d, child.data + 4), s32(d, child.data + 8), s32(d, child.data + 12))
                CLIENT_DATA -> forEachChild(child) {
                    if (it.type == OE_PLACEHOLDER && it.length >= 5) placeholder = d[it.data + 4].toInt() and 0xFF
                }
                CLIENT_TEXTBOX -> readTextbox(child, pieces)
                OPT -> number = pictureNumber(child)
                CLIENT_ANCHOR -> {
                    // Its top, left, right and bottom, in 576ths of an inch: two bytes each, or four.
                    val wide = child.length >= 16
                    val step = if (wide) 4 else 2
                    if (child.length >= 4 * step) {
                        fun at(i: Int) = if (wide) s32(d, child.data + i * 4) else u16(d, child.data + i * 2).toShort().toInt()
                        width = ((at(2) - at(1)) * EMU_PER_UNIT).toLong().coerceAtLeast(0)
                        height = ((at(3) - at(0)) * EMU_PER_UNIT).toLong().coerceAtLeast(0)
                        if (box == null) box = intArrayOf(at(1), at(0), at(2), at(3))
                    }
                }
            }
        }
        val where = blips.getOrNull(number - 1)?.takeIf { it >= 0 }
        val picture = where?.let { PptPicture(it, width, height) }
        // An empty box is kept only in a group, where it may be a cell with nothing in it.
        val empty = pieces.isEmpty() && picture == null
        if (deleted || (empty && !(boxes && type == SHAPE_RECTANGLE && box != null))) return null
        return PptShape(placeholder, pieces, picture, type, box)
    }

    /** Which picture a shape's properties say it shows, by number from 1; 0 for none. */
    private fun pictureNumber(opt: Rec): Int {
        // Six bytes each: an id, with a flag for a number that stands for a picture, and its value.
        for (i in 0 until minOf(opt.instance, opt.length / 6)) {
            val id = u16(d, opt.data + i * 6)
            if (id and 0x3FFF == PIB && id and 0x4000 != 0) return s32(d, opt.data + i * 6 + 2).coerceIn(0, MAX_BLIPS)
        }
        return 0
    }

    private fun readTextbox(box: Rec, pieces: MutableList<PptPiece>) {
        var run: PptText? = null
        fun current(): PptText = run ?: PptText(TEXT_OTHER).also {
            run = it
            pieces += PptPiece.Own(it)
        }
        forEachChild(box) { child ->
            when (child.type) {
                TEXT_HEADER -> {
                    val next = PptText(if (child.length >= 4) s32(d, child.data) else TEXT_OTHER)
                    run = next
                    pieces += PptPiece.Own(next)
                }
                TEXT_CHARS -> current().text = utf16(child)
                TEXT_BYTES -> current().text = latin1(child)
                STYLE_TEXT_PROP -> run?.style = d.copyOfRange(child.data, child.end)
                in FIELD_MARKS -> if (child.length >= 4) run?.addField(s32(d, child.data))
                OUTLINE_REF -> if (child.length >= 4) pieces += PptPiece.Listed(s32(d, child.data))
            }
        }
    }

    // ---- Master styles ------------------------------------------------------

    /** The paragraph styles of the master a slide is built on, by text type and level. */
    private fun stylesOf(masterId: Long, masters: List<PptEntry>): Map<Int, List<PptParagraphProps>> =
        masterStyles.getOrPut(masterId) {
            val master = masters.firstOrNull { it.id == masterId }?.let { persisted(it.persistId, MAIN_MASTER) }
                ?: return@getOrPut emptyMap()
            val out = HashMap<Int, List<PptParagraphProps>>()
            forEachChild(master) { child ->
                if (child.type == TX_MASTER_STYLE) out.putIfAbsent(child.instance, readMasterStyle(child))
            }
            out
        }

    /** Each level's paragraph properties, as far as they can be read. */
    private fun readMasterStyle(atom: Rec): List<PptParagraphProps> {
        val out = ArrayList<PptParagraphProps>()
        try {
            val cursor = PptCursor(d, atom.data, atom.end)
            val levels = cursor.read16().coerceAtMost(5)
            repeat(levels) {
                // Text of the later types names its level first.
                if (atom.instance >= TEXT_CENTER_BODY) cursor.skip(2)
                out += readParagraphProps(cursor, level = out.size)
                readCharacterProps(cursor)
            }
        } catch (e: PptCut) {
            // What was read, is.
        }
        return out
    }

    // ---- The formatting of a run of text ------------------------------------

    /** One paragraph's properties at [cursor]: which are told, then each of those. */
    private fun readParagraphProps(cursor: PptCursor, level: Int): PptParagraphProps {
        val masks = cursor.read32()
        val props = PptParagraphProps()
        props.level = level
        var bulletFlags = 0
        if (masks and 0xF != 0) bulletFlags = cursor.read16()
        if (masks and 0x1 != 0) props.bullet = bulletFlags and 0x1 != 0
        if (masks and 0x80 != 0) props.bulletChar = cursor.read16()
        if (masks and 0x10 != 0) props.bulletFont = cursor.read16()
        if (masks and 0x40 != 0) cursor.skip(2)
        if (masks and 0x20 != 0) cursor.skip(4)
        if (masks and 0x800 != 0) props.align = cursor.read16()
        // Line spacing, the space before and after, the margins and the tab size.
        if (masks and 0x1000 != 0) cursor.skip(2)
        if (masks and 0x2000 != 0) cursor.skip(2)
        if (masks and 0x4000 != 0) cursor.skip(2)
        if (masks and 0x100 != 0) cursor.skip(2)
        if (masks and 0x400 != 0) cursor.skip(2)
        if (masks and 0x8000 != 0) cursor.skip(2)
        if (masks and 0x100000 != 0) cursor.skip(cursor.read16() * 4)
        if (masks and 0x10000 != 0) cursor.skip(2)
        if (masks and 0xE0000 != 0) cursor.skip(2)
        if (masks and 0x200000 != 0) props.rightToLeft = cursor.read16() == 1
        return props
    }

    private fun readCharacterProps(cursor: PptCursor): PptCharacterProps {
        val masks = cursor.read32()
        var style = 0
        if (masks and 0xFFFF != 0) style = cursor.read16()
        // The fonts, the size, the colour and the rise.
        if (masks and 0x10000 != 0) cursor.skip(2)
        if (masks and 0x200000 != 0) cursor.skip(2)
        if (masks and 0x400000 != 0) cursor.skip(2)
        if (masks and 0x800000 != 0) cursor.skip(2)
        if (masks and 0x20000 != 0) cursor.skip(2)
        if (masks and 0x40000 != 0) cursor.skip(4)
        if (masks and 0x80000 != 0) cursor.skip(2)
        // A style is only told where its mask says so.
        return PptCharacterProps(
            bold = masks and 0x1 != 0 && style and 0x1 != 0,
            italic = masks and 0x2 != 0 && style and 0x2 != 0,
            underline = masks and 0x4 != 0 && style and 0x4 != 0,
        )
    }

    /**
     * A StyleTextPropAtom: for each run of [length] characters, and the one
     * more that ends the last paragraph, a paragraph's properties; and then
     * the same for the characters'. Null for one that doesn't fit its text.
     */
    private fun readStyle(data: ByteArray, length: Int): PptStyle? {
        val cursor = PptCursor(data, 0, data.size)
        val paragraphs = ArrayList<StyleRun<PptParagraphProps>>()
        val characters = ArrayList<StyleRun<PptCharacterProps>>()
        try {
            var covered = 0L
            while (covered < length + 1L) {
                if (paragraphs.size >= MAX_RUNS) return null
                val count = cursor.read32()
                val level = cursor.read16()
                if (count < 0) return null
                covered += count
                paragraphs += StyleRun(covered, readParagraphProps(cursor, level))
            }
            covered = 0L
            while (covered < length + 1L) {
                if (characters.size >= MAX_RUNS) return null
                val count = cursor.read32()
                if (count < 0) return null
                covered += count
                characters += StyleRun(covered, readCharacterProps(cursor))
            }
        } catch (e: PptCut) {
            return null
        }
        return PptStyle(paragraphs, characters)
    }

    // ---- Paragraphs ---------------------------------------------------------

    /** The paragraphs of [text]: its characters in the spans they are formatted in, its levels and its bullets. */
    private fun paragraphs(text: PptContent.Text, notes: Boolean, master: List<PptParagraphProps>?): List<DocParagraph> {
        val raw = text.run.text
        val style = text.run.style?.let { readStyle(it, raw.length) }
        val out = ArrayList<DocParagraph>()
        val spans = ArrayList<DocSpan>()
        val word = StringBuilder()
        var shown: PptCharacterProps? = null
        var characterRun = 0
        var paragraphRun = 0
        var start = 0

        fun flushWord() {
            if (word.isEmpty()) return
            val look = shown
            val span = DocSpan(word.toString(), look?.bold == true, look?.italic == true, look?.underline == true, false)
            val last = spans.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                spans[spans.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                spans += span
            }
            word.setLength(0)
        }

        fun finish() {
            flushWord()
            if (spans.any { it.text.isNotBlank() }) {
                // The properties of the run that holds the paragraph's first character.
                val runs = style?.paragraphs
                if (runs != null) while (paragraphRun < runs.lastIndex && start >= runs[paragraphRun].end) paragraphRun++
                out += paragraph(spans.toList(), runs?.getOrNull(paragraphRun)?.props, text, notes, master)
            }
            spans.clear()
        }

        for ((i, char) in raw.withIndex()) {
            if (out.size >= Docx.MAX_TOGETHER) {
                cut = true
                break
            }
            val runs = style?.characters
            if (runs != null) while (characterRun < runs.lastIndex && i >= runs[characterRun].end) characterRun++
            val look = runs?.getOrNull(characterRun)?.props
            if (look !== shown && (look?.bold != shown?.bold || look?.italic != shown?.italic || look?.underline != shown?.underline)) {
                flushWord()
            }
            shown = look
            when {
                // Where a field stands: for PowerPoint to fill in.
                text.run.fields?.contains(i) == true && char != '\r' -> Unit
                // A paragraph ends.
                char == '\r' -> {
                    finish()
                    start = i + 1
                }
                // A line ends, in the paragraph.
                char == '\u000B' -> word.append('\n')
                char == '\t' || char >= ' ' -> word.append(char)
            }
        }
        finish()
        return out
    }

    private fun paragraph(spans: List<DocSpan>, props: PptParagraphProps?, text: PptContent.Text, notes: Boolean, master: List<PptParagraphProps>?): DocParagraph {
        val level = (props?.level ?: 0).coerceIn(0, Docx.MAX_LEVEL)
        val inherited = master?.getOrNull(level)
        val type = text.run.type
        // What the paragraph says of itself, then its master, and only then
        // what almost every master has: body text is bulleted.
        val bulleted = props?.bullet ?: inherited?.bullet
            ?: (!notes && (type == TEXT_BODY || type == TEXT_HALF_BODY || type == TEXT_QUARTER_BODY))
        val marker = if (bulleted) {
            val code = (props?.bulletChar ?: inherited?.bulletChar)?.takeIf { it > 0 }
            val font = (props?.bulletFont ?: inherited?.bulletFont)?.let { fonts.getOrNull(it) }
            if (code == null) BULLET_GLYPHS[level % BULLET_GLYPHS.size] else bulletGlyph(code.toChar().toString(), isSymbolFont(font, null))
        } else {
            null
        }
        val align = when (props?.align ?: inherited?.align) {
            0 -> DocAlign.LEFT
            1 -> DocAlign.CENTER
            2 -> DocAlign.RIGHT
            3, 4, 5, 6 -> DocAlign.JUSTIFY
            else -> DocAlign.START
        }
        val docStyle = when {
            text.placeholder == CENTERED_TITLE_PLACEHOLDER || type == TEXT_CENTER_TITLE -> DocStyle.TITLE
            type == TEXT_TITLE || text.placeholder in TITLE_PLACEHOLDERS -> DocStyle.HEADING1
            text.placeholder == SUBTITLE_PLACEHOLDER || type == TEXT_CENTER_BODY -> DocStyle.SUBTITLE
            else -> DocStyle.BODY
        }
        return DocParagraph(
            spans = spans,
            style = docStyle,
            align = align,
            marker = marker,
            level = if (marker == null) 0 else level,
            // Right to left, where it says so. Left to right is only what every
            // English file says, and no reason to stop looking at the text.
            rtl = (props?.rightToLeft ?: inherited?.rightToLeft)?.takeIf { it },
        )
    }

    private companion object {
        val BULLET_GLYPHS = listOf("•", "◦", "▪")
    }
}
