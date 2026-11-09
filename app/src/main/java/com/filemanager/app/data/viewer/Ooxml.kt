package com.filemanager.app.data.viewer

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
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

    /** Parse [part] with [handler]; false if the file has no such part. */
    fun parse(part: String, handler: DefaultHandler): Boolean {
        val entry = zip.getEntry(part) ?: return false
        zip.getInputStream(entry).use { input ->
            SAXParserFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newSAXParser()
                .parse(input, handler)
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

/** Thrown to stop reading a part once as much as will be shown has been read. */
internal class ReadEnough : SAXException("Read as much as will be shown")

private class LinksHandler(private val folder: String) : OoxmlHandler() {
    val links = HashMap<String, OoxmlLink>()

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        if (localName != "Relationship" || attributes.attr("TargetMode") == "External") return
        val id = attributes.attr("Id") ?: return
        val target = attributes.attr("Target") ?: return
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
