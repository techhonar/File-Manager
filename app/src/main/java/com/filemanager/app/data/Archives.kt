package com.filemanager.app.data

/**
 * How the names of archives the core can unpack end.
 *
 * Longest first, so "photos.tar.gz" is taken as a compressed tar rather than
 * as a ".gz" - which matters for the folder it unpacks into. The core goes by
 * what is inside, not by these; they only decide what a tap offers.
 */
private val EXTRACTABLE = listOf(
    ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst",
    ".tgz", ".tbz2", ".tbz", ".txz", ".tzst",
    ".zip", ".7z", ".rar", ".tar",
    ".gz", ".bz2", ".xz", ".zst",
)

private fun extractableEnding(name: String): String? =
    EXTRACTABLE.firstOrNull { name.endsWith(it, ignoreCase = true) }

/** Whether tapping [name] should offer to extract it. */
fun isExtractable(name: String): Boolean = extractableEnding(name) != null

/**
 * [name] without its archive ending, for the folder it unpacks into:
 * "photos.tar.gz" becomes "photos", where cutting at the last dot left
 * "photos.tar".
 */
fun archiveBaseName(name: String): String {
    val ending = extractableEnding(name) ?: return name.substringBeforeLast('.', name)
    return name.dropLast(ending.length).ifEmpty { name }
}
