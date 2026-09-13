package com.filemanager.app

import android.app.Application
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the single [FileRepository] for the process.
 *
 * A dependency-injection framework would be the usual answer here; for an app
 * this size one Application-scoped object is less machinery for the same
 * result. Swap in Hilt if the graph ever grows.
 */
class FileManagerApp : Application() {

    val repository: FileRepository by lazy { FileRepository(this) }

    /** Shared by every screen: see FileClipboard for why it cannot live in a
     *  per-folder ViewModel. */
    val clipboard: FileClipboard by lazy { FileClipboard() }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Clear out trash older than the retention window. Fire-and-forget:
        // nothing in the UI waits on it, and a failure is not worth surfacing.
        appScope.launch {
            runCatching { repository.purgeExpiredTrash() }
        }
    }
}
