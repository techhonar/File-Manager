package com.filemanager.app.data.viewer

/** What the app's own viewer can show. */
enum class ViewerKind { TEXT, PDF, DOCX, DOC, XLSX, PPTX }

/**
 * Plain text, by extension: notes, logs, data and source code. They are all
 * characters and line breaks whatever program wrote them, so one viewer reads
 * them all. Web pages are left out on purpose - someone tapping one expects
 * to see the page, not its markup.
 */
private val TEXT_EXTENSIONS = setOf(
    "txt", "text", "md", "markdown", "log", "csv", "tsv",
    "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env",
    "css", "js", "mjs", "ts", "kt", "kts", "java", "gradle", "py", "rb", "rs", "go",
    "c", "h", "cc", "cpp", "hpp", "cs", "swift", "php", "sh", "bash", "bat", "ps1", "sql",
    "srt", "vtt", "tex", "gitignore",
)

/** Excel workbooks and PowerPoint presentations, with macros or not, and their templates: one format each. */
private val XLSX_EXTENSIONS = setOf("xlsx", "xlsm", "xltx", "xltm")
private val PPTX_EXTENSIONS = setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm")

/**
 * Which viewer opens [fileName], or null for anything it cannot show - that
 * goes to another app, as every file did before. Word's older .doc and .dot
 * too, and RTF; but not the older binary .xls and .ppt.
 */
fun viewerKind(fileName: String): ViewerKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> ViewerKind.PDF
        "docx" -> ViewerKind.DOCX
        "doc", "dot", "rtf" -> ViewerKind.DOC
        in XLSX_EXTENSIONS -> ViewerKind.XLSX
        in PPTX_EXTENSIONS -> ViewerKind.PPTX
        in TEXT_EXTENSIONS -> ViewerKind.TEXT
        else -> null
    }
