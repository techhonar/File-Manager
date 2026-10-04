package com.filemanager.app.data.transfer

import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.freeName
import com.filemanager.app.data.freeRemoteName
import com.filemanager.app.data.remote.RemoteEntry
import com.filemanager.app.data.remote.RemotePaths
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class Direction { DOWNLOAD, UPLOAD }

/** One batch of files on its way between this phone and a server, as it stands. */
data class Transfer(
    val id: Int,
    val direction: Direction,
    val serverId: String,
    val serverLabel: String,
    /** Where the files are going, as the user would name it: a folder on the
     *  phone for a download, a path on the server for an upload. */
    val destination: String,
    val fileCount: Int,
    /** Files dealt with so far, whether they made it or not. */
    val filesDone: Int = 0,
    /** The file on its way now. */
    val current: String? = null,
    val bytesDone: Long = 0,
    /** The whole batch, known before it starts: a download's sizes come from
     *  the server's listing, an upload's from the files themselves. */
    val bytesTotal: Long,
    /** Set once it is over, one way or another. */
    val outcome: Outcome? = null,
) {
    /** How far through, or null when the sizes are not known. */
    val fraction: Float?
        get() = if (bytesTotal > 0) (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f) else null
}

data class Outcome(
    val succeeded: Int,
    val failed: Int,
    val cancelled: Boolean,
    /** One line for the user. */
    val summary: String,
)

/**
 * Downloads from and uploads to network storage, for the whole app.
 *
 * Transfers used to run in the remote browser's view model, so they lasted as
 * long as that screen: leaving it stopped the transfer after the file it was
 * on, and nothing said how far it had got. They run here now, outliving any
 * screen, and the app keeps a foreground service and a notification going
 * while they do - see [Host]. Each reports its progress as it goes, paced so
 * that whatever draws it is not asked to for every buffer.
 *
 * No Android here: the service and the notifications are the host's, which is
 * what lets the tests run this against a real server on the JVM.
 */
