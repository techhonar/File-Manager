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
import uniffi.filemanager_core.ProgressListener
import uniffi.filemanager_core.SearchFilter
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions

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
     * Debounced: a search starts 300 ms after the user stops typing.
     *
     * Without this, every keystroke kicks off a full-device walk and the
     * previous one is thrown away -- which on a large card means the phone
     * gets hot and the results still lag behind the text field.
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
     * Used when the user taps a tile on the home screen -- it replaces rather
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
            _state.update { it.copy(results = emptyList(), hasSearched = false, isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)

            val token = CancelToken()
            cancelToken = token
            _state.update { it.copy(isSearching = true, scanned = 0uL) }

            val progress = object : ProgressListener {
                override fun onProgress(done: ULong, total: ULong, currentPath: String) {
                    _state.update { it.copy(scanned = done) }
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
            val sort = SortOptions(SortKey.MODIFIED, descending = true, dirsFirst = false)

            runCatching { repository.search(roots, filter, sort, progress, token) }
                .onSuccess { results ->
                    _state.update {
                        it.copy(results = results, isSearching = false, hasSearched = true)
                    }
                }
                .onFailure {
                    // Cancellation lands here too; leaving the old results on
                    // screen is better than blanking them mid-typing.
                    _state.update { it.copy(isSearching = false) }
                }
        }
    }

    private fun cancelSearch() {
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
