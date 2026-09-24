package com.filemanager.app.data.external

import com.filemanager.app.data.viewer.ViewerKind
import com.filemanager.app.data.viewer.viewerKind

/*
 * The rules behind Incoming, kept apart from Android so they can be tested
 * on their own.
 */

const val DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** The text types the manifest offers to show; the same list, for recognising them. */
private val TEXT_TYPES = setOf(
    "text/plain", "text/markdown", "text/x-markdown", "text/csv", "text/comma-separated-values",
    "text/tab-separated-values", "text/xml", "application/xml", "application/json",
)

/**
 * Which viewer shows a file another app sent, from its name if that says,
 * else from its type. A name often doesn't: many apps send a bare number.
 */
internal fun viewerKindFor(mime: String?, name: String?): ViewerKind? {
    name?.let(::viewerKind)?.let { return it }
    val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return when (type) {
        "application/pdf" -> ViewerKind.PDF
        DOCX_TYPE -> ViewerKind.DOCX
        in TEXT_TYPES -> ViewerKind.TEXT
        else -> null
    }
}

/**
 * The types a picking app will take, lowercased; empty when it takes
 * anything. A list in EXTRA_MIME_TYPES is the real answer: the intent's own
 * type is then usually just the catch-all, to cover it.
 */
internal fun requestedTypes(type: String?, extra: Array<String>?): List<String> {
    val listed = extra?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
    val types = listed.ifEmpty { listOfNotNull(type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) }
    return if ("*/*" in types) emptyList() else types.distinct()
}

/** Whether a file of type [mime] is one of [types], counting wildcards such as any image. */
internal fun accepts(types: List<String>, mime: String?): Boolean {
    if (types.isEmpty()) return true
    val actual = mime?.lowercase() ?: return false
    return types.any { wanted ->
        wanted == actual || (wanted.endsWith("/*") && actual.startsWith(wanted.dropLast(1)))
    }
}

/**
 * The folder a storage document URI's path names: ".../document/primary:DCIM"
 * is DCIM on internal storage, "1234-ABCD:Music" Music on that SD card.
 */
internal fun documentFolder(segments: List<String>, primary: String): String? {
    val id = listOf("document", "tree", "root").firstNotNullOfOrNull { key ->
        segments.indexOf(key).takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
    } ?: return null
    val volume = id.substringBefore(':')
    val relative = id.substringAfter(':', "").trim('/')
    val base = when (volume) {
        "" -> return null
        "primary" -> primary
        // The Files app's own shortcut to Documents.
        "home" -> "$primary/Documents"
        else -> "/storage/$volume"
    }
    return if (relative.isEmpty()) base else "$base/$relative"
}

/** A name safe to save a copy under: no folders in it, and not too long. */
internal fun safeFileName(name: String?): String {
    val cleaned = name.orEmpty().replace(Regex("[/\\\\\u0000]"), "_").trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "file"
    if (cleaned.length <= 120) return cleaned
    val extension = cleaned.substringAfterLast('.', "").take(10)
    return if (extension.isEmpty()) cleaned.take(120) else cleaned.take(119 - extension.length) + "." + extension
}
