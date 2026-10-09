package com.filemanager.app.ui.components

import uniffi.filemanager_core.FileEntry

/**
 * One item in the More menu of the selection bar. Items of one [group] sit
 * together, with a line between one group and the next.
 */
class MoreAction(val label: String, val group: Int = 0, val onClick: () -> Unit)

/** What a screen in selection mode is titled: how many are chosen, or what to do. */
fun selectionTitle(count: Int): String = if (count == 0) "Select items" else "$count selected"

/**
 * The More menu a selection offers wherever it is made, in One UI's order:
 * Copy to clipboard, Details, Rename and favourites, then what opens the one
 * file chosen. Each leaves itself out where it does not apply - Rename with
 * two files chosen, Open with for a folder - rather than sitting there greyed.
 *
 * [onShowInFolder] is for lists drawn from all over the phone, where where a
 * file lives is worth a tap; a folder's own list is already that place.
 */
fun selectionMoreActions(
    selected: List<FileEntry>,
    allFavorite: Boolean,
    onCopyToClipboard: () -> Unit,
    onDetails: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
    onFavorite: () -> Unit,
    onOpenWith: (FileEntry) -> Unit,
    onShowInFolder: ((FileEntry) -> Unit)? = null,
): List<MoreAction> {
    if (selected.isEmpty()) return emptyList()
    val single = selected.singleOrNull()
    return buildList {
        // Files only: there is no pasting a folder into another app.
        if (selected.none { it.isDir }) add(MoreAction("Copy to clipboard", onClick = onCopyToClipboard))
        if (single != null) {
            add(MoreAction("Details") { onDetails(single) })
            add(MoreAction("Rename") { onRename(single) })
        }
        // The label says which way it goes, as the action toggles.
        add(MoreAction(if (allFavorite) "Remove from favourites" else "Add to favourites", onClick = onFavorite))
        if (single != null && !single.isDir) add(MoreAction("Open with", group = 1) { onOpenWith(single) })
        if (single != null && onShowInFolder != null) {
            add(MoreAction("Show in folder", group = 1) { onShowInFolder(single) })
        }
    }
}
