package com.filemanager.app.data.external

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The requests a file manager answers that Android keeps a default for, one
 * each: picking "Always" for a PDF does nothing for folders.
 */
enum class FileRequest(val title: String) {
    DOWNLOADS("Downloads"),
    FOLDERS("Folders"),
    PDF("PDF files"),
    TEXT("Text files"),
    WORD("Word documents"),
}

/** Who answers a request without asking. */
sealed interface Answerer {
    data object ThisApp : Answerer

    /** Nobody: Android lists the apps that can, each time. */
    data object Asks : Answerer

    data class OtherApp(val packageName: String, val label: String) : Answerer
}

/**
 * Finding out who answers each request, and getting the choice changed.
 *
 * Android has no screen for these. The choice lives with whichever app was
 * picked with "Always", and is undone from that app's own settings page.
 */
object DefaultApps {

    fun answerer(context: Context, request: FileRequest): Answerer {
        val pm = context.packageManager
        val intent = intentFor(context, request, sample = null)
        val able = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
        // With no default and more than one app able, this is Android's own
        // list - which is none of them.
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return when {
            resolved == context.packageName -> Answerer.ThisApp
            resolved != null && resolved in able -> Answerer.OtherApp(resolved, labelOf(pm, resolved))
            else -> Answerer.Asks
        }
    }

    /**
     * The request itself, sent to bring up Android's list so File Manager
     * can be picked with "Always". A file request opens a small sample file,
     * written here, since the list only appears for a real one.
     */
    fun tryIntent(context: Context, request: FileRequest): Intent {
        val sample = when (request) {
            FileRequest.DOWNLOADS, FileRequest.FOLDERS -> null
            FileRequest.PDF -> sample(context, "Sample.pdf", ::writePdf)
            FileRequest.TEXT -> sample(context, "Sample.txt") { it.writeText(SAMPLE_TEXT) }
            FileRequest.WORD -> sample(context, "Sample.docx", ::writeDocx)
        }
        return intentFor(context, request, sample).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The settings page of [packageName], where its defaults are cleared. */
    fun appSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /**
     * As other apps send it. Without a [sample] the file requests point at
     * nothing - enough to ask who would answer, not to open.
     */
    private fun intentFor(context: Context, request: FileRequest, sample: File?): Intent {
        fun view(type: String): Intent {
            val uri = if (sample != null) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sample)
            } else {
                Uri.parse("content://${context.packageName}.fileprovider/sample")
            }
            return Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        }
        return when (request) {
            FileRequest.DOWNLOADS -> Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            FileRequest.FOLDERS -> Intent(Intent.ACTION_VIEW).setType("resource/folder")
            FileRequest.PDF -> view("application/pdf")
            FileRequest.TEXT -> view("text/plain")
            FileRequest.WORD -> view(DOCX_TYPE)
        }
    }

    private fun labelOf(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun sample(context: Context, name: String, write: (File) -> Unit): File {
        val file = File(File(context.cacheDir, "samples").apply { mkdirs() }, name)
        if (!file.exists()) write(file)
        return file
    }

    private fun writePdf(file: File) {
        val document = android.graphics.pdf.PdfDocument()
        try {
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = 16f
            }
            page.canvas.drawText(SAMPLE_TEXT, 56f, 96f, paint)
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    /** The least a .docx can be and still open in Word. */
    private fun writeDocx(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            val header = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            put(
                "[Content_Types].xml",
                header + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                    """</Types>""",
            )
            put(
                "_rels/.rels",
                header + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                    """</Relationships>""",
            )
            put(
                "word/document.xml",
                header + """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                    """<w:body><w:p><w:r><w:t>$SAMPLE_TEXT</w:t></w:r></w:p></w:body></w:document>""",
            )
        }
    }

    private const val SAMPLE_TEXT = "A sample file, opened to choose which app opens files like it."
}
