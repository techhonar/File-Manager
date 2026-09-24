package com.filemanager.app.data.viewer

/** What the app's own viewer can show. */
enum class ViewerKind { TEXT, PDF, DOCX }

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

/**
 * Which viewer opens [fileName], or null for anything it cannot show - that
 * goes to another app, as every file did before. Only .docx for Word: the
 * older .doc is a binary format of its own.
 */
fun viewerKind(fileName: String): ViewerKind? =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> ViewerKind.PDF
        "docx" -> ViewerKind.DOCX
        in TEXT_EXTENSIONS -> ViewerKind.TEXT
        else -> null
    }
