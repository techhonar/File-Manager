package com.filemanager.app.ui.components

/**
 * The kinds of document a file can be, each drawn in a colour of its own.
 * Every document used to take the Documents colour and the same page glyph,
 * so a PDF and a Word file looked alike.
 */
enum class DocumentKind { PDF, WORD, SHEET, SLIDES, TEXT, WEB, EBOOK }

/** A document's kind, and the label its icon carries: its extension, "PDF" or "DOCX". */
data class DocumentType(val kind: DocumentKind, val label: String)

/**
 * What kind of document [fileName] is, by its extension: the extensions the
 * Rust core counts as documents (DOCUMENT in categories.rs). Null for any
 * other file.
 */
fun documentType(fileName: String): DocumentType? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val kind = when (extension) {
        "pdf" -> DocumentKind.PDF
        "doc", "docx", "odt", "rtf" -> DocumentKind.WORD
        "xls", "xlsx", "ods", "csv" -> DocumentKind.SHEET
        "ppt", "pptx", "odp" -> DocumentKind.SLIDES
        "txt", "md" -> DocumentKind.TEXT
        "html", "htm", "json", "xml" -> DocumentKind.WEB
        "epub" -> DocumentKind.EBOOK
        else -> return null
    }
    return DocumentType(kind, extension.uppercase())
}

/**
 * How large a label's letters are against the space they sit in: the
 * shorter the label, the larger, so "MD" and "DOCX" both fill it.
 */
fun documentLabelScale(label: String): Float = when (label.length) {
    in 0..2 -> 0.34f
    3 -> 0.27f
    else -> 0.22f
}
