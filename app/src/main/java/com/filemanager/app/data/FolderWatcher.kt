package com.filemanager.app.data

import android.os.FileObserver
import java.io.File

/**
 * Watches one folder and reports when its contents change.
 *
 * Only the folder currently on screen. A recursive watch over all of storage
 * would mean an inotify watch per directory - thousands of them - and Android
 * caps how many a process may hold, so it would fail on exactly the devices
 * with the most files.
 *
 * Events arrive individually and in bursts: copying one file emits CREATE then
 * several MODIFY then CLOSE_WRITE. The caller is told once per burst rather
 * than once per event, which is why [onChanged] is expected to debounce.
 */
class FolderWatcher(
    private val path: String,
    private val onChanged: () -> Unit,
) {

    private val observer = object : FileObserver(
        File(path),
        CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF,
    ) {
        override fun onEvent(event: Int, childPath: String?) {
            // Reported either way. A null path is the watched folder itself -
            // deleted, moved away, or the watch dropped - and that used to be
            // ignored as having nothing to say about a child, which left the
            // screen listing a folder that no longer existed. It is exactly
            // the case where reloading matters: the reload finds it gone and
            // says so.
            onChanged()
        }
    }

    fun start() {
        runCatching { observer.startWatching() }
    }

    fun stop() {
        runCatching { observer.stopWatching() }
    }
}
