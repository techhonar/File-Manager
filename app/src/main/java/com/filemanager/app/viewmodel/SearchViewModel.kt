package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.CancelToken
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SearchFilter
import uniffi.filemanager_core.SearchSink
import java.util.concurrent.atomic.AtomicLong

data class SearchState(
    val query: String = "",
    val categories: Set<FileCategory> = emptySet(),
    val results: List<FileEntry> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    /** Paths the user has ticked. Empty means normal (non-selection) mode. */
    val selected: Set<String> = emptySet(),
    val message: String? = null,
    /**
     * Set when selection is entered deliberately rather than by long-press.
     *
     * Without it, selection mode could only be derived from something already
     * being selected - so "select all" was unreachable until the user had
     * first long-pressed a file, which is the wrong way round.
     */
    val selectionActive: Boolean = false,
    /** Category results are often photos, so the layout matters here too. */
    val viewMode: ViewMode = ViewMode.LIST,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()

    /** True when every visible item is ticked, so the control can offer the
     *  opposite action. */
    val allSelected: Boolean
        get() = results.isNotEmpty() && selected.size == results.size
}

/**
 * Results of a completed walk, kept so that extending the query can be
 * answered without touching the disk again.
 */
private data class SearchCache(
    val query: String,
    val categories: Set<FileCategory>,
    val entries: List<FileEntry>,
)

