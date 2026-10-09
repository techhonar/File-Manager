package com.filemanager.app.ui.screens

import uniffi.filemanager_core.TrashItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The heading over the items with [days] left, as One UI groups its trash. */
internal fun deletionHeading(days: Int): String = when {
    days <= 0 -> "Deleting today"
    days == 1 -> "1 day until deletion"
    else -> "$days days until deletion"
}

/** The line that closes one item's details: when it goes for good. */
internal fun deletionNote(item: TrashItem): String {
    val what = if (item.isDir) "folder" else "file"
    return when {
        item.daysRemaining <= 0 -> "This $what will be deleted today."
        item.daysRemaining == 1 -> "This $what will be deleted in 1 day."
        else -> "This $what will be deleted in ${item.daysRemaining} days."
    }
}

/** "1 item", "3 items". */
internal fun countOf(count: Long, noun: String): String = if (count == 1L) "1 $noun" else "$count ${noun}s"

/** "2 files", "1 folder, 3 files": what [files] and [folders] come to. */
internal fun containsText(files: Long, folders: Long): String = when {
    files == 0L && folders == 0L -> "Nothing"
    folders == 0L -> countOf(files, "file")
    files == 0L -> countOf(folders, "folder")
    else -> "${countOf(folders, "folder")}, ${countOf(files, "file")}"
}

/**
 * When the [times] run from and to, "9 Oct 2026, 11:07 - 9 Oct 2026, 13:39",
 * or the one time when they are all the same.
 */
internal fun modifiedRange(times: List<Long>, locale: Locale = Locale.getDefault()): String {
    if (times.isEmpty()) return ""
    val first = shownTime(times.min(), locale)
    val last = shownTime(times.max(), locale)
    return if (first == last) first else "$first - $last"
}

internal fun shownTime(ms: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(ms))
