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
private const val SP_ATOM = 0xF00A
private const val CLIENT_TEXTBOX = 0xF00D
private const val CLIENT_DATA = 0xF011

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

private class PptShape(val placeholder: Int, val pieces: List<PptPiece>)

/** One slide - or master, or page of notes - in a slide list: where it is kept, what it is called, and the text the list holds for it. */
private class PptEntry(val persistId: Int, val id: Long) {
    val texts = ArrayList<PptText>()
}

/** A run of text, and whose it is. */
private class PptResolved(val run: PptText, val placeholder: Int)

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
        val texts = resolve(drawing, entry)
        val blocks = ArrayList<DocBlock>()
        // The title first, wherever the slide keeps it; the rest as it comes.
        for (text in texts.sortedByDescending { isTitle(it) }) {
            if (blocks.size >= Docx.MAX_TOGETHER) {
                cut = true
                break
            }
            paragraphs(text, notes = false, styles[text.run.type]).forEach { if (room(it)) blocks += it }
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
            for (text in resolve(notesDrawing, page)) {
                paragraphs(text, notes = true, null).forEach { if (notes.size < Docx.MAX_TOGETHER && room(it)) notes += it }
                if (spent) break
            }
        }
        return PptxSlide(number, blocks, notes, hidden)
    }

    private fun isTitle(text: PptResolved) =
        text.run.type == TEXT_TITLE || text.run.type == TEXT_CENTER_TITLE || text.placeholder in TITLE_PLACEHOLDERS

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
    private fun resolve(drawing: Rec?, entry: PptEntry): List<PptResolved> {
        val out = ArrayList<PptResolved>()
        val shapes = drawing?.let { shapesOf(it) }.orEmpty()
        for (shape in shapes) {
            if (shape.placeholder in FURNITURE_PLACEHOLDERS) continue
            for (piece in shape.pieces) {
                val run = when (piece) {
                    is PptPiece.Own -> piece.run
                    is PptPiece.Listed -> entry.texts.getOrNull(piece.index)
                }
                if (run != null && run.text.isNotEmpty() && out.size < Docx.MAX_TOGETHER) out += PptResolved(run, shape.placeholder)
            }
        }
        if (out.isEmpty()) {
            for (run in entry.texts) if (run.text.isNotEmpty()) out += PptResolved(run, 0)
        }
        return out
    }

    private fun shapesOf(drawing: Rec): List<PptShape> {
        val out = ArrayList<PptShape>()
        fun walk(container: Rec, depth: Int) {
            if (depth > MAX_DEPTH) return
            forEachChild(container) { child ->
                when {
                    child.type == SP_CONTAINER && child.version == 0xF -> shape(child)?.let {
                        // Shapes with text: as many as a slide is shown at once.
                        if (out.size < Docx.MAX_TOGETHER) out += it else cut = true
                    }
                    (child.type == SPGR_CONTAINER || child.type == DG_CONTAINER) && child.version == 0xF -> walk(child, depth + 1)
                }
            }
        }
        forEachChild(drawing) { if (it.type == DG_CONTAINER && it.version == 0xF) walk(it, 0) }
        return out
    }

    private fun shape(container: Rec): PptShape? {
        var placeholder = 0
        var deleted = false
        val pieces = ArrayList<PptPiece>()
        forEachChild(container) { child ->
            when (child.type) {
                SP_ATOM -> if (child.length >= 8) deleted = s32(d, child.data + 4) and 0x8 != 0
                CLIENT_DATA -> forEachChild(child) {
                    if (it.type == OE_PLACEHOLDER && it.length >= 5) placeholder = d[it.data + 4].toInt() and 0xFF
                }
                CLIENT_TEXTBOX -> readTextbox(child, pieces)
            }
        }
        return if (deleted || pieces.isEmpty()) null else PptShape(placeholder, pieces)
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
    private fun paragraphs(text: PptResolved, notes: Boolean, master: List<PptParagraphProps>?): List<DocParagraph> {
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

    private fun paragraph(spans: List<DocSpan>, props: PptParagraphProps?, text: PptResolved, notes: Boolean, master: List<PptParagraphProps>?): DocParagraph {
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
