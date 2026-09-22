package com.filemanager.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hand files to another app.
 *
 * Everything goes through FileProvider: Android has refused file:// URIs
 * across app boundaries since Nougat, and passing a raw path throws
 * FileUriExposedException.
 */
object ShareFiles {

    /** Beyond this, the intent risks exceeding the Binder limit. */
    const val MAX_FILES = 300

    /**
     * Result of a share attempt, so the caller can say why nothing happened.
     */
    sealed interface Result {
        data object Sent : Result
        data object NoFiles : Result
        data class TooMany(val count: Int) : Result
        data object NoApp : Result
    }

    fun share(context: Context, paths: List<String>): Result {
        val files = paths.map(::File).filter { it.isFile }
        if (files.isEmpty()) return Result.NoFiles

        // Every URI travels through a Binder transaction, which is capped at
        // about 1 MB for the whole intent. A few thousand of them silently
        // throws TransactionTooLargeException, so refuse with a count the user
        // can act on rather than failing obscurely.
        if (files.size > MAX_FILES) return Result.TooMany(files.size)

        // Guarded for the same reason as opening: FileProvider throws for a
        // file outside its roots, and uncaught that closes the app. A file it
        // cannot share is left out rather than failing the rest.
        // Kept in pairs, so the type is always worked out from a file that is
        // actually being sent.
        val shared = files.mapNotNull { file ->
            runCatching {
                file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
        if (shared.isEmpty()) return Result.NoFiles

        val intent = if (shared.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeOf(shared.first().first)
                putExtra(Intent.EXTRA_STREAM, shared.first().second)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                // A mixed selection has no single type, so fall back to the
                // wildcard rather than claiming one of them.
                type = shared.map { mimeTypeOf(it.first) }.distinct().singleOrNull() ?: "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList<Uri>(shared.map { it.second }),
                )
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return try {
            context.startActivity(
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result.Sent
        } catch (_: ActivityNotFoundException) {
            Result.NoApp
        }
    }

    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
}
