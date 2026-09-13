package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

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
            .take(DISPLAY_LIMIT)

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

                    val snapshot = synchronized(accumulatedLock) {
                        accumulated += entries
                        // Newest first. The walk is parallel, so batches turn
                        // up in no useful order and an unsorted list would
                        // visibly reshuffle while the user is reading it.
                        accumulated.sortedByDescending { it.modifiedMs }
                            .take(DISPLAY_LIMIT)
                    }
                    _state.update { it.copy(results = snapshot, hasSearched = true) }
                }

                override fun onScanned(count: ULong) = Unit

                override fun onFinished(matched: ULong, cancelled: Boolean) {
                    if (generation.get() != mine) return
                    _state.update { it.copy(isSearching = false, hasSearched = true) }

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

    private companion object {
        /** Only before a disk walk. Narrowing from the cache never waits. */
        const val WALK_DEBOUNCE_MS = 220L

        /** How many results the list shows. */
        const val DISPLAY_LIMIT = 500

        /**
         * How many a walk collects. Higher than the display limit so that a
         * common letter still produces a cache worth narrowing from, and
         * bounded so a device-wide match cannot grow without limit.
         */
        const val CACHE_LIMIT: UInt = 20000u
    }
}
