package com.filemanager.app.data.viewer

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** A text file read for showing: its lines, and whether it was cut short. */
class TextContent(val lines: List<String>, val truncated: Boolean)

/** How a text file's characters are stored, byte-order mark included. */
enum class TextEncoding(val charset: Charset, private val mark: IntArray) {
    UTF8(Charsets.UTF_8, intArrayOf()),
    UTF8_BOM(Charsets.UTF_8, intArrayOf(0xEF, 0xBB, 0xBF)),
    UTF16LE(Charsets.UTF_16LE, intArrayOf(0xFF, 0xFE)),
    UTF16BE(Charsets.UTF_16BE, intArrayOf(0xFE, 0xFF)),
    ;

    val bom: ByteArray get() = ByteArray(mark.size) { mark[it].toByte() }

    companion object {
        /**
         * UTF-8 unless the file says otherwise with a byte-order mark. Windows
         * Notepad's "Unicode" is UTF-16 with one, and read as UTF-8 it comes
         * out as every other character missing.
         */
        fun of(bytes: ByteArray): TextEncoding =
            listOf(UTF8_BOM, UTF16LE, UTF16BE).firstOrNull { encoding ->
                bytes.size >= encoding.mark.size && encoding.mark.indices.all { bytes[it] == encoding.mark[it].toByte() }
            } ?: UTF8
    }
}

/** What opening a text file to edit found. */
sealed interface Editing {
    /**
     * Ready, with line breaks as "\n" whatever the file uses; [crlf] says it
     * used Windows ones, which saving puts back.
     */
    data class Ready(val text: String, val encoding: TextEncoding, val crlf: Boolean) : Editing

    data object TooLarge : Editing

    /**
     * Not valid text in its own encoding - another encoding, or not text at
     * all. Saving would write back what could be read, and lose the rest.
     */
    data object NotText : Editing
}

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

    /** [bytes] as text, in the encoding they say they are in. */
    fun decode(bytes: ByteArray): String {
        val encoding = TextEncoding.of(bytes)
        val skip = encoding.bom.size
        return String(bytes, skip, bytes.size - skip, encoding.charset)
    }

    /**
     * Files larger than this are only viewed. Every keystroke lays the whole
     * text out again, and past about half a megabyte typing starts to lag.
     */
    const val EDIT_LIMIT = 512 * 1024

    fun openForEditing(file: File, limit: Int = EDIT_LIMIT): Editing {
        if (file.length() > limit) return Editing.TooLarge
        val bytes = file.readBytes()
        val encoding = TextEncoding.of(bytes)
        val skip = encoding.bom.size
        // Strictly, unlike for viewing: a character that can't be read would
        // be saved as a replacement mark, and the original lost for good.
        val text = try {
            encoding.charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip))
                .toString()
        } catch (_: CharacterCodingException) {
            return Editing.NotText
        }
        val crlf = "\r\n" in text
        return Editing.Ready(if (crlf) text.replace("\r\n", "\n") else text, encoding, crlf)
    }

    /** [text] as the bytes of a file in [encoding], with Windows line breaks if [crlf]. */
    fun encode(text: String, encoding: TextEncoding, crlf: Boolean): ByteArray {
        val unix = text.replace("\r\n", "\n")
        val body = (if (crlf) unix.replace("\n", "\r\n") else unix).toByteArray(encoding.charset)
        return encoding.bom + body
    }

    /**
     * Writes [text] over [file]. Into a copy beside it first, then swapped
     * in, so a save that fails part-way - a full disk - leaves the file as
     * it was rather than cut short.
     */
    fun save(file: File, text: String, encoding: TextEncoding, crlf: Boolean) {
        val bytes = encode(text, encoding, crlf)
        val copy = File(file.parentFile, ".${file.name}.saving")
        try {
            copy.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!copy.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
        } finally {
            copy.delete()
        }
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
}
