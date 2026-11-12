package com.filemanager.app.data.viewer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/*
 * What a workbook (.xlsx) and a presentation (.pptx) have in common: like a
 * .docx, a zip of XML parts that point at one another through relationship
 * files - xl/_rels/workbook.xml.rels says where each sheet is, a slide's own
 * .rels where its pictures are.
 */

private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val RELATIONSHIPS_STRICT = "http://purl.oclc.org/ooxml/officeDocument/relationships"

/**
 * Where a relationship points, as a part name in the zip, and the kind of
 * part it is: "worksheet", "slide", "image" - the end of its type.
 */
internal class OoxmlLink(val target: String, val kind: String)

/** The parts of one Office file, read one at a time. */
internal class OoxmlParts(private val zip: ZipFile) {

    /** How much more of the file's parts, unpacked, will be read. */
    private var left = MAX_UNPACKED_BYTES

    /** Reading stopped at [MAX_UNPACKED_BYTES]: what was read is the beginning of what is there. */
    var cutShort = false
        private set

    /**
     * Parse [part] with [handler]; false if the file has no such part. Once
     * the file has been read as far as it will be, no more of any part is:
     * the handler is left with what it had.
     */
    fun parse(part: String, handler: DefaultHandler): Boolean {
        val entry = zip.getEntry(part) ?: return false
        if (cutShort) return true
        zip.getInputStream(entry).use { input ->
            val bounded = BoundedInput(input, left)
            try {
                parsePart(bounded, handler)
            } catch (e: TooLarge) {
                cutShort = true
            } finally {
                left -= bounded.count
            }
        }
        return true
    }

    /** [part]'s relationships by id. Links out of the file are left out. */
    fun links(part: String): Map<String, OoxmlLink> {
        val folder = part.substringBeforeLast('/', "")
        val rels = (if (folder.isEmpty()) "" else "$folder/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val handler = LinksHandler(folder)
        parse(rels, handler)
        return handler.links
    }

    /**
     * The file's main part - xl/workbook.xml, ppt/presentation.xml - where
     * the package says it is, else where it [usually] is.
     */
    fun mainPart(usually: String): String =
        links("").values.firstOrNull { it.kind == "officeDocument" && zip.getEntry(it.target) != null }?.target
            ?: usually
}

/** Never fetches anything a part points to outside the file. */
internal abstract class OoxmlHandler : DefaultHandler() {
    override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
}

/*
 * A file a megabyte long can unpack into gigabytes, or say a million times
 * over something that is kept each time it is said. Opened from a download
 * or a message, it must not be able to take all the memory the app has, nor
 * keep it reading for minutes. So every part is read through the limits
 * here, and every reader counts what it keeps and stops at more than any
 * real file has.
 */

/**
 * As much of a file's parts, unpacked, as is read: several times what the
 * longest document or widest workbook that is shown takes, and a few seconds'
 * reading at the worst.
 */
internal const val MAX_UNPACKED_BYTES = 100L * 1024 * 1024

/** A file that unpacks to more than is read. */
internal class TooLarge : IOException("Too large")

/** Tables in tables in text boxes make a few dozen; thousands is a file made to break the parser. */
private const val MAX_DEPTH = 1_000

/** A document with a link on every line has thousands. */
internal const val MAX_LINKS = 50_000

/**
 * One part of an Office file, parsed with [handler] - but not nested without
 * end, and not at all if it declares a document type: no Office part does,
 * and one can define text that grows a thousandfold each time it is named.
 */
private fun parsePart(input: InputStream, handler: DefaultHandler) {
    val guard = PartGuard(handler)
    val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
    // A parser that can't be told of document types still keeps the other limits.
    runCatching { parser.setProperty("http://xml.org/sax/properties/lexical-handler", guard) }
    parser.parse(input, guard)
}

/** Everything passed on to [inner], but for what [parsePart] guards against. */
private class PartGuard(private val inner: DefaultHandler) : DefaultHandler(), LexicalHandler {
    private var depth = 0

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (++depth > MAX_DEPTH) throw SAXException("Nested too deep")
        inner.startElement(uri, localName, qName, attributes)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        depth--
        inner.endElement(uri, localName, qName)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) = inner.characters(ch, start, length)

    override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))

    override fun startDTD(name: String?, publicId: String?, systemId: String?) = throw SAXException("A document type in an Office part")

    override fun endDTD() = Unit
    override fun startEntity(name: String?) = Unit
    override fun endEntity(name: String?) = Unit
    override fun startCDATA() = Unit
    override fun endCDATA() = Unit
    override fun comment(ch: CharArray?, start: Int, length: Int) = Unit
}

/**
 * [input], failing as [TooLarge] once more than [limit] bytes have been read
 * from it - and stopping when whoever asked for the file has gone, so a
 * viewer closed on a large file isn't still read for nobody.
 */
internal class BoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    /** Bytes read so far. */
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) took(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) took(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) took(it) }

    private fun took(bytes: Long) {
        count += bytes
        if (count > limit) throw TooLarge()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }
}

/** Thrown to stop reading a part once as much as will be shown has been read. */
internal class ReadEnough : SAXException("Read as much as will be shown")

private class LinksHandler(private val folder: String) : OoxmlHandler() {
    val links = HashMap<String, OoxmlLink>()

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (localName != "Relationship" || attributes.attr("TargetMode") == "External") return
        val id = attributes.attr("Id") ?: return
        val target = attributes.attr("Target") ?: return
        if (links.size >= MAX_LINKS) return
        links[id] = OoxmlLink(partPath(folder, target), attributes.attr("Type").orEmpty().substringAfterLast('/'))
    }
}

/** An attribute with no namespace, as most in these parts are. */
internal fun Attributes.attr(name: String): String? = getValue("", name) ?: getValue(name)

/** An XML boolean that is set: "1" or "true". */
internal fun String?.isTrue() = this == "1" || this == "true"

/** An attribute in the relationships namespace: r:id, r:embed. */
internal fun Attributes.relationship(name: String): String? =
    getValue(RELATIONSHIPS, name) ?: getValue(RELATIONSHIPS_STRICT, name)

/**
 * [target], relative to [folder] unless it starts at the top of the zip, as
 * a part name: "../media/a.png" from "ppt/slides" is "ppt/media/a.png".
 */
internal fun partPath(folder: String, target: String): String {
    val path = if (target.startsWith("/")) target.drop(1) else if (folder.isEmpty()) target else "$folder/$target"
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

/**
 * Whether [text] reads right to left, by its first letter that has a
 * direction: Persian, Arabic or Hebrew rather than English. Digits and
 * punctuation have none, so "۱۲ ماه" and "12 ماه" both read right to left.
 */
internal fun startsRightToLeft(text: CharSequence): Boolean {
    for (char in text) {
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}
