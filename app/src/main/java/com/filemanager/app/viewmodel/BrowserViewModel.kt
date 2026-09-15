package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.SortKeySetting
import com.filemanager.app.data.ViewModeSetting
import com.filemanager.app.data.ViewScope
import com.filemanager.app.data.FolderWatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import com.filemanager.app.data.isWrongPassword
import com.filemanager.app.data.userMessage
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
    /** Paths currently pinned, so rows can show the marker. */
    val pinned: Set<String> = emptySet(),
    /** Paths currently favourited, so the menu can offer the opposite. */
    val favorites: Set<String> = emptySet(),
    /**
     * Briefly marked after arriving from "show in folder".
     *
     * Opening the folder alone leaves the user to find the file themselves,
     * which in a folder of hundreds is most of the work they were trying to
     * avoid.
     */
    val highlightPath: String? = null,
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
    private val paths: PathPrefs,
    private val settings: AppSettings,
    startPath: String,
    highlightPath: String? = null,
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
        // Seeded from the stored preferences, so a folder opens the way the
        // last one was left rather than back at the defaults.
        _state.update {
            it.copy(
                viewMode = settings.viewMode(ViewScope.Folders).value.toViewMode(),
                showHidden = settings.showHidden.value,
                sort = SortOptions(
                    key = settings.sortKey.value.toSortKey(),
                    descending = settings.sortDescending.value,
                    dirsFirst = true,
                ),
            )
        }
        _state.update { it.copy(highlightPath = highlightPath) }
        load(startPath)

        // Re-sort when a pin changes, so the item moves without a reload.
        viewModelScope.launch {
            paths.pinned.collect { pinned ->
                _state.update {
                    it.copy(pinned = pinned, entries = applyPinning(it.entries, pinned))
                }
            }
        }
        viewModelScope.launch {
            paths.favorites.collect { favorites -> _state.update { it.copy(favorites = favorites) } }
        }
    }

    /**
     * Pinned items first, everything else in the order the core returned.
     *
     * A stable partition, so the chosen sort still holds inside each group -
     * pinning is a promotion, not a second sort key.
     */
    private fun applyPinning(entries: List<FileEntry>, pinned: Set<String>): List<FileEntry> {
        if (pinned.isEmpty()) return entries
        val (top, rest) = entries.partition { it.path in pinned }
        return top + rest
    }

    fun load(path: String) {
        watch(path)
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
                _state.update {
                    it.copy(
                        entries = applyPinning(entries, paths.pinned.value),
                        isLoading = false,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(isLoading = false, error = error.userMessage("Could not open folder"))
                }
            }
        }
    }

    fun refresh() = load(_state.value.path)

    /**
     * Reload without the loading state, for a refresh the user did not ask to
     * see - a pull-to-refresh gesture, or a change on disk.
     *
     * Showing the spinner would make the list blink every time anything in the
     * folder changed, which is worse than the staleness it fixes.
     */
    private fun reloadQuietly() {
        val path = _state.value.path
        viewModelScope.launch {
            runCatching { repository.list(path, _state.value.showHidden, _state.value.sort) }
                .onSuccess { entries ->
                    _state.update {
                        // Discard a result for a folder the user has left.
                        if (it.path != path) it
                        else it.copy(entries = applyPinning(entries, paths.pinned.value))
                    }
                }
        }
    }

    /** Pull-to-refresh. The gesture animates; the work stays invisible. */
    fun refreshQuietly() = reloadQuietly()

    // --- Live folder watching -------------------------------------------------

    private var watcher: FolderWatcher? = null
    private var watchJob: Job? = null

    private fun watch(path: String) {
        watcher?.stop()
        watcher = FolderWatcher(path) {
            // Copying one file emits a burst of events, so collapse them:
            // wait for quiet, then reload once.
            watchJob?.cancel()
            watchJob = viewModelScope.launch {
                delay(WATCH_DEBOUNCE_MS)
                reloadQuietly()
            }
        }.also { it.start() }
    }

    override fun onCleared() {
        watcher?.stop()
        super.onCleared()
    }

    /** Navigate up one level, stopping at the volume root. */
    fun navigateUp(): Boolean {
        val parent = File(_state.value.path).parentFile ?: return false
        if (!parent.canRead()) return false
        load(parent.absolutePath)
        return true
    }

    fun setSort(sort: SortOptions) {
        settings.setSort(sort.key.toSetting(), sort.descending)
        _state.update { it.copy(sort = sort) }
        refresh()
    }

    /** View option: whether hidden files appear in the listing at all. */
    fun toggleShowHidden() {
        val next = !_state.value.showHidden
        settings.setShowHidden(next)
        _state.update { it.copy(showHidden = next) }
        refresh()
    }

    fun setViewMode(mode: ViewMode) {
        // Folders keep one layout between them. Choosing Grid in Pictures and
        // finding List again in the folder next to it would read as the
        // setting not having been saved.
        settings.setViewMode(ViewScope.Folders, mode.toSetting())
        _state.update { it.copy(viewMode = mode) }
    }

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

    /** Clears the marker once it has been shown for long enough to notice. */
    fun clearHighlight() = _state.update { it.copy(highlightPath = null) }

    // --- Marks and visibility ------------------------------------------------

    /**
     * Move any marks from an old path to a new one.
     *
     * Favourites and pins are keyed by path, so anything that renames or moves
     * a file has to carry them across or the mark is silently lost - which
     * looks to the user like the app forgetting on its own.
     */
    private fun carryMarks(from: String, to: String) {
        if (from == to) return
        if (paths.isFavorite(from)) {
            paths.toggleFavorite(listOf(from))
            paths.toggleFavorite(listOf(to))
        }
        if (paths.isPinned(from)) {
            paths.togglePinned(listOf(from))
            paths.togglePinned(listOf(to))
        }
    }

    fun toggleFavorite() {
        val selected = _state.value.selected.toList()
        paths.toggleFavorite(selected)
        _messages.value = if (selected.all { paths.isFavorite(it) }) {
            "Added to favourites"
        } else {
            "Removed from favourites"
        }
        clearSelection()
    }

    fun togglePinned() {
        val selected = _state.value.selected.toList()
        paths.togglePinned(selected)
        _messages.value = if (selected.all { paths.isPinned(it) }) "Pinned to top" else "Unpinned"
        clearSelection()
    }

    /** File attribute: hide or reveal the selection by renaming it. */
    fun toggleSelectionHidden() {
        val selected = _state.value.selected.toList()
        if (selected.isEmpty()) return

        viewModelScope.launch {
            val renamed = runCatching { repository.toggleHidden(selected) }.getOrNull()
            if (renamed == null) {
                _messages.value = "Could not change visibility"
                return@launch
            }
            // Marks are keyed by path, so a rename has to carry them across or
            // a hidden favourite silently stops being a favourite.
            selected.zip(renamed).forEach { (before, after) -> carryMarks(before, after) }
            clearSelection()
            refresh()
        }
    }

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
                // A move changes every path involved, so the marks follow.
                if (pending.isMove) {
                    pending.paths.forEach { source ->
                        carryMarks(source, File(destination, File(source).name).absolutePath)
                    }
                }
                clipboard.clear()
                _messages.value =
                    "$count ${if (pending.isMove) "moved" else "copied"}"
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false) }
                _messages.value = error.userMessage("Operation failed")
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
                    // Otherwise favourites and pins pile up pointing at files
                    // that are no longer there.
                    this@BrowserViewModel.paths.forget(paths)
                    _messages.value = "${paths.size} moved to trash"
                    refresh()
                }
                .onFailure { _messages.value = it.userMessage("Could not delete") }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            val ok = runCatching { repository.rename(path, newName) }.getOrDefault(false)
            _messages.value = if (ok) null else "A file named \"$newName\" already exists"
            if (ok) {
                carryMarks(path, File(File(path).parentFile, newName).absolutePath)
                refresh()
            }
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

    /**
     * Zip the selection, optionally behind a password.
     *
     * A blank [password] means an ordinary archive - the dialog hands back an
     * empty string when the field is left alone, and that is a request for no
     * encryption rather than for encryption with nothing.
     */
    fun compressSelected(password: String? = null) {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        // Name the zip after the first item, the way most file managers do.
        val base = File(paths.first()).nameWithoutExtension
        val destination = File(_state.value.path, "$base.zip").absolutePath

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching { repository.compress(paths, destination, password) }
                .onSuccess { count ->
                    // Read back rather than assume. Whether the archive really
                    // came out encrypted is the one thing the user cannot
                    // check without another tool, and an unprotected zip they
                    // believe is protected is worse than a failure they can
                    // see.
                    _messages.value = when {
                        password.isNullOrEmpty() -> "Compressed $count files"
                        repository.archiveNeedsPassword(destination) ->
                            "Compressed $count files, password protected"
                        else ->
                            "Compressed $count files, but the password was not applied"
                    }
                    clearSelection()
                    refresh()
                }
                .onFailure {
                    _state.update { s -> s.copy(isLoading = false) }
                    _messages.value = it.userMessage("Could not compress")
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
                    // Matched on the exception type, not its text: a variant
                    // with no fields has an empty message, so a string test
                    // never fired and the prompt silently never reopened.
                    if (error.isWrongPassword()) {
                        _state.update { s ->
                            s.copy(
                                extractTarget = s.entries.firstOrNull { it.path == archivePath },
                                extractNeedsPassword = true,
                                extractWrongPassword = true,
                            )
                        }
                    } else {
                        _messages.value = error.userMessage("Could not extract")
                    }
                }
        }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private companion object {
        /** Long enough for a copy to settle, short enough to feel immediate. */
        const val WATCH_DEBOUNCE_MS = 350L
    }
}
