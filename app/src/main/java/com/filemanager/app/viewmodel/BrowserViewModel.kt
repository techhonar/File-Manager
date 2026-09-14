package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions
import java.io.File

/** What the browser screen renders. */
/**
 * The parts of a file's details that have to be fetched.
 *
 * Everything else comes straight off the FileEntry; these three need either a
 * subtree walk or a MediaStore query, so the sheet shows what it has and fills
 * these in when they land.
 */
data class FileDetails(
    val folderBytes: ULong? = null,
    val fileCount: ULong? = null,
    val folderCount: ULong? = null,
    val ownerApp: String? = null,
)

/** How the file list is laid out. */
enum class ViewMode {
    /** Icon, name, and a single quiet line of date and size. */
    LIST,

    /** Adds type and a fuller timestamp, for comparing files at a glance. */
    DETAILED,

    /** Large thumbnails in a grid, for pictures and video. */
    GRID,
}

data class BrowserState(
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showHidden: Boolean = false,
    val sort: SortOptions = SortOptions(SortKey.NAME, descending = false, dirsFirst = true),
    val viewMode: ViewMode = ViewMode.LIST,
    /** Set while the details sheet is open. */
    val detailsTarget: FileEntry? = null,
    /** Null until the walk and the MediaStore lookup come back. */
    val details: FileDetails? = null,
    /** Archive awaiting the user's confirmation before it is unpacked. */
    val extractTarget: FileEntry? = null,
    val extractNeedsPassword: Boolean = false,
    val extractWrongPassword: Boolean = false,
    /** Paths the user has ticked. Empty means normal (non-selection) mode. */
    val selected: Set<String> = emptySet(),
    /**
     * Set when selection is entered deliberately rather than by long-press.
     *
     * Without it, selection mode could only be derived from something already
     * being selected - so "select all" was unreachable until the user had
     * first long-pressed a file, which is the wrong way round.
     */
    val selectionActive: Boolean = false,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()

    /** True when every visible item is ticked, so the control can offer the
     *  opposite action. */
    val allSelected: Boolean
        get() = entries.isNotEmpty() && selected.size == entries.size

    /** Path split into (label, path) pairs for the breadcrumb bar. */
    val breadcrumbs: List<Pair<String, String>>
        get() {
            if (path.isEmpty()) return emptyList()
            val parts = path.trim('/').split('/')
            var accumulated = ""
            return parts.map { part ->
                accumulated += "/$part"
                part to accumulated
            }
        }
}

