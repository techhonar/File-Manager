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

    fun share(context: Context, paths: List<String>): Boolean {
        // Folders have no stream to send, and a share sheet offering one that
        // every target would reject is worse than not offering it.
        val files = paths.map(::File).filter { it.isFile }
        if (files.isEmpty()) return false

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
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"
}
