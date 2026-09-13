package com.filemanager.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Paths waiting to be pasted, and whether the originals should be removed. */
data class ClipboardContents(val paths: List<String>, val isMove: Boolean)

/**
 * The cut/copy clipboard, scoped to the whole app.
 *
 * It has to live above any one screen. The browser creates a ViewModel per
 * folder, so a clipboard owned by that ViewModel is discarded the moment the
 * user navigates - which makes copying in one folder and pasting in another,
 * the entire point of a clipboard, impossible. Holding it here also lets the
 * search results copy files and the browser paste them.
 */
class FileClipboard {

    private val _contents = MutableStateFlow<ClipboardContents?>(null)
    val contents: StateFlow<ClipboardContents?> = _contents.asStateFlow()

    fun copy(paths: List<String>) {
        if (paths.isNotEmpty()) _contents.value = ClipboardContents(paths, isMove = false)
    }

    fun cut(paths: List<String>) {
        if (paths.isNotEmpty()) _contents.value = ClipboardContents(paths, isMove = true)
    }

    fun clear() {
        _contents.value = null
    }
}
