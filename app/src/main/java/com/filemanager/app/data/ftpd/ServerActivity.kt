package com.filemanager.app.data.ftpd

import com.filemanager.app.data.transfer.Pacer
import com.filemanager.app.data.transfer.ProgressInputStream
import com.filemanager.app.data.transfer.ProgressOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** A file going to or from a computer through the built-in server. */
data class ServerTransfer(
    val id: Int,
    val name: String,
    /** True when the computer is sending it here. */
    val receiving: Boolean,
    val bytes: Long,
    /** Unknown (-1) for a file being received: FTP does not send a file's
     *  size ahead of it. */
    val total: Long,
)

/**
 * One line about [transfers] for the server's notification, none of them
 * finished. [size] formats a byte count.
 */
fun describeServerTransfers(transfers: List<ServerTransfer>, size: (Long) -> String): String {
    val single = transfers.singleOrNull()
    if (single != null) {
        val verb = if (single.receiving) "Receiving" else "Sending"
        val amount = if (single.total > 0) {
            "${size(single.bytes)} of ${size(single.total)}"
        } else {
            size(single.bytes)
        }
        return "$verb ${single.name} · $amount"
    }
    val receiving = transfers.count { it.receiving }
    val sending = transfers.size - receiving
    val parts = buildList {
        if (receiving > 0) add("receiving ${files(receiving)}")
        if (sending > 0) add("sending ${files(sending)}")
    }
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() } +
        " · " + size(transfers.sumOf { it.bytes })
}

/**
 * How far through [transfers] are together, or null when that cannot be said:
 * a file being received has no known size.
 */
fun serverTransferFraction(transfers: List<ServerTransfer>): Float? {
    if (transfers.isEmpty() || transfers.any { it.total <= 0 }) return null
    val done = transfers.sumOf { it.bytes }.toDouble()
    return (done / transfers.sumOf { it.total }).toFloat().coerceIn(0f, 1f)
}

private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

/**
 * What the built-in server is transferring right now, for its notification.
 *
 * The server library reports nothing of the kind, but every byte it sends or
 * receives passes through a stream it asks the file system for - so the file
 * system is wrapped, and the streams count.
 */
class ServerActivity(private val progressIntervalMs: Long = 500) {

    private val _transfers = MutableStateFlow<List<ServerTransfer>>(emptyList())
    val transfers: StateFlow<List<ServerTransfer>> = _transfers.asStateFlow()

    private val nextId = AtomicInteger()

    /** [factory], with every stream it hands out counted here. */
    fun watch(factory: FileSystemFactory): FileSystemFactory =
        FileSystemFactory { user: User -> CountingView(factory.createFileSystemView(user)) }

    internal fun sending(name: String, total: Long, stream: InputStream): InputStream {
        val id = begin(name, receiving = false, total = total)
        val pacer = Pacer(progressIntervalMs)
        return object : ProgressInputStream(stream, { bytes -> if (pacer.due()) progress(id, bytes) }) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    end(id)
                }
            }
        }
    }

    internal fun receiving(name: String, stream: OutputStream): OutputStream {
        val id = begin(name, receiving = true, total = -1)
        val pacer = Pacer(progressIntervalMs)
        return object : ProgressOutputStream(stream, { bytes -> if (pacer.due()) progress(id, bytes) }) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    end(id)
                }
            }
        }
    }

    private fun begin(name: String, receiving: Boolean, total: Long): Int {
        val id = nextId.incrementAndGet()
        _transfers.update { it + ServerTransfer(id, name, receiving, bytes = 0, total = total) }
        return id
    }

    private fun progress(id: Int, bytes: Long) {
        _transfers.update { list -> list.map { if (it.id == id) it.copy(bytes = bytes) else it } }
    }

    private fun end(id: Int) {
        _transfers.update { list -> list.filterNot { it.id == id } }
    }

    private inner class CountingView(private val inner: FileSystemView) : FileSystemView by inner {
        override fun getHomeDirectory(): FtpFile = CountingFile(inner.homeDirectory)
        override fun getWorkingDirectory(): FtpFile = CountingFile(inner.workingDirectory)
        override fun getFile(file: String): FtpFile = CountingFile(inner.getFile(file))
    }

    /** One of the server's files, with its streams counted. */
    private inner class CountingFile(val inner: FtpFile) : FtpFile by inner {

        override fun createInputStream(offset: Long): InputStream =
            sending(inner.name, (inner.size - offset).coerceAtLeast(0), inner.createInputStream(offset))

        override fun createOutputStream(offset: Long): OutputStream =
            receiving(inner.name, inner.createOutputStream(offset))

        override fun listFiles(): List<FtpFile>? = inner.listFiles()?.map { CountingFile(it) }

        // The library's own files expect one of their own here: a rename casts
        // its destination, and would throw on this wrapper.
        override fun move(destination: FtpFile): Boolean =
            inner.move((destination as? CountingFile)?.inner ?: destination)

        override fun equals(other: Any?): Boolean =
            inner == ((other as? CountingFile)?.inner ?: other)

        override fun hashCode(): Int = inner.hashCode()
    }
}
