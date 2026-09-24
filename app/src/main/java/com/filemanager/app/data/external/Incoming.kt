package com.filemanager.app.data.external

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.filemanager.app.data.StorageVolumes
import com.filemanager.app.data.viewer.ViewerKind
import java.io.File
import java.io.IOException

/**
 * What another app, or Android itself, asked of this one in starting it.
 *
 * Android has no setting for a default file manager. What makes an app one is
 * answering the requests file managers answer - the manifest lists them - and
 * being picked, with "Always", when the phone asks which app should.
 */
sealed interface Incoming {

    /** Show this folder. */
    data class Folder(val path: String) : Incoming

    /** Show another app's file in the viewer. */
    data class View(val uri: Uri, val kind: ViewerKind) : Incoming

    /**
     * Let the user choose a file and send it back. [types] are what the app
     * asked for, such as any image type; empty for anything at all.
     */
    data class Pick(val types: List<String>) : Incoming

    companion object {
        /** What [intent] asks for, or null for an ordinary start. */
        fun of(intent: Intent, context: Context): Incoming? = when (intent.action) {
            // The Downloads notification, and "Downloads" in some launchers.
            DownloadManager.ACTION_VIEW_DOWNLOADS -> Folder(StorageVolumes.downloadsPath())
            Intent.ACTION_GET_CONTENT -> Pick(
                requestedTypes(intent.type, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)),
            )
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                val type = intent.type
                    ?: uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
                val primary = StorageVolumes.primaryPath()
                when {
                    type != null && type.lowercase() in FOLDER_TYPES ->
                        Folder(uri?.let { folderOf(it, primary) } ?: primary)
                    uri == null -> null
                    else -> viewerKindFor(type, uri.lastPathSegment)?.let { View(uri, it) }
                }
            }
            else -> null
        }
    }
}

/** The ways apps say "a folder" when asking for one to be opened. */
private val FOLDER_TYPES = setOf(
    "resource/folder",
    "inode/directory",
    "x-directory/normal",
    "vnd.android.document/directory",
    "vnd.android.document/root",
)

private fun folderOf(uri: Uri, primary: String): String? = when (uri.scheme) {
    "file" -> uri.path
    "content" -> when (uri.authority) {
        "com.android.externalstorage.documents" -> documentFolder(uri.pathSegments, primary)
        // The Downloads provider names a folder by an id, or by its path
        // after "raw:".
        "com.android.providers.downloads.documents" ->
            uri.lastPathSegment?.takeIf { it.startsWith("raw:") }?.removePrefix("raw:") ?: "$primary/Download"
        else -> null
    }
    else -> null
}

/** The MIME type Android gives [fileName]'s extension, if any. */
fun mimeTypeOf(fileName: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())

/**
 * Copies another app's file into the cache for the viewer, which reads
 * files, not streams. The app lends it only while this one is open, and some
 * senders delete it soon after, so it is copied at once.
 *
 * Each copy gets a folder of its own, so a second file opened in another
 * window cannot replace one still being read; copies over a day old go.
 */
fun copyForViewing(context: Context, uri: Uri): File {
    val name = safeFileName(displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/'))
    val root = File(context.cacheDir, "opened")
    val now = System.currentTimeMillis()
    root.listFiles()?.filter { now - it.lastModified() > DAY_MS }?.forEach { it.deleteRecursively() }
    val target = File(File(root, now.toString() + "-" + System.nanoTime()).apply { mkdirs() }, name)
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Nothing to read")
    input.use { from -> target.outputStream().use { from.copyTo(it) } }
    return target
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()
