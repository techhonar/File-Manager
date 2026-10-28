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
        Direction.COPY, Direction.MOVE -> placingTitle(single)
    }
}

/**
 * "Copying file…", "Moving 12 files…": a copy or move on the phone, or one
 * to a server, as One UI words it - by what was asked for, not how it is
 * done. A move within a volume is a rename and never shows long enough to
 * read; one to a card copies first, and still reads as moving.
 */
fun placingTitle(transfer: Transfer): String {
    val verb = if (transfer.isMove) "Moving" else "Copying"
    val what = if (transfer.fileCount == 1) "file" else "${transfer.fileCount} files"
    return when (transfer.direction) {
        Direction.UPLOAD -> "$verb $what to ${transfer.serverLabel}…"
        else -> "$verb $what…"
    }
}

/**
 * What to say once a copy or a move on the phone is over. [items] were asked
 * for, [placed] of them arrived; [failure] says why the rest did not.
 */
fun placeSummary(move: Boolean, items: Int, placed: Int, stopped: Boolean, failure: String?): String {
    val done = if (move) "moved" else "copied"
    return when {
        stopped && placed == 0 -> "File transfer cancelled. ${if (items == 1) "File" else "Files"} not $done."
        stopped -> "File transfer cancelled. $placed of $items $done."
        failure != null && placed == 0 -> "Could not ${if (move) "move" else "copy"}: $failure"
        failure != null -> "$placed of $items $done. Could not ${if (move) "move" else "copy"} the rest: $failure"
        move -> "Move completed."
        else -> "Copy completed."
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
