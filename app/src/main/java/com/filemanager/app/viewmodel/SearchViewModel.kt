package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.ViewScope
import com.filemanager.app.data.userMessage
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
    /**
     * Bumped whenever [results] is replaced rather than added to.
     *
     * The screen watches this to put the list back at the top. It is not
     * enough on its own - a lazy list's scroll position is saved and restored
     * across visits, so re-entering a category can land partway down a list
     * that is still being filled - so the screen also holds the list at the
     * top until the reader takes hold of it.
     */
    val resultsEpoch: Int = 0,
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
    private val settings: AppSettings,
    private val roots: List<String>,
) : ViewModel() {

    // Seeded at construction rather than in an init block: properties are
    // initialised in declaration order, and an init block above this line runs
    // before _state exists.
    private val _state = MutableStateFlow(
        SearchState(viewMode = settings.viewMode(ViewScope.Search).value.toViewMode()),
    )
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

    /**
     * A running walk's results, newest first. The cache is built from this.
     *
     * Held in order rather than sorted at the end so the list never re-orders
     * under the reader. Call [mergeNewestFirst] to add to it, never `+=`.
     */
    private val accumulated = mutableListOf<FileEntry>()
    private val accumulatedLock = Any()

    /**
     * Fold one batch into [accumulated], keeping it newest first.
     *
     * Caller must hold [accumulatedLock]; batches arrive on the walk's own
     * threads.
     */
    private fun mergeNewestFirst(batch: List<FileEntry>): List<FileEntry> {
        val incoming = batch.sortedByDescending { it.modifiedMs }
        val merged = ArrayList<FileEntry>(accumulated.size + incoming.size)

        var held = 0
        var new = 0
        while (held < accumulated.size && new < incoming.size) {
            merged += if (accumulated[held].modifiedMs >= incoming[new].modifiedMs) {
                accumulated[held++]
            } else {
                incoming[new++]
            }
        }
        while (held < accumulated.size) merged += accumulated[held++]
        while (new < incoming.size) merged += incoming[new++]

        accumulated.clear()
        accumulated.addAll(merged)
        return merged
    }

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
            // Ticking a chip moves to a different list, which has its own
            // stored layout.
            current.copy(categories = next, viewMode = storedViewMode(next))
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
        val only = setOf(category)
        _state.update { it.copy(categories = only, viewMode = storedViewMode(only)) }
        cache = null
        scheduleWalk()
    }

    fun clear() {
        cancelSearch()
        cache = null
        _state.value = SearchState(viewMode = storedViewMode(emptySet()))
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
            it.copy(
                results = filtered,
                isSearching = false,
                hasSearched = true,
                resultsEpoch = it.resultsEpoch + 1,
            )
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
            _state.update {
                it.copy(
                    isSearching = true,
                    results = emptyList(),
                    resultsEpoch = it.resultsEpoch + 1,
                )
            }

            val sink = object : SearchSink {
                override fun onBatch(entries: List<FileEntry>) {
                    if (generation.get() != mine) return

                    // Merged into date order as it arrives, so the newest file
                    // found so far is always the top row.
                    //
                    // This used to append in walk order and sort the whole
                    // list once at the end. That put an arbitrary file at the
                    // top for the length of the scan and then reshuffled
                    // everything underneath the reader - and because a keyed
                    // lazy list holds its place by following whichever item
                    // was on top, the view was dragged down to wherever that
                    // item had moved to. Opening a category appeared to scroll
                    // itself to the bottom.
                    //
                    // Merging two sorted lists costs a pass over what is
                    // already held, which is what copying it for the UI cost
                    // anyway.
                    val snapshot = synchronized(accumulatedLock) {
                        mergeNewestFirst(entries)
                    }
                    _state.update { it.copy(results = snapshot, hasSearched = true) }
                }

                override fun onScanned(count: ULong) = Unit

                override fun onFinished(matched: ULong, cancelled: Boolean) {
                    if (generation.get() != mine) return

                    // Nothing to re-order: every batch was merged into place,
                    // so what is on screen is already the finished order. The
                    // list simply stops growing.
                    val ordered = synchronized(accumulatedLock) { accumulated.toList() }
                    _state.update {
                        it.copy(
                            results = ordered,
                            isSearching = false,
                            hasSearched = true,
                        )
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

    /**
     * Which stored layout this screen is currently showing.
     *
     * One category is its own list and keeps its own layout; no category, or
     * several at once, is a plain search and shares one.
     */
    private fun scopeFor(categories: Set<FileCategory>): ViewScope =
        categories.singleOrNull()?.let { ViewScope.category(it.name) } ?: ViewScope.Search

    private fun storedViewMode(categories: Set<FileCategory>): ViewMode =
        settings.viewMode(scopeFor(categories)).value.toViewMode()

    fun setViewMode(mode: ViewMode) {
        settings.setViewMode(scopeFor(_state.value.categories), mode.toSetting())
        _state.update { it.copy(viewMode = mode) }
    }

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
                    _state.update { it.copy(message = e.userMessage("Could not delete")) }
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
