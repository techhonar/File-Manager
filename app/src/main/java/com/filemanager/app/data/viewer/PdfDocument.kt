package com.filemanager.app.data.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A PDF open for showing, page by page.
 *
 * Android's PdfRenderer holds one page open at a time and is not safe to use
 * from two threads at once, so every use goes through one lock.
 */
class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Each page's width over its height, known before any page is drawn. */
    val pageRatios: List<Float>,
) {
    private val lock = Mutex()

    @Volatile
    private var closed = false
    private var released = false

    val pageCount: Int get() = pageRatios.size

    /** Page [index] drawn [width] pixels wide, or null once the document is closed. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            val bitmap = if (closed) null else draw(index, width)
            // Closed while this page was drawing: close() left it to us.
            if (closed) release()
            bitmap
        }
    }

    /** Frees the file. A page being drawn finishes first. */
    fun close() {
        closed = true
        if (lock.tryLock()) {
            try {
                release()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun draw(index: Int, width: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt()
            return Bitmap.createBitmap(width, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
                // A page is drawn over whatever is there, and paper is white.
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    private fun release() {
        if (released) return
        released = true
        renderer.close()
        descriptor.close()
    }

    companion object {
        /**
         * Opens [file], reading every page's shape first so that pages hold
         * their place while they draw rather than jumping as each arrives.
         *
         * Throws SecurityException for a password-protected file and
         * IOException for one that is damaged or not a PDF.
         */
        fun open(file: File): PdfDocument {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(descriptor)
                try {
                    val ratios = List(renderer.pageCount) { index ->
                        val page = renderer.openPage(index)
                        try {
                            page.width.toFloat() / page.height.coerceAtLeast(1)
                        } finally {
                            page.close()
                        }
                    }
                    return PdfDocument(descriptor, renderer, ratios)
                } catch (e: Exception) {
                    renderer.close()
                    throw e
                }
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}
