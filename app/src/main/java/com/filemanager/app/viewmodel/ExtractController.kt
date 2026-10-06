package com.filemanager.app.viewmodel

import com.filemanager.app.data.ArchiveRow
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.archiveBaseName
import com.filemanager.app.data.archiveTree
import com.filemanager.app.data.freeName
import com.filemanager.app.data.initiallyOpen
import com.filemanager.app.data.isWrongPassword
import com.filemanager.app.data.transfer.Pacer
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.FileException
import uniffi.filemanager_core.ProgressListener
import java.io.File

/** Files written so far, of how many - 0 when the archive does not say. */
data class ExtractProgress(val done: Long, val total: Long)

/** Extracting one archive, from the question to the end. */
data class ExtractState(
    val archive: FileEntry,
    /** The folder it goes into: the archive's own, unless another was chosen. */
    val location: String,
    /** Whether a folder named after the archive is made there for it. */
    val intoFolder: Boolean = true,
    /** What that folder is called: the archive's name, with a number added
     *  when a folder of that name is already there. */
    val folderName: String,
    val needsPassword: Boolean = false,
    val wrongPassword: Boolean = false,
    /** What the archive holds, as rows; null while it is being read. */
    val contents: List<ArchiveRow>? = null,
    /** Why the contents are not shown, when they are not. */
    val contentsNote: String? = null,
    /** True when the names inside are encrypted too, so the contents can be
     *  shown once the password has been typed. */
    val namesProtected: Boolean = false,
    /** Folders opened in the contents. */
    val open: Set<String> = emptySet(),
    /** Set while it is being extracted. */
    val progress: ExtractProgress? = null,
) {
    /** Where the files will land. */
    val destination: String
        get() = if (intoFolder) File(location, folderName).path else location
}

/** How an extraction ended, to be said once. */
data class ExtractOutcome(
    val message: String,
    /** The folder the files went into, so the way there can be offered -
     *  null when no files were written. */
    val folder: String? = null,
)

/**
 * Asking before extracting an archive, and then extracting it - for every
 * screen that can, so it is the same everywhere.
 *
 * It shows what the archive holds before anything is written, and lets the
 * user choose where it goes: into a new folder named after it, as it always
 * did, or straight into a folder, the archive's own or any other. Nothing
 * already there is written over - see the core's archive_extract.
 *
 * [onDone] is told when an extraction ends, however it ended, so the owner
 * can look again at what is on disk. [scope] is the owner's, so it all stops
 * with the screen.
 */
