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
    /** Files examined so far, for the "scanned 12,480 files" line. */
    val scanned: ULong = 0uL,
    val hasSearched: Boolean = false,
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
     * Which search results belong to.
     *
     * A cancelled walk does not stop instantly - its threads finish the file
     * they are on and may deliver another batch or two. Without this, those
     * late results from the previous query would land in the new query's list.
     */
    private val generation = AtomicLong(0)

    /**
     * Debounced: a search starts 300 ms after the user stops typing.
     *
     * Without it every keystroke kicks off a full-device walk that is then
     * thrown away, so the phone gets hot and the results still lag the text.
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        scheduleSearch()
    }

    fun toggleCategory(category: FileCategory) {
        _state.update { current ->
            val next = current.categories.toMutableSet()
            if (!next.add(category)) next.remove(category)
            current.copy(categories = next)
        }
        scheduleSearch()
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
        scheduleSearch()
    }

    fun clear() {
        cancelSearch()
        _state.value = SearchState()
    }

    private fun scheduleSearch() {
        cancelSearch()

        val current = _state.value
        // Nothing to search for: no text and no category filter.
        if (current.query.isBlank() && current.categories.isEmpty()) {
            _state.update {
                it.copy(results = emptyList(), hasSearched = false, isSearching = false)
            }
            return
        }

        val mine = generation.incrementAndGet()

        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)

            val token = CancelToken()
            cancelToken = token
            _state.update {
                it.copy(isSearching = true, scanned = 0uL, results = emptyList())
            }

            // Results are shown as they are found rather than after the walk
            // finishes, which on a full device is the difference between a
            // list that fills in and a spinner that sits there for a minute.
            val sink = object : SearchSink {
                override fun onBatch(entries: List<FileEntry>) {
                    if (generation.get() != mine) return
                    _state.update { state ->
                        // Kept newest-first as results arrive. The walk is
                        // parallel, so batches turn up in no useful order and
                        // an unsorted list would visibly reshuffle itself.
                        val merged = (state.results + entries)
                            .sortedByDescending { it.modifiedMs }
                            .take(RESULT_LIMIT.toInt())
                        state.copy(results = merged, hasSearched = true)
                    }
                }

                override fun onScanned(count: ULong) {
                    if (generation.get() != mine) return
                    _state.update { it.copy(scanned = count) }
                }

                override fun onFinished(matched: ULong, cancelled: Boolean) {
                    if (generation.get() != mine) return
                    _state.update { it.copy(isSearching = false, hasSearched = true) }
                }
            }

            val filter = SearchFilter(
                query = current.query,
                categories = current.categories.toList(),
                minSize = null,
                maxSize = null,
                modifiedAfter = null,
                includeHidden = false,
                limit = RESULT_LIMIT,
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
        // Bumping the generation first means any batch still in flight is
        // discarded rather than racing the new search's results.
        generation.incrementAndGet()
        cancelToken?.cancel()
        searchJob?.cancel()
    }

    override fun onCleared() {
        cancelSearch()
        super.onCleared()
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val RESULT_LIMIT: UInt = 500u
    }
}