class SearchViewModel(
    private val repository: FileRepository,
    private val clipboard: FileClipboard,
    private val roots: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var cancelToken: CancelToken? = null

    /**
     * Which search a batch belongs to.
     *
     * A cancelled walk does not stop instantly - its threads finish the file
     * they are on and may deliver another batch - so without this, late
     * results from the previous query would land in the new query's list.
     */
    private val generation = AtomicLong(0)

    /** Everything the last completed walk found, for narrowing. Null if the
     *  last walk was cancelled or hit the cap, in which case it is unusable. */
    private var cache: SearchCache? = null

    /** Accumulates a running walk's results, which the cache is built from. */
    private val accumulated = mutableListOf<FileEntry>()
    private val accumulatedLock = Any()

    /**
     * Every keystroke searches, as it should - but most keystrokes never
     * touch the disk.
     *
     * Substring matching only ever narrows: a name containing "mad" must also
     * contain "ma", so the results for "mad" are a subset of the results for
     * "ma". Extending the query therefore just filters what is already in
     * hand, which is instant and needs no walk at all. Only a query that is
     * not an extension of the cached one - the first character, or deleting
     * past it - has to go to disk.
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }

        if (narrowFromCache(query)) {
            return
        }
        scheduleWalk()
    }

    fun toggleCategory(category: FileCategory) {
        _state.update { current ->
            val next = current.categories.toMutableSet()
            if (!next.add(category)) next.remove(category)
            current.copy(categories = next)
        }
        // Changing the category filter can widen the set, so the cache cannot
        // answer it.
        cache = null
        scheduleWalk()
    }

    /**
     * Replace the filter with a single category and search immediately.
     *
     * Used when the user taps a tile on the home screen - it replaces rather
     * than adds, so arriving from "Videos" shows videos and not videos plus
     * whatever was ticked last time.
     */
    fun applyCategory(category: FileCategory) {
        _state.update { it.copy(categories = setOf(category)) }
        cache = null
        scheduleWalk()
    }

    fun clear() {
        cancelSearch()
        cache = null
        _state.value = SearchState()
    }

    /**
     * Answer from the cache if the new query only narrows it.
     *
     * Returns false when a real walk is needed.
     */
    private fun narrowFromCache(query: String): Boolean {
        val cached = cache ?: return false
        val current = _state.value

        if (query.isBlank()) return false
        if (cached.categories != current.categories) return false
        // Only an extension is safe. "ma" -> "mad" narrows; "mad" -> "max"
        // or "mad" -> "m" could both match files the cache never held.
        if (!query.startsWith(cached.query, ignoreCase = true)) return false

        // No walk is running, so nothing can arrive late and overwrite this.
        cancelSearch()

        val filtered = cached.entries
            .filter { it.name.contains(query, ignoreCase = true) }

        _state.update {
            it.copy(results = filtered, isSearching = false, hasSearched = true)
        }
        return true
    }

    private fun scheduleWalk() {
        cancelSearch()

        val current = _state.value
        if (current.query.isBlank() && current.categories.isEmpty()) {
            _state.update {
                it.copy(results = emptyList(), hasSearched = false, isSearching = false)
            }
            return
        }

        val mine = generation.incrementAndGet()
        synchronized(accumulatedLock) { accumulated.clear() }

        searchJob = viewModelScope.launch {
            // A short pause only before a walk. Narrowing above is instant and
            // never waits; this exists so that typing three characters from
            // empty starts one walk rather than three that cancel each other.
            delay(WALK_DEBOUNCE_MS)

            val token = CancelToken()
            cancelToken = token
            _state.update { it.copy(isSearching = true, results = emptyList()) }

            val sink = object : SearchSink {
                override fun onBatch(entries: List<FileEntry>) {
                    if (generation.get() != mine) return

                    // Appended in arrival order, not re-sorted on every
                    // batch: sorting the whole list each time is O(n log n)
                    // per batch, and it makes rows the user is reading jump
                    // around. New results simply arrive at the bottom, and
                    // the list is ordered once the walk finishes.
                    val snapshot = synchronized(accumulatedLock) {
                        accumulated += entries
                        accumulated.toList()
                    }
                    _state.update { it.copy(results = snapshot, hasSearched = true) }
                }

                override fun onScanned(count: ULong) = Unit

                override fun onFinished(matched: ULong, cancelled: Boolean) {
                    if (generation.get() != mine) return

                    val ordered = synchronized(accumulatedLock) {
                        accumulated.sortByDescending { it.modifiedMs }
                        accumulated.toList()
                    }
                    _state.update {
                        it.copy(results = ordered, isSearching = false, hasSearched = true)
                    }

                    // Only a walk that ran to completion, and was not cut off
                    // by the cap, holds every match - anything else would make
                    // later narrowing silently drop results.
                    val full = synchronized(accumulatedLock) { accumulated.toList() }
                    cache = if (!cancelled && full.size < CACHE_LIMIT.toInt()) {
                        SearchCache(current.query, current.categories, full)
                    } else {
                        null
                    }
                }
            }

            val filter = SearchFilter(
                query = current.query,
                categories = current.categories.toList(),
                minSize = null,
                maxSize = null,
                modifiedAfter = null,
                includeHidden = false,
                // Collect well beyond what is displayed, so the cache is
                // usable for narrowing rather than being truncated on any
                // common letter.
                limit = CACHE_LIMIT,
            )

            runCatching { repository.searchStreaming(roots, filter, sink, token) }
                .onFailure {
                    if (generation.get() == mine) {
                        _state.update { it.copy(isSearching = false) }
                    }
                }
        }
    }

    private fun cancelSearch() {
        // Bump first: any batch still in flight is then discarded rather than
        // racing the next search's results.
        generation.incrementAndGet()
        cancelToken?.cancel()
        searchJob?.cancel()
    }

    override fun onCleared() {
        cancelSearch()
        super.onCleared()
    }

    // --- Selection and file operations --------------------------------------

    fun toggleSelection(path: String) = _state.update { current ->
        val next = current.selected.toMutableSet()
        if (!next.add(path)) next.remove(path)
        current.copy(selected = next)
    }

    /** Enter selection mode with nothing ticked, from the overflow menu. */
    fun enterSelectionMode() = _state.update { it.copy(selectionActive = true) }

    fun setViewMode(mode: ViewMode) = _state.update { it.copy(viewMode = mode) }

    /** Select everything, or clear it if everything is already selected. */
    fun toggleSelectAll() = _state.update { current ->
        if (current.allSelected) {
            current.copy(selected = emptySet())
        } else {
            current.copy(selected = current.results.map { it.path }.toSet())
        }
    }

    fun clearSelection() =
        _state.update { it.copy(selected = emptySet(), selectionActive = false) }

    /** Copy into the shared clipboard, to be pasted from any folder. */
    fun copySelection() {
        clipboard.copy(_state.value.selected.toList())
        _state.update { it.copy(selected = emptySet(), message = "Copied. Paste in any folder.") }
    }

    fun cutSelection() {
        clipboard.cut(_state.value.selected.toList())
        _state.update { it.copy(selected = emptySet(), message = "Cut. Paste in any folder.") }
    }

    /** Delete goes through the trash, so it is always undoable. */
    fun deleteSelection() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            runCatching { repository.moveToTrash(paths) }
                .onSuccess { removeFromResults(paths, "${paths.size} moved to trash") }
                .onFailure { e ->
                    _state.update { it.copy(message = e.message ?: "Could not delete") }
                }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            val ok = runCatching { repository.rename(path, newName) }.getOrDefault(false)
            if (ok) {
                // The renamed file no longer matches what was searched for, so
                // drop it rather than leaving a row with a stale name.
                removeFromResults(listOf(path), null)
            } else {
                _state.update {
                    it.copy(message = "A file named \"$newName\" already exists")
                }
            }
        }
    }

    /**
     * Drop paths from the visible results and from the cache.
     *
     * Without clearing them from the cache too, narrowing the query would
     * bring deleted files back.
     */
    private fun removeFromResults(paths: List<String>, message: String?) {
        val gone = paths.toSet()
        synchronized(accumulatedLock) { accumulated.removeAll { it.path in gone } }
        cache = cache?.let { c -> c.copy(entries = c.entries.filterNot { it.path in gone }) }
        _state.update { current ->
            current.copy(
                results = current.results.filterNot { it.path in gone },
                selected = emptySet(),
                message = message,
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        /** Only before a disk walk. Narrowing from the cache never waits. */
        const val WALK_DEBOUNCE_MS = 220L

        /**
         * How many matches a walk collects.
         *
         * All of them are displayed - the list is lazy, so rows cost nothing
         * until scrolled to. The cap exists only so a query matching a whole
         * device cannot grow without bound, and it doubles as the point beyond
         * which the cache is considered incomplete.
         */
        const val CACHE_LIMIT: UInt = 20000u
    }
}