class BrowserViewModel(
    private val repository: FileRepository,
    private val clipboard: FileClipboard,
    startPath: String,
    /**
     * Resolves which app created a file.
     *
     * Injected rather than called directly because it needs a Context, and a
     * ViewModel holding one is how activities get leaked.
     */
    private val ownerAppOf: suspend (String) -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState(path = startPath))
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /** Exposed straight from the app-scoped clipboard, so a copy made in
     *  another folder - or in the search results - is still there. */
    val clipboardContents = clipboard.contents

    /** One-off messages for the snackbar (errors, "3 items moved to trash"). */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        load(startPath)
    }

    fun load(path: String) {
        _state.update {
            it.copy(
                path = path,
                isLoading = true,
                error = null,
                selected = emptySet(),
                // A selection belongs to the folder it was made in.
                selectionActive = false,
            )
        }
        viewModelScope.launch {
            runCatching {
                repository.list(path, _state.value.showHidden, _state.value.sort)
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, isLoading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(isLoading = false, error = error.message ?: "Could not open folder")
                }
            }
        }
    }

    fun refresh() = load(_state.value.path)

    /** Navigate up one level, stopping at the volume root. */
    fun navigateUp(): Boolean {
        val parent = File(_state.value.path).parentFile ?: return false
        if (!parent.canRead()) return false
        load(parent.absolutePath)
        return true
    }

    fun setSort(sort: SortOptions) {
        _state.update { it.copy(sort = sort) }
        refresh()
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        refresh()
    }

    fun setViewMode(mode: ViewMode) = _state.update { it.copy(viewMode = mode) }

    fun showDetails(entry: FileEntry) {
        _state.update { it.copy(detailsTarget = entry, details = null) }

        viewModelScope.launch {
            // One walk covers size and contents; tree_stats returns both, so
            // asking for them separately would traverse twice.
            val stats = if (entry.isDir) {
                runCatching { repository.stats(listOf(entry.path)) }.getOrNull()
            } else {
                null
            }
            val owner = runCatching { ownerAppOf(entry.path) }.getOrNull()

            _state.update { current ->
                // Discard a result that arrives after the sheet moved on.
                if (current.detailsTarget?.path != entry.path) return@update current
                current.copy(
                    details = FileDetails(
                        folderBytes = stats?.totalBytes,
                        fileCount = stats?.fileCount,
                        folderCount = stats?.dirCount,
                        ownerApp = owner,
                    ),
                )
            }
        }
    }

    fun dismissDetails() = _state.update { it.copy(detailsTarget = null, details = null) }

    // --- Selection ----------------------------------------------------------

    fun toggleSelection(path: String) = _state.update { current ->
        val next = current.selected.toMutableSet()
        if (!next.add(path)) next.remove(path)
        current.copy(selected = next)
    }

    /** Enter selection mode with nothing ticked, from the overflow menu. */
    fun enterSelectionMode() = _state.update { it.copy(selectionActive = true) }

    /**
     * Select everything, or clear it if everything is already selected.
     *
     * One control doing both is what the user expects from a "select all"
     * checkbox - having to tap each of 300 rows to undo it would not be.
     */
    fun toggleSelectAll() = _state.update { current ->
        if (current.allSelected) {
            current.copy(selected = emptySet())
        } else {
            current.copy(selected = current.entries.map { it.path }.toSet())
        }
    }

    fun clearSelection() =
        _state.update { it.copy(selected = emptySet(), selectionActive = false) }

    // --- Operations ---------------------------------------------------------

    fun cut() {
        clipboard.cut(_state.value.selected.toList())
        clearSelection()
    }

    fun copy() {
        clipboard.copy(_state.value.selected.toList())
        clearSelection()
    }

    fun paste() {
        val pending = clipboard.contents.value ?: return
        val destination = _state.value.path

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching {
                if (pending.isMove) {
                    repository.move(pending.paths, destination)
                } else {
                    repository.copy(pending.paths, destination)
                }
            }.onSuccess { count ->
                clipboard.clear()
                _messages.value =
                    "$count ${if (pending.isMove) "moved" else "copied"}"
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false) }
                _messages.value = error.message ?: "Operation failed"
            }
        }
    }

    /** Delete goes through the trash, so it is always undoable. */
    fun deleteSelected() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            runCatching { repository.moveToTrash(paths) }
                .onSuccess {
                    _messages.value = "${paths.size} moved to trash"
                    refresh()
                }
                .onFailure { _messages.value = it.message ?: "Could not delete" }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            val ok = runCatching { repository.rename(path, newName) }.getOrDefault(false)
            _messages.value = if (ok) null else "A file named \"$newName\" already exists"
            if (ok) refresh()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val ok = runCatching { repository.createFolder(_state.value.path, name) }
                .getOrDefault(false)
            _messages.value = if (ok) null else "Could not create folder"
            if (ok) refresh()
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            val ok = runCatching { repository.createFile(_state.value.path, name) }
                .getOrDefault(false)
            _messages.value = if (ok) null else "A file named \"$name\" already exists"
            if (ok) refresh()
        }
    }

    fun compressSelected() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        // Name the zip after the first item, the way most file managers do.
        val base = File(paths.first()).nameWithoutExtension
        val destination = File(_state.value.path, "$base.zip").absolutePath

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching { repository.compress(paths, destination) }
                .onSuccess {
                    _messages.value = "Compressed $it files"
                    clearSelection()
                    refresh()
                }
                .onFailure {
                    _state.update { s -> s.copy(isLoading = false) }
                    _messages.value = it.message ?: "Could not compress"
                }
        }
    }

    /**
     * Ask before unpacking, rather than extracting on a single tap.
     *
     * Whether a password is needed is settled here, before anything is
     * written - an encrypted archive discovered partway through would leave
     * half a folder behind.
     */
    fun confirmExtract(entry: FileEntry) {
        _state.update {
            it.copy(
                extractTarget = entry,
                extractNeedsPassword = false,
                extractWrongPassword = false,
            )
        }
        viewModelScope.launch {
            val needs = repository.archiveNeedsPassword(entry.path)
            _state.update { current ->
                if (current.extractTarget?.path != entry.path) current
                else current.copy(extractNeedsPassword = needs)
            }
        }
    }

    fun dismissExtract() = _state.update {
        it.copy(
            extractTarget = null,
            extractNeedsPassword = false,
            extractWrongPassword = false,
        )
    }

    fun extract(archivePath: String, password: String? = null) {
        val destination = File(
            File(archivePath).parentFile,
            File(archivePath).nameWithoutExtension,
        ).absolutePath

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, extractTarget = null) }
            runCatching { repository.extract(archivePath, destination, password) }
                .onSuccess {
                    _messages.value = "Extracted $it files"
                    refresh()
                }
                .onFailure { error ->
                    _state.update { s -> s.copy(isLoading = false) }
                    // A wrong password reopens the prompt; anything else is a
                    // failure the user cannot retype their way out of.
                    val wrongPassword = error.message?.contains("wrong password", true) == true
                    if (wrongPassword) {
                        _state.update { s ->
                            s.copy(
                                extractTarget = s.entries.firstOrNull { it.path == archivePath },
                                extractNeedsPassword = true,
                                extractWrongPassword = true,
                            )
                        }
                    } else {
                        _messages.value = error.message ?: "Could not extract"
                    }
                }
        }
    }

    fun consumeMessage() {
        _messages.value = null
    }
}
