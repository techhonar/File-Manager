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
        CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE,
    ) {
        override fun onEvent(event: Int, childPath: String?) {
            // A null path means the watch itself is in trouble - overflow, or
            // the folder went away. Nothing to report about a child then.
            if (childPath == null) return
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
