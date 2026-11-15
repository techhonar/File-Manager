package com.filemanager.app.data.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class PptTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- The records of a presentation, as PowerPoint writes them ---------

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun record(type: Int, instance: Int = 0, version: Int = 0, body: ByteArray = ByteArray(0)): ByteArray =
        le16((instance shl 4) or version) + le16(type) + le32(body.size) + body

    private fun container(type: Int, instance: Int = 0, vararg children: ByteArray): ByteArray =
        record(type, instance, 0xF, children.fold(ByteArray(0)) { all, child -> all + child })

    private fun header(type: Int) = record(0x0F9F, body = le32(type))

    private fun chars(text: String) = record(0x0FA0, body = text.toByteArray(Charsets.UTF_16LE))

    private fun bytes(text: String) = record(0x0FA8, body = text.toByteArray(Charsets.ISO_8859_1))

    private fun outline(index: Int) = record(0x0F9E, body = le32(index))

    /** Where in the text a field stands - 0x0FD8 is the page number's. */
    private fun field(position: Int) = record(0x0FD8, body = le32(position))

    /** A paragraph's properties as a style atom tells them; null says nothing of it. */
    private class P(val count: Int, val level: Int = 0, val bullet: Boolean? = null, val char: Char? = null, val rtl: Boolean? = null)

    /** A run of characters' properties. */
    private class C(val count: Int, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null)

    private fun paragraphProps(p: P): ByteArray {
        var masks = 0
        if (p.bullet != null) masks = masks or 0x1
        if (p.char != null) masks = masks or 0x80
        if (p.rtl != null) masks = masks or 0x200000
        var out = le32(masks)
        if (masks and 0xF != 0) out += le16(if (p.bullet == true) 1 else 0)
        if (p.char != null) out += le16(p.char.code)
        if (p.rtl != null) out += le16(if (p.rtl) 1 else 0)
        return out
    }

    private fun characterProps(c: C): ByteArray {
        var masks = 0
        var style = 0
        if (c.bold != null) { masks = masks or 0x1; if (c.bold) style = style or 0x1 }
        if (c.italic != null) { masks = masks or 0x2; if (c.italic) style = style or 0x2 }
        if (c.underline != null) { masks = masks or 0x4; if (c.underline) style = style or 0x4 }
        return le32(masks) + if (masks and 0xFFFF != 0) le16(style) else ByteArray(0)
    }

    private fun style(paragraphs: List<P>, characters: List<C>) = record(
        0x0FA1,
        body = paragraphs.fold(ByteArray(0)) { all, p -> all + le32(p.count) + le16(p.level) + paragraphProps(p) } +
            characters.fold(ByteArray(0)) { all, c -> all + le32(c.count) + characterProps(c) },
    )

    /** A shape: a placeholder if [placeholder] says which, with [text] in its text box. */
    private fun shape(placeholder: Int?, vararg text: ByteArray, deleted: Boolean = false): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts += record(0xF00A, instance = 1, version = 2, body = le32(1) + le32(if (deleted) 0x8 else 0))
        if (placeholder != null) {
            parts += container(0xF011, 0, record(0x0BC3, body = le32(0) + byteArrayOf(placeholder.toByte(), 0, 0, 0)))
        }
        if (text.isNotEmpty()) parts += container(0xF00D, 0, *text)
        return container(0xF004, 0, *parts.toTypedArray())
    }

    private fun drawing(vararg shapes: ByteArray) = container(0x040C, 0, container(0xF002, 0, container(0xF003, 0, *shapes)))

    private fun slide(drawing: ByteArray, masterId: Long = 0, notesId: Long = 0, hidden: Boolean = false): ByteArray {
        val atom = record(0x03EF, version = 2, body = le32(0) + ByteArray(8) + le32(masterId.toInt()) + le32(notesId.toInt()) + le16(0) + le16(0))
        val info = record(0x03F9, body = ByteArray(10) + le16(if (hidden) 4 else 0) + ByteArray(4))
        return container(0x03EE, 0, atom, info, drawing)
    }

    private fun notesPage(drawing: ByteArray) = container(0x03F0, 0, record(0x03F1, version = 1, body = ByteArray(8)), drawing)

    private fun master(vararg styles: ByteArray) = container(0x03F8, 0, *styles)

    /** A master's style for text of [type]: each level's bullet on or off, and its character. */
    private fun masterStyle(type: Int, vararg levels: P): ByteArray {
        var body = le16(levels.size)
        for (level in levels) {
            if (type >= 5) body += le16(0)
            body += paragraphProps(level) + characterProps(C(0))
        }
        return record(0x0FA3, instance = type, body = body)
    }

    private fun persistAtom(persistId: Int, id: Long) = record(0x03F3, body = le32(persistId) + le32(0) + le32(0) + le32(id.toInt()) + le32(0))

    private fun list(instance: Int, vararg children: ByteArray) = container(0x0FF0, instance, *children)

    private fun document(slides: ByteArray, masters: ByteArray? = null, notes: ByteArray? = null) =
        container(0x03E8, 0, *listOfNotNull(record(0x03E9, body = ByteArray(40)), masters, slides, notes).toTypedArray())

    /** The stream, built up as it is by saving: what is new is added at its end, with a table of where everything is. */
    private class Presentation {
        private val out = ByteArrayOutputStream()
        private var lastEdit = 0

        private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

        private fun record(type: Int, body: ByteArray) =
            byteArrayOf(0, 0, type.toByte(), (type shr 8).toByte()) + le32(body.size) + body

        /** One save of [objects], by persist id, with the table of where they are and the edit that points to it. */
        fun save(documentId: Int, objects: Map<Int, ByteArray>, loopsToItself: Boolean = false) {
            val where = LinkedHashMap<Int, Int>()
            for ((id, bytes) in objects) {
                where[id] = out.size()
                out.write(bytes)
            }
            val table = where.entries.fold(ByteArray(0)) { all, (id, at) -> all + le32((1 shl 20) or id) + le32(at) }
            val tableAt = out.size()
            out.write(record(0x1772, table))
            val editAt = out.size()
            val previous = if (loopsToItself) editAt else lastEdit
            out.write(record(0x0FF5, le32(0) + le32(0) + le32(previous) + le32(tableAt) + le32(documentId) + le32(0) + le32(0)))
            lastEdit = editAt
        }

        fun stream(): ByteArray = out.toByteArray()

        /** The "Current User" stream: it says where the last save's edit is. */
        fun user(encrypted: Boolean = false): ByteArray = record(
            0x0FF6,
            le32(0x14) + le32(if (encrypted) 0xF3D1C4DF.toInt() else 0xE391C05F.toInt()) + le32(lastEdit) + byteArrayOf(0, 0, 0xF4.toByte(), 3, 3, 0, 0, 0),
        )
    }

    private fun read(presentation: Presentation, maxChars: Int = Pptx.MAX_CHARS, encrypted: Boolean = false) =
        PowerPointBinary.read(presentation.stream(), presentation.user(encrypted), maxChars)

    /** A presentation of one slide, saved once, with [text] in the shapes [slide] gives and [list] after its entry in the slide list. */
    private fun oneSlide(slide: ByteArray, vararg list: ByteArray, masters: ByteArray? = null, notes: ByteArray? = null, others: Map<Int, ByteArray> = emptyMap()): Presentation =
        Presentation().apply {
            save(1, mapOf(1 to document(list(0, persistAtom(3, 256), *list), masters, notes), 3 to slide) + others)
        }

    private fun PptxSlide.paragraphs() = blocks.filterIsInstance<DocParagraph>()

    // ---- Where the text is ------------------------------------------------------

    @Test
    fun `a placeholder's text is in the slide list and a text box's is in its shape, and the title comes first`() {
        val deck = read(
            oneSlide(
                slide(
                    drawing(
                        shape(null, header(4), chars("Own words")),
                        shape(13, header(0), outline(0)),
                        shape(14, header(1), outline(1)),
                    ),
                ),
                header(0), chars("Harbour survey"),
                header(1), chars("First point\rSecond point"),
            ),
        )
        val slide = deck.slides.single()
        // The title first, and the rest in the order of the drawing, as in a .pptx.
        assertEquals(
            listOf(null to "Harbour survey", null to "Own words", "•" to "First point", "•" to "Second point"),
            slide.paragraphs().map { it.marker to it.text },
        )
        assertEquals(DocStyle.HEADING1, slide.paragraphs().first().style)
        assertEquals(1, deck.total)
        assertFalse(deck.truncated)
    }

    @Test
    fun `a page whose drawing says nothing is shown as the slide list has it`() {
        val deck = read(oneSlide(slide(drawing()), header(0), chars("Only here"), header(1), chars("And here")))
        assertEquals(listOf("Only here", "And here"), deck.slides.single().paragraphs().map { it.text })
    }

    @Test
    fun `a later save's slide is the slide, and what it did not change is read from the save before`() {
        fun withText(text: String) = slide(drawing(shape(null, header(4), chars(text))))
        val presentation = Presentation()
        presentation.save(1, mapOf(1 to document(list(0, persistAtom(3, 256))), 3 to withText("Old words")))
        presentation.save(1, mapOf(3 to withText("New words")))
        assertEquals(listOf("New words"), read(presentation).slides.single().paragraphs().map { it.text })
    }

    @Test
    fun `slides come in the slide list's order, whatever order they were saved in`() {
        fun withText(text: String) = slide(drawing(shape(null, header(4), chars(text))))
        val presentation = Presentation()
        presentation.save(
            1,
            mapOf(
                1 to document(list(0, persistAtom(5, 258), persistAtom(3, 256), persistAtom(4, 257))),
                3 to withText("second saved"),
                4 to withText("third saved"),
                5 to withText("first listed"),
            ),
        )
        val deck = read(presentation)
        assertEquals(listOf("first listed", "second saved", "third saved"), deck.slides.map { it.paragraphs().single().text })
        assertEquals(listOf(1, 2, 3), deck.slides.map { it.number })
    }

    @Test
    fun `text in the old 8-bit form and in UTF-16 both come out as the characters they are`() {
        val deck = read(oneSlide(slide(drawing(shape(null, header(4), bytes("Café au lait")), shape(null, header(4), chars("گزارش"))))))
        assertEquals(listOf("Café au lait", "گزارش"), deck.slides.single().paragraphs().map { it.text })
    }

    @Test
    fun `a deleted shape is left out`() {
        val deck = read(oneSlide(slide(drawing(shape(null, header(4), chars("Kept")), shape(null, header(4), chars("Gone"), deleted = true)))))
        assertEquals(listOf("Kept"), deck.slides.single().paragraphs().map { it.text })
    }

    @Test
    fun `the date, number, footer and header every slide repeats are left out, and fields are for PowerPoint to fill in`() {
        val deck = read(
            oneSlide(
                slide(
                    drawing(
                        shape(13, header(0), chars("Title")),
                        shape(9, header(4), chars("Company name")),
                        shape(8, header(4), chars("*"), field(0)),
                        shape(null, header(4), chars("*"), field(0)),
                        shape(null, header(4), chars("Page *"), field(5)),
                    ),
                ),
            ),
        )
        assertEquals(listOf("Title", "Page"), deck.slides.single().paragraphs().map { it.text.trim() })
    }

    // ---- How it is set -------------------------------------------------------------

    @Test
    fun `levels, bullets and their characters, and bold, italic and underline, are as the text's style says`() {
        // "Alpha\rBeta\rGamma": 16 characters, and the one that ends the last paragraph.
        val deck = read(
            oneSlide(
                slide(
                    drawing(
                        shape(
                            null,
                            header(4),
                            chars("Alpha\rBeta\rGamma"),
                            style(
                                paragraphs = listOf(P(6, level = 0, bullet = true), P(5, level = 1, bullet = true, char = '–'), P(6, level = 2, bullet = false)),
                                characters = listOf(C(6), C(5, bold = true, italic = true), C(3, underline = true), C(3)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val (alpha, beta, gamma) = deck.slides.single().paragraphs()
        assertEquals(Triple("•", 0, "Alpha"), Triple(alpha.marker, alpha.level, alpha.text))
        assertEquals(Triple("–", 1, "Beta"), Triple(beta.marker, beta.level, beta.text))
        // No bullet, no indent for one.
        assertEquals(Triple(null, 0, "Gamma"), Triple(gamma.marker, gamma.level, gamma.text))
        assertEquals(listOf(DocSpan("Beta", bold = true, italic = true)), beta.spans)
        assertEquals(listOf(DocSpan("Gam", underline = true), DocSpan("ma")), gamma.spans)
    }

    @Test
    fun `a line break is a new line in the same paragraph, and a paragraph with nothing in it is left out`() {
        val deck = read(oneSlide(slide(drawing(shape(null, header(4), chars("One\u000Btwo\r\r  \rThree\u0001"))))))
        assertEquals(listOf("One\ntwo", "Three"), deck.slides.single().paragraphs().map { it.text })
    }

    @Test
    fun `a style that does not fit its text is ignored, and the text is still shown`() {
        val deck = read(
            oneSlide(
                slide(drawing(shape(null, header(1), chars("Alpha\rBeta"), style(listOf(P(2), P(2, bullet = false)), listOf(C(900)))))),
            ),
        )
        // Four characters of ten: the style is not believed, and body text is bulleted as it is when there is none.
        assertEquals(listOf("•" to "Alpha", "•" to "Beta"), deck.slides.single().paragraphs().map { it.marker to it.text })
    }

    @Test
    fun `right to left, and the kinds of title`() {
        val deck = read(
            oneSlide(
                slide(
                    drawing(
                        shape(15, header(6), chars("Centred")),
                        shape(16, header(5), chars("Sub")),
                        shape(null, header(4), chars("نکته"), style(listOf(P(5, rtl = true)), listOf(C(5)))),
                        shape(null, header(4), chars("Left to right"), style(listOf(P(14, rtl = false)), listOf(C(14)))),
                    ),
                ),
            ),
        )
        val paragraphs = deck.slides.single().paragraphs()
        assertEquals(listOf(DocStyle.TITLE, DocStyle.SUBTITLE, DocStyle.BODY, DocStyle.BODY), paragraphs.map { it.style })
        // Left to right is what every English text says: no reason to stop looking at the text.
        assertEquals(listOf(null, null, true, null), paragraphs.map { it.rtl })
    }

    @Test
    fun `what a master says of bullets is the default for the text built on it`() {
        val masters = list(1, persistAtom(2, 0x80000000L))
        val deck = read(
            oneSlide(
                slide(
                    drawing(
                        shape(14, header(1), chars("Top\rDown\rDeeper"), style(listOf(P(4), P(5, level = 1), P(7, level = 2)), listOf(C(16)))),
                        shape(null, header(4), chars("Plain")),
                        shape(13, header(0), chars("Title")),
                    ),
                    masterId = 0x80000000L,
                ),
                masters = masters,
                others = mapOf(
                    2 to master(
                        // Body text: a dot, a dash, and no bullet at the third level; a text box has none.
                        masterStyle(1, P(0, bullet = true, char = '•'), P(0, bullet = true, char = '–'), P(0, bullet = false)),
                        masterStyle(4, P(0, bullet = false)),
                        masterStyle(0, P(0, bullet = false)),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(null to "Title", "•" to "Top", "–" to "Down", null to "Deeper", null to "Plain"),
            deck.slides.single().paragraphs().map { it.marker to it.text },
        )
    }

    // ---- The rest of a slide ----------------------------------------------------------

    @Test
    fun `a slide knows whether it is hidden, and has the notes of its own page`() {
        val noted = slide(drawing(shape(null, header(4), chars("Slide text"))), notesId = 700, hidden = true)
        val bare = slide(drawing(shape(null, header(4), chars("No notes here"))))
        val page = notesPage(
            drawing(
                // The picture of the slide, the notes, and the page number a page of notes has.
                shape(11),
                shape(12, header(2), chars("Remember the tide")),
                shape(null, header(4), chars("*"), field(0)),
            ),
        )
        val presentation = Presentation()
        presentation.save(
            1,
            mapOf(
                1 to document(list(0, persistAtom(3, 256), persistAtom(4, 257)), notes = list(2, persistAtom(5, 700))),
                3 to noted,
                4 to bare,
                5 to page,
            ),
        )
        val (first, second) = read(presentation).slides
        assertTrue(first.hidden)
        assertEquals(listOf("Remember the tide"), first.notes.map { it.text })
        assertEquals(listOf("Slide text"), first.paragraphs().map { it.text })
        // A slide with no page of notes has none - and is not shown its own text for them.
        assertFalse(second.hidden)
        assertEquals(emptyList<DocParagraph>(), second.notes)
        assertEquals(listOf("No notes here"), second.paragraphs().map { it.text })
    }

    @Test
    fun `a notes page keeps its text in the notes list where its shape says so`() {
        val noted = slide(drawing(shape(null, header(4), chars("Slide text"))), notesId = 700)
        val page = notesPage(drawing(shape(12, header(2), outline(0))))
        val presentation = Presentation()
        presentation.save(
            1,
            mapOf(
                1 to document(list(0, persistAtom(3, 256)), notes = list(2, persistAtom(5, 700), header(2), chars("Said in the list"))),
                3 to noted,
                5 to page,
            ),
        )
        assertEquals(listOf("Said in the list"), read(presentation).slides.single().notes.map { it.text })
    }

    // ---- What isn't one ------------------------------------------------------------------------

    @Test(expected = SecurityException::class)
    fun `a presentation protected with a password says so`() {
        read(oneSlide(slide(drawing(shape(null, header(4), chars("Secret"))))), encrypted = true)
    }

    @Test
    fun `a stream with nothing in it that is a presentation is not one`() {
        for (bytes in listOf(ByteArray(0), ByteArray(7), ByteArray(300), ByteArray(300) { 0xFF.toByte() })) {
            val failed = runCatching { PowerPointBinary.read(bytes, null, Pptx.MAX_CHARS) }.exceptionOrNull()
            assertTrue("${bytes.size} bytes: $failed", failed is IOException)
        }
    }

    // ---- Files made to break a reader -----------------------------------------------------------

    @Test
    fun `edits that lead back to themselves, tables that point nowhere and slides that are not slides end the reading`() {
        val looping = Presentation()
        looping.save(1, mapOf(1 to document(list(0, persistAtom(3, 256), persistAtom(9, 257))), 3 to slide(drawing(shape(null, header(4), chars("Fine"))))), loopsToItself = true)
        // Persist 9 is nowhere; the slide is an empty one and the rest is read.
        val deck = read(looping)
        assertEquals(listOf("Fine"), deck.slides[0].paragraphs().map { it.text })
        assertEquals(emptyList<DocBlock>(), deck.slides[1].blocks)
    }

    @Test
    fun `more slides than are shown stop at the most, and say so`() {
        val entries = Array(Pptx.MAX_SLIDES + 100) { persistAtom(3, 256L + it) }
        val presentation = Presentation()
        presentation.save(1, mapOf(1 to document(list(0, *entries)), 3 to slide(drawing(shape(null, header(4), chars("Same slide"))))))
        val deck = read(presentation)
        assertEquals(Pptx.MAX_SLIDES, deck.slides.size)
        assertEquals(Pptx.MAX_SLIDES + 100, deck.total)
        assertTrue(deck.truncated)
    }

    @Test
    fun `more text than is read stops at the most, and says so`() {
        val long = "x".repeat(100)
        val presentation = oneSlide(slide(drawing(shape(null, header(4), chars("$long\r$long\r$long")))))
        val deck = read(presentation, maxChars = 150)
        assertTrue(deck.truncated)
        // As far as 150 characters go, and no further.
        assertTrue(deck.slides.single().paragraphs().sumOf { it.text.length } <= 150)
        assertTrue(deck.slides.single().paragraphs().size < 3)
    }

    @Test
    fun `a paragraph run said to cover two thousand million characters, and levels past any there are, are kept in bounds`() {
        val deck = read(
            oneSlide(
                slide(
                    drawing(
                        shape(null, header(1), chars("One\rTwo"), style(listOf(P(Int.MAX_VALUE, level = 60000, bullet = true)), listOf(C(Int.MAX_VALUE, bold = true)))),
                        shape(null, header(1), chars("Three"), style(listOf(P(-1, level = 1)), listOf(C(-5)))),
                    ),
                ),
            ),
        )
        val paragraphs = deck.slides.single().paragraphs()
        assertEquals(listOf("One", "Two", "Three"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.level in 0..Docx.MAX_LEVEL })
        assertTrue(paragraphs.take(2).all { it.spans.single().bold })
    }

    @Test
    fun `slides that all say they are one heavy slide are read until the paragraphs are more than a document is read to`() {
        // Each slide shows five thousand paragraphs: a hundred of them are five hundred thousand.
        val many = "x\r".repeat(Docx.MAX_TOGETHER - 1) + "x"
        val entries = Array(100) { persistAtom(3, 256L + it) }
        val presentation = Presentation()
        presentation.save(1, mapOf(1 to document(list(0, *entries)), 3 to slide(drawing(shape(null, header(4), chars(many))))))
        val deck = read(presentation)
        val paragraphs = deck.slides.sumOf { it.paragraphs().size }
        assertTrue(deck.truncated)
        assertTrue("$paragraphs paragraphs", paragraphs <= Docx.MAX_PIECES)
        assertTrue(deck.slides.size in 70..99)
    }

    @Test
    fun `a slide of more text shapes than are shown at once keeps that many, and says so`() {
        val shapes = Array(Docx.MAX_TOGETHER + 300) { shape(null, header(4), chars("t$it")) }
        val deck = read(oneSlide(slide(drawing(*shapes))))
        assertTrue(deck.truncated)
        assertEquals(Docx.MAX_TOGETHER, deck.slides.single().paragraphs().size)
    }

    @Test
    fun `a text with a hundred thousand runs of formatting is shown, without them`() {
        val length = 150_000
        val text = "ab\r".repeat(length / 3)
        val runs = List(length / 3) { P(3, level = it % 5, bullet = true) }
        val deck = read(oneSlide(slide(drawing(shape(null, header(4), chars(text), style(runs, listOf(C(length + 1, bold = true))))))))
        val paragraphs = deck.slides.single().paragraphs()
        // The style is not believed; the text, past what is shown at once, is cut as any would be.
        assertEquals(Docx.MAX_TOGETHER, paragraphs.size)
        assertTrue(paragraphs.all { it.marker == null && it.level == 0 && !it.spans.single().bold })
    }

    @Test
    fun `a text record far larger than is read is read only as far as is`() {
        val huge = chars("y".repeat(3_000_000))
        val deck = read(oneSlide(slide(drawing(shape(null, header(4), huge)))))
        assertTrue(deck.truncated)
        assertTrue(deck.slides.isEmpty() || deck.slides.single().paragraphs().all { it.text.length <= Pptx.MAX_CHARS })
    }

    @Test
    fun `a stream cut anywhere is read as far as it goes, or refused, and never worse`() {
        val whole = oneSlide(
            slide(drawing(shape(14, header(1), outline(0)), shape(null, header(4), chars("Own text"), style(listOf(P(9, bullet = true)), listOf(C(9, bold = true)))))),
            header(1), chars("Listed text"),
        )
        val stream = whole.stream()
        val user = whole.user()
        for (cut in stream.indices step 3) {
            val failed = runCatching { PowerPointBinary.read(stream.copyOf(cut), user, Pptx.MAX_CHARS) }.exceptionOrNull()
            assertTrue("cut at $cut: $failed", failed == null || failed is IOException)
        }
        // Damaged in place, a byte at a time.
        for (at in stream.indices step 2) {
            val damaged = stream.copyOf()
            damaged[at] = (damaged[at].toInt() xor 0xFF).toByte()
            val failed = runCatching { PowerPointBinary.read(damaged, user, Pptx.MAX_CHARS) }.exceptionOrNull()
            assertTrue("damaged at $at: $failed", failed == null || failed is IOException || failed is SecurityException)
        }
    }

    // ---- Whole files ---------------------------------------------------------------------------------

    private fun resource(name: String, saveAs: String = name): File {
        val file = File(temp.root, saveAs)
        file.outputStream().use { out -> javaClass.getResourceAsStream("/viewer/$name")!!.use { it.copyTo(out) } }
        return file
    }

    @Test
    fun `a presentation LibreOffice saved is read slide by slide`() {
        // Made from a deck of five slides: a title, bullets at two levels with notes, one hidden, one set in
        // runs of bold, italic and underline, and one in Persian.
        val deck = Pptx.read(resource("sample.ppt"))
        assertEquals(5, deck.total)
        assertFalse(deck.truncated)
        val (title, bullets, hidden, styles, persian) = deck.slides
        assertEquals(listOf("Harbour survey", "Spring report"), title.paragraphs().map { it.text })
        assertEquals(listOf(DocStyle.HEADING1, DocStyle.SUBTITLE), title.paragraphs().map { it.style })

        assertEquals(
            listOf("Findings", "Quay wall cracked", "East end", "Near the crane", "Bollards loose"),
            bullets.paragraphs().map { it.text },
        )
        assertEquals(listOf(null, "•", "–", "–", "•"), bullets.paragraphs().map { it.marker })
        assertEquals(listOf(0, 0, 1, 1, 0), bullets.paragraphs().map { it.level })
        assertEquals(listOf("Ask the harbour master about the crane."), bullets.notes.map { it.text })

        assertEquals(listOf(false, false, true, false, false), deck.slides.map { it.hidden })
        assertEquals("Not shown", hidden.paragraphs().first().text)

        val runs = styles.paragraphs().last { it.spans.size > 1 }
        assertEquals(
            listOf(DocSpan("Plain, "), DocSpan("bold, ", bold = true), DocSpan("italic, ", italic = true), DocSpan("underlined", underline = true)),
            runs.spans,
        )
        assertEquals(DocAlign.RIGHT, styles.paragraphs().last().align)

        assertEquals(listOf("گزارش بندر", "دیوار اسکله ترک خورده است", "Mixed: بندر harbour"), persian.paragraphs().map { it.text })
        assertEquals(listOf(null, true, null), persian.paragraphs().map { it.rtl })
    }

    @Test
    fun `what is inside a file decides how it is read, not what it is called`() {
        // A .doc is not a presentation.
        val failed = runCatching { Pptx.read(resource("sample.doc")) }.exceptionOrNull()
        assertTrue("$failed", failed is IOException)
        assertEquals("Not a PowerPoint presentation", failed?.message)
        // A presentation called anything.
        assertEquals(5, Pptx.read(resource("sample.ppt", saveAs = "talk.pptx")).total)
        // And the newer format under the older name.
        val zip = File(temp.root, "newer.ppt")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            val pkg = "http://schemas.openxmlformats.org/package/2006/relationships"
            put("_rels/.rels", """<Relationships xmlns="$pkg"><Relationship Id="rId1" Type="$rel/officeDocument" Target="ppt/presentation.xml"/></Relationships>""")
            put("ppt/presentation.xml", """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="$rel"><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>""")
            put("ppt/_rels/presentation.xml.rels", """<Relationships xmlns="$pkg"><Relationship Id="rId2" Type="$rel/slide" Target="slides/slide1.xml"/></Relationships>""")
            put(
                "ppt/slides/slide1.xml",
                """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree>""" +
                    """<p:sp><p:txBody><a:p><a:r><a:t>Newer format</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""",
            )
        }
        assertEquals(listOf("Newer format"), Pptx.read(zip).slides.single().paragraphs().map { it.text })
        assertNull(runCatching { Pptx.read(zip) }.exceptionOrNull())
    }
}
