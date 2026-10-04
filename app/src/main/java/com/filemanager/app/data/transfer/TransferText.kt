package com.filemanager.app.data.transfer

/*
 * What the transfer notification and the remote browser say about transfers
 * under way. Kept apart from the Android code that shows it, so the wording
 * can be checked without a phone.
 */

/** A title for [active] transfers: what is going where. */
fun transfersTitle(active: List<Transfer>): String {
    val single = active.singleOrNull()
        ?: return if (active.isEmpty()) "Transfers" else "${active.size} transfers"
    val what = if (single.fileCount == 1) single.current ?: "1 file" else "${single.fileCount} files"
    return when (single.direction) {
        Direction.DOWNLOAD -> "Downloading $what from ${single.serverLabel}"
        Direction.UPLOAD -> "Uploading $what to ${single.serverLabel}"
    }
}

/**
 * How much of [active] has gone - and which file of how many, for one batch
 * of several. [size] formats a byte count.
 */
fun transfersText(active: List<Transfer>, size: (Long) -> String): String {
    val done = active.sumOf { it.bytesDone }
    val total = active.sumOf { it.bytesTotal }
    val amount = if (total > 0) "${size(done)} of ${size(total)}" else size(done)
    val single = active.singleOrNull()
    return if (single != null && single.fileCount > 1) {
        "$amount · ${(single.filesDone + 1).coerceAtMost(single.fileCount)} of ${single.fileCount}"
    } else {
        amount
    }
}

/** How far through [active] are together, or null when their sizes are not known. */
fun transfersFraction(active: List<Transfer>): Float? {
    val total = active.sumOf { it.bytesTotal }
    if (total <= 0) return null
    return (active.sumOf { it.bytesDone }.toDouble() / total).toFloat().coerceIn(0f, 1f)
}
