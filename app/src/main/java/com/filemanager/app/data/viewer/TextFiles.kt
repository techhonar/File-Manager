package com.filemanager.app.data.viewer

import java.io.File

/** A text file read for showing: its lines, and whether it was cut short. */
class TextContent(val lines: List<String>, val truncated: Boolean)

/** Reading text files for the viewer. */
object TextFiles {

    /**
     * Read at most this much. Past a few megabytes a text file is a log or a
     * dump, which nobody reads in full on a phone, and holding all of it
     * would only risk running out of memory.
     */
    const val READ_LIMIT = 4 * 1024 * 1024

    /**
     * Longer lines are shown in pieces of this length. A minified file can be
     * one line of megabytes, and laying that out as a single piece of text
     * stalls the screen.
     */
    const val LINE_PIECE = 2_000

    fun read(file: File, limit: Int = READ_LIMIT): TextContent {
        val size = file.length()
        val buffer = ByteArray(minOf(size, limit.toLong()).toInt())
        var read = 0
        file.inputStream().use { input ->
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
        }
        val bytes = if (read == buffer.size) buffer else buffer.copyOf(read)
        return TextContent(lines(decode(bytes)), truncated = size > limit)
    }

    /**
     * UTF-8 unless the file says otherwise with a byte-order mark. Windows
     * Notepad's "Unicode" is UTF-16 with one, and read as UTF-8 it comes out
     * as every other character missing.
     */
    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }

    /** [text] as lines, any Windows line endings dropped and long lines in pieces. */
    fun lines(text: String, piece: Int = LINE_PIECE): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split('\n')
        // A file that ends with a line break has no line after it.
        val count = if (raw.last().isEmpty()) raw.size - 1 else raw.size
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val line = raw[i].removeSuffix("\r")
            if (line.length <= piece) out += line else out += line.chunked(piece)
        }
        return out
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