class ExtractController(
    private val repository: FileRepository,
    volumeRoots: List<String>,
    private val scope: CoroutineScope,
    private val onDone: () -> Unit,
) {
    /** Choosing a folder to extract into. */
    val picker = FolderPicker(repository, volumeRoots, scope)

    private val _state = MutableStateFlow<ExtractState?>(null)

    /** Null when nothing is being extracted or asked about. */
    val state: StateFlow<ExtractState?> = _state.asStateFlow()

    private val _outcome = MutableStateFlow<ExtractOutcome?>(null)

    /** How the last extraction ended, until it has been said. Kept here
     *  rather than with the owner's other messages, so the way to the files
     *  is offered the same on every screen. */
    val outcome: StateFlow<ExtractOutcome?> = _outcome.asStateFlow()

    private var cancel: CancelToken? = null

    /** Ask about extracting [archive]. */
    fun open(archive: FileEntry) {
        val location = File(archive.path).parent ?: return
        _state.value = ExtractState(
            archive = archive,
            location = location,
            folderName = folderNameIn(location, archive.name),
        )
        // Settled before anything is written, so an encrypted archive asks up
        // front rather than failing partway.
        scope.launch {
            val needs = repository.archiveNeedsPassword(archive.path)
            update(archive) { it.copy(needsPassword = needs) }
        }
        scope.launch { readContents(archive, password = null) }
    }

    /**
     * List the archive with [password], for one whose names are protected as
     * well as its contents.
     */
    fun showContents(password: String) {
        val current = _state.value ?: return
        update(current.archive) { it.copy(contents = null, contentsNote = null) }
        scope.launch { readContents(current.archive, password) }
    }

    private suspend fun readContents(archive: FileEntry, password: String?) {
        runCatching { repository.listArchive(archive.path, password) }
            .onSuccess { entries ->
                val rows = archiveTree(entries)
                update(archive) {
                    it.copy(
                        contents = rows,
                        contentsNote = null,
                        namesProtected = false,
                        open = initiallyOpen(rows),
                    )
                }
            }
            .onFailure { failure ->
                update(archive) {
                    it.copy(
                        contents = emptyList(),
                        namesProtected = failure is FileException.PasswordRequired ||
                            (password != null && failure.isWrongPassword()),
                        contentsNote = when {
                            failure is FileException.PasswordRequired ->
                                "The names inside are protected too. Enter the password to see them."
                            failure.isWrongPassword() -> "That password did not work"
                            else -> failure.userMessage("Could not read what is inside")
                        },
                    )
                }
            }
    }

    fun toggleFolder(path: String) = _state.update { current ->
        current?.copy(open = if (path in current.open) current.open - path else current.open + path)
    }

    fun setIntoFolder(into: Boolean) = _state.update { it?.copy(intoFolder = into) }

    /** Open the folder picker where the files are going now. */
    fun chooseLocation() {
        val current = _state.value ?: return
        picker.open(current.location)
    }

    /** Take the folder the picker shows as where to extract to. */
    fun pickLocation() {
        val chosen = picker.choose() ?: return
        _state.update { current ->
            current?.copy(location = chosen, folderName = folderNameIn(chosen, current.archive.name))
        }
    }

    fun extract(password: String?) {
        val current = _state.value ?: return
        if (current.progress != null) return
        val destination = current.destination
        val token = CancelToken()
        cancel = token
        _state.update { it?.copy(progress = ExtractProgress(0, 0), wrongPassword = false) }

        val pacer = Pacer(PROGRESS_INTERVAL_MS)
        val listener = object : ProgressListener {
            override fun onProgress(done: ULong, total: ULong, currentPath: String) {
                if (pacer.due()) {
                    update(current.archive) {
                        it.copy(progress = ExtractProgress(done.toLong(), total.toLong()))
                    }
                }
            }
        }

        scope.launch {
            runCatching {
                repository.extract(current.archive.path, destination, password, listener, token)
            }
                .onSuccess { count ->
                    _state.value = null
                    val files = if (count == 1uL) "1 file" else "$count files"
                    // An empty archive leaves nothing to go and look at.
                    finish("Extracted $files to ${File(destination).name}", destination.takeIf { count > 0uL })
                }
                .onFailure { failure ->
                    // The folder made for it, if nothing went into it. The core
                    // clears up after a wrong password itself; this covers a
                    // failure on the very first file.
                    if (current.intoFolder) {
                        File(destination).let { dir ->
                            if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete()
                        }
                    }
                    when {
                        token.isCancelled() -> {
                            _state.value = null
                            finish("Extraction stopped")
                        }
                        // Matched on the exception type, not its text: a
                        // variant with no fields has an empty message.
                        failure.isWrongPassword() -> update(current.archive) {
                            it.copy(progress = null, needsPassword = true, wrongPassword = true)
                        }
                        else -> {
                            _state.value = null
                            finish(failure.userMessage("Could not extract"))
                        }
                    }
                }
        }
    }

    /** End with [message], offering [folder] when files went into it. */
    private fun finish(message: String, folder: String? = null) {
        _outcome.value = ExtractOutcome(message, folder)
        onDone()
    }

    /** The outcome has been shown. */
    fun consumeOutcome() {
        _outcome.value = null
    }

    /** Stop an extraction under way. What was written stays, but for the
     *  file it was in the middle of. */
    fun stop() {
        cancel?.cancel()
    }

    /** Close the question, or stop the extraction and close. */
    fun dismiss() {
        if (_state.value?.progress != null) {
            stop()
            return
        }
        picker.close()
        _state.value = null
    }

    /** Change the state, if it is still about [archive]. */
    private fun update(archive: FileEntry, change: (ExtractState) -> ExtractState) =
        _state.update { current -> if (current?.archive?.path == archive.path) change(current) else current }

    /** A folder in [location] named after [archiveName] that is not there yet. */
    private fun folderNameIn(location: String, archiveName: String): String =
        freeName(File(location), archiveBaseName(archiveName)).name

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
