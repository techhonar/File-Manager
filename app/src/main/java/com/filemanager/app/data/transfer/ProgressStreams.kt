package com.filemanager.app.data.transfer

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Thrown from inside a transfer to stop it partway.
 *
 * An IOException because that is what every library here lets through from
 * the streams it is handed: thrown from a progress callback, it unwinds the
 * transfer the way a dropped connection would, and whoever asked for the stop
 * knows to read it as one.
 */
class TransferCancelledException : IOException("Cancelled")

/**
 * Reads through [inner], telling [onBytes] how many bytes have passed so far.
 *
 * [onBytes] runs on every read, so anything it does that costs more than a
 * comparison wants pacing; and it may throw, which is how a transfer is
 * stopped mid-file.
 */
open class ProgressInputStream(
    inner: InputStream,
    private val onBytes: (Long) -> Unit,
) : FilterInputStream(inner) {

    private var count = 0L

    override fun read(): Int {
        val byte = super.read()
        if (byte >= 0) advance(1)
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) advance(read.toLong())
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) advance(skipped)
        return skipped
    }

    /** A count cannot be rewound with the stream. */
    override fun markSupported(): Boolean = false

    private fun advance(n: Long) {
        count += n
        onBytes(count)
    }
}

/** Writes through to [inner], telling [onBytes] how many bytes have passed so far. */
open class ProgressOutputStream(
    inner: OutputStream,
    private val onBytes: (Long) -> Unit,
) : FilterOutputStream(inner) {

    private var count = 0L

    override fun write(b: Int) {
        out.write(b)
        advance(1)
    }

    // FilterOutputStream would otherwise hand a buffer on one byte at a time.
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        advance(len.toLong())
    }

    private fun advance(n: Long) {
        count += n
        onBytes(count)
    }
}

/**
 * Lets something through at most once per [intervalMs] - progress, which
 * arrives with every buffer of a transfer, to something that redraws for it.
 */
class Pacer(private val intervalMs: Long) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun due(): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (last != Long.MIN_VALUE && now - last < intervalMs) return false
        last = now
        return true
    }
}
