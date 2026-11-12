package com.filemanager.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The folder the browser on screen is showing.
 *
 * A browser goes from folder to folder without leaving its screen, so the
 * folder that screen was opened at is only where it began. Whether files
 * were copied "here" - into the folder already in view, where there is
 * nothing to be shown the way to - is judged by where the browser is now.
 *
 * Each browser screen says where it is as it gets there, and that it has
 * gone when it goes. One screen comes up before the one under it has gone,
 * so a screen only takes back what it said itself.
 */
class ShownFolder {
    private var owner: Any? = null
    private val shown = MutableStateFlow<String?>(null)

    /** The folder on screen, or null when what is on screen is not a browser. */
    val path: StateFlow<String?> = shown.asStateFlow()

    /** [owner]'s screen is showing [path]. */
    fun show(owner: Any, path: String) {
        this.owner = owner
        shown.value = path
    }

    /** [owner]'s screen has gone, or is about to say where it is now. */
    fun leave(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        shown.value = null
    }
}
