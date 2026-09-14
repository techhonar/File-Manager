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

        val uris = files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeOf(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                // A mixed selection has no single type, so fall back to the
                // wildcard rather than claiming one of them.
                type = files.map(::mimeTypeOf).distinct().singleOrNull() ?: "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
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
