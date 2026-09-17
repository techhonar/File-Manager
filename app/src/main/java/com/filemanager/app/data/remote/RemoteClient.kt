package com.filemanager.app.data.remote

import java.io.Closeable
import java.io.File

/**
 * One entry in a remote directory.
 *
 * Deliberately not the core's FileEntry. That one carries a category worked
 * out by reading the file and a hidden flag with a local meaning, and it is
 * produced by Rust from a real filesystem - none of which applies to a name
 * and a size read off a socket.
 */
data class RemoteEntry(
    val name: String,
    /** Absolute path on the server, always with forward slashes. */
    val path: String,
    val size: Long,
    val isDir: Boolean,
    val modifiedMs: Long,
)

/**
 * What every protocol here has to be able to do.
 *
 * Every call blocks - these are sockets, and pretending otherwise with
 * suspending signatures would only hide where the waiting happens. The
 * repository wraps them on the IO dispatcher.
 *
 * Transfers take a local [File] rather than a stream on purpose: two of the
 * four libraries offer a file-to-file transfer that handles its own buffering
 * and resumption, and every caller here is moving between the device and the
 * server anyway.
 */
interface RemoteClient : Closeable {

    /** Directory contents. Not recursive, and never includes "." or "..". */
    fun list(path: String): List<RemoteEntry>

    /** Copy a remote file to [to], which is created or overwritten. */
    fun download(path: String, to: File)

    /** Copy a local file to [path], overwriting whatever is there. */
    fun upload(from: File, path: String)

    /** Remove one entry. [isDir] because most protocols have separate calls. */
    fun delete(path: String, isDir: Boolean)

    fun makeDirectory(path: String)

    /** Both paths absolute. Used for renaming and for moving within a server. */
    fun rename(from: String, to: String)

    /**
     * Whether the connection is still usable.
     *
     * Checked before a pooled client is handed out: a server that has dropped
     * an idle connection otherwise surfaces as a failure on whatever the user
     * did next, rather than as a reconnect they never had to know about.
     */
    fun isUsable(): Boolean
}

/**
 * A failure worth showing the user.
 *
 * The four libraries throw entirely different things - an IOException, a
 * protocol-specific status object, an HTTP code - so each client translates
 * into this rather than letting its own vocabulary reach the UI.
 */
class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Path helpers shared by the clients, which all speak forward slashes. */
internal object RemotePaths {

    /** Leading slash, no trailing slash, no doubled separators. */
    fun normalise(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part == "..") stack.removeLastOrNull() else stack.add(part)
        }
        return "/" + stack.joinToString("/")
    }

    fun join(parent: String, name: String): String =
        normalise(if (parent.endsWith("/")) "$parent$name" else "$parent/$name")

    fun parent(path: String): String {
        val normalised = normalise(path)
        if (normalised == "/") return "/"
        return normalised.substringBeforeLast('/').ifEmpty { "/" }
    }

    fun name(path: String): String = normalise(path).substringAfterLast('/')

    /** SMB wants backslashes and no leading separator, relative to the share. */
    fun toSmb(path: String): String = normalise(path).trimStart('/').replace('/', '\\')
}
