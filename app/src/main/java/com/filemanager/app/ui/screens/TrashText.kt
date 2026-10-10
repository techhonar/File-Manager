package com.filemanager.app.ui.screens

import uniffi.filemanager_core.TrashItem

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