class TransferCenter(
    private val repository: RemoteRepository,
    private val clipboard: FileClipboard,
    /** Moves local files to the trash and says how many went: finishes a move. */
    private val trash: suspend (List<String>) -> Int,
    private val host: Host,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** How often progress is passed on. Small in tests, which want every step. */
    private val progressIntervalMs: Long = 250,
) {
    /** What the app does around transfers, which needs Android. */
    interface Host {
        /** A transfer has begun. Called for each one. */
        fun started()

        /** [transfer] is over; its [Transfer.outcome] says how it went. */
        fun finished(transfer: Transfer)
    }

    private val _active = MutableStateFlow<List<Transfer>>(emptyList())

    /** Transfers still running, oldest first. */
    val active: StateFlow<List<Transfer>> = _active.asStateFlow()

    private val jobs = ConcurrentHashMap<Int, MutableStateFlow<Transfer>>()
    private val cancelled = ConcurrentHashMap.newKeySet<Int>()
    private val nextId = AtomicInteger()

    /** The running transfer [id], to follow, or null if it is over. */
    fun follow(id: Int): StateFlow<Transfer>? = jobs[id]?.asStateFlow()

    /** Stop transfer [id] after the bytes in hand. What was half-sent is removed. */
    fun cancel(id: Int) {
        if (jobs.containsKey(id)) cancelled += id
    }

    fun cancelAll() {
        jobs.keys.forEach(::cancel)
    }

    /**
     * Download [entries] from [server] into [into].
     *
     * Never onto a file already there. Downloading straight to
     * Download/<name> replaced any file of the user's with that name - and on
     * failure the cleanup then deleted it, so a server that was merely
     * unreachable destroyed a local file the download had never touched.
     */
    fun download(server: RemoteServer, entries: List<RemoteEntry>, into: File): StateFlow<Transfer> {
        val job = begin(
            Transfer(
                id = nextId.incrementAndGet(),
                direction = Direction.DOWNLOAD,
                serverId = server.id,
                serverLabel = server.label,
                destination = into.name,
                fileCount = entries.size,
                bytesTotal = entries.sumOf { it.size.coerceAtLeast(0L) },
            ),
        )
        val id = job.value.id

        scope.launch {
            var succeeded = 0
            var failed = 0
            var before = 0L
            val pacer = Pacer(progressIntervalMs)
            for (entry in entries) {
                if (id in cancelled) break
                job.publish { it.copy(current = entry.name) }
                val target = claim(into, entry.name)
                try {
                    repository.download(server, entry, target) { bytes ->
                        if (id in cancelled) throw TransferCancelledException()
                        if (pacer.due()) job.publish { it.copy(bytesDone = before + bytes) }
                    }
                    succeeded++
                } catch (e: CancellationException) {
                    target.delete()
                    throw e
                } catch (e: Exception) {
                    // A half-written file is worse than none: it looks like a
                    // download that worked. Safe to remove - the name was free
                    // before this began, so the file is this transfer's own.
                    target.delete()
                    if (id !in cancelled) failed++
                }
                before += entry.size.coerceAtLeast(0L)
                job.publish { it.copy(filesDone = it.filesDone + 1, bytesDone = before) }
            }

            val stopped = id in cancelled
            finish(
                job,
                Outcome(
                    succeeded = succeeded,
                    failed = failed,
                    cancelled = stopped,
                    summary = when {
                        stopped -> "Download stopped: $succeeded of ${entries.size} downloaded"
                        failed == 0 -> "Downloaded $succeeded to ${into.name}"
                        else -> "Downloaded $succeeded, $failed failed"
                    },
                ),
            )
        }
        return job.asStateFlow()
    }

    /**
     * Upload [files] to [folder] on [server].
     *
     * Not over a file already there: [taken] is the folder's listing, so a
     * clash costs nothing to see, and it grows as names are used, so two
     * files with one name - from different folders - do not both take it.
     *
     * A [move] sends the originals to the trash once their upload has been
     * confirmed - only those, so a transfer that failed or was stopped
     * partway leaves the rest where they are. [skipped] is how many folders
     * the caller left out, for the summary: there is no recursive upload, and
     * quietly flattening one would be worse than saying so.
     */
    fun upload(
        server: RemoteServer,
        files: List<File>,
        folder: String,
        taken: Set<String>,
        move: Boolean,
        skipped: Int = 0,
    ): StateFlow<Transfer> {
        val job = begin(
            Transfer(
                id = nextId.incrementAndGet(),
                direction = Direction.UPLOAD,
                serverId = server.id,
                serverLabel = server.label,
                destination = folder,
                fileCount = files.size,
                bytesTotal = files.sumOf { it.length() },
            ),
        )
        val id = job.value.id

        scope.launch {
            val names = HashSet(taken)
            val uploaded = mutableListOf<String>()
            var failed = 0
            var before = 0L
            val pacer = Pacer(progressIntervalMs)
            for (file in files) {
                if (id in cancelled) break
                job.publish { it.copy(current = file.name) }
                val name = freeRemoteName(names, file.nameWithoutExtension, file.extension)
                names += name
                val target = RemotePaths.join(folder, name)
                try {
                    repository.upload(server, file, target) { bytes ->
                        if (id in cancelled) throw TransferCancelledException()
                        if (pacer.due()) job.publish { it.copy(bytesDone = before + bytes) }
                    }
                    uploaded += file.absolutePath
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // What arrived of it is not the file; the name was free,
                    // so whatever is there now is this transfer's to remove.
                    runCatching {
                        val partial = RemoteEntry(name, target, 0, isDir = false, modifiedMs = 0)
                        repository.delete(server, partial)
                    }
                    if (id !in cancelled) failed++
                }
                before += file.length()
                job.publish { it.copy(filesDone = it.filesDone + 1, bytesDone = before) }
            }

            var moved = 0
            if (move && uploaded.isNotEmpty()) {
                // To the trash rather than deleted outright, which is what a
                // move does everywhere else in the app and leaves a way back.
                moved = runCatching { trash(uploaded) }.getOrDefault(0)
            }
            val stopped = id in cancelled
            // Only once it has all been dealt with: leaving it would invite a
            // second paste that moves nothing, having already moved it.
            if (failed == 0 && !stopped) clipboard.clear()

            finish(
                job,
                Outcome(
                    succeeded = uploaded.size,
                    failed = failed,
                    cancelled = stopped,
                    summary = buildString {
                        if (stopped) {
                            append("Upload stopped: ${uploaded.size} of ${files.size} ")
                            append(if (move) "moved" else "uploaded")
                        } else {
                            append(if (move) "Moved " else "Uploaded ")
                            append(uploaded.size)
                        }
                        if (move && moved < uploaded.size) append(" (originals kept)")
                        if (failed > 0) append(", $failed failed")
                        if (skipped > 0) append(", $skipped folders skipped")
                    },
                ),
            )
        }
        return job.asStateFlow()
    }

    /**
     * A file in [dir] named after [name] that nothing else has: taken by
     * creating it, so two downloads into one folder cannot both pick the same
     * free name before either has written to it.
     */
    private fun claim(dir: File, name: String): File {
        dir.mkdirs()
        val base = File(name).nameWithoutExtension
        val extension = File(name).extension
        while (true) {
            val candidate = freeName(dir, base, extension)
            if (candidate.createNewFile()) return candidate
        }
    }

    private fun begin(transfer: Transfer): MutableStateFlow<Transfer> {
        val job = MutableStateFlow(transfer)
        jobs[transfer.id] = job
        _active.update { it + transfer }
        host.started()
        return job
    }

    /** Change [this] job, and the list of running ones with it. */
    private fun MutableStateFlow<Transfer>.publish(change: (Transfer) -> Transfer) {
        val next = change(value)
        value = next
        _active.update { list -> list.map { if (it.id == next.id) next else it } }
    }

    private fun finish(job: MutableStateFlow<Transfer>, outcome: Outcome) {
        val final = job.value.copy(outcome = outcome, current = null)
        jobs.remove(final.id)
        cancelled.remove(final.id)
        _active.update { list -> list.filterNot { it.id == final.id } }
        host.finished(final)
        // Last: whoever sees the outcome then finds everything else about the
        // end already done - off the running list, and the host told.
        job.value = final
    }
}
