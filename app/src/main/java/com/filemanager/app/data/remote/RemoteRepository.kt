package com.filemanager.app.data.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The only place the rest of the app talks to a network server.
 *
 * Mirrors what FileRepository is for local files: every call suspends and
 * moves to the IO dispatcher, so nothing above this has to remember that a
 * directory listing here is a round trip rather than a syscall.
 */
class RemoteRepository(
    private val connections: RemoteConnections,
    /** Where downloads land before being handed to another app. */
    private val cacheDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun list(server: RemoteServer, path: String): List<RemoteEntry> =
        withContext(io) {
            connections.use(server) { it.list(path) }
                // Folders first, then by name, which is what the local browser
                // does and what the sort settings there default to.
                .sortedWith(compareByDescending<RemoteEntry> { it.isDir }
                    .thenBy { it.name.lowercase() })
        }

    /**
     * Fetch a file into the cache so another app can open it.
     *
     * Named from the full remote path rather than just the file name: two
     * folders on the same server can hold different files called report.pdf,
     * and a cache keyed on the name alone would hand back the wrong one.
     */
    suspend fun cacheForOpening(server: RemoteServer, entry: RemoteEntry): File =
        withContext(io) {
            val folder = File(cacheDir, "remote/${server.id}").apply { mkdirs() }
            val target = File(folder, "${entry.path.hashCode().toUInt()}-${entry.name}")

            // Skip the transfer when the cached copy already matches. Size is
            // a weak check, but the alternative is re-downloading a video
            // every time the user taps it.
            if (target.isFile && target.length() == entry.size && entry.size > 0) {
                return@withContext target
            }
            connections.use(server) { it.download(entry.path, target) }
            target
        }

    suspend fun download(server: RemoteServer, entry: RemoteEntry, to: File): Unit =
        withContext(io) { connections.use(server) { it.download(entry.path, to) } }

    suspend fun upload(server: RemoteServer, from: File, toPath: String): Unit =
        withContext(io) { connections.use(server) { it.upload(from, toPath) } }

    suspend fun delete(server: RemoteServer, entry: RemoteEntry): Unit =
        withContext(io) { connections.use(server) { it.delete(entry.path, entry.isDir) } }

    suspend fun makeDirectory(server: RemoteServer, parent: String, name: String): Unit =
        withContext(io) {
            connections.use(server) { it.makeDirectory(RemotePaths.join(parent, name)) }
        }

    suspend fun rename(server: RemoteServer, entry: RemoteEntry, newName: String): Unit =
        withContext(io) {
            val target = RemotePaths.join(RemotePaths.parent(entry.path), newName)
            connections.use(server) { it.rename(entry.path, target) }
        }

    /** Opens a throwaway connection and lists the base path. See
     *  [RemoteConnections.test] for why it does not reuse the pooled one. */
    suspend fun test(server: RemoteServer): Unit = withContext(io) { connections.test(server) }

    fun forget(serverId: String) = connections.disconnect(serverId)
}
