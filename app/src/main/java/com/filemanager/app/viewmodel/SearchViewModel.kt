package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
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
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SearchFilter
import uniffi.filemanager_core.SearchPage
import uniffi.filemanager_core.SearchSession
import uniffi.filemanager_core.SearchObserver
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class SearchState(
    val query: String = "",
    /**
     * The one category being shown, or null for everything.
     *
     * One rather than a set: the chips read as filters that add up, but each
     * one opens its own list with its own layout, and two at once had no
     * layout of its own and no heading that described it.
     */
    val category: Category? = null,
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
    /**
     * Everything that matched, which can exceed what [results] holds.
     *
     * Only a page crosses from Rust - more rows than any screen shows, but not
     * the fifty thousand a loose query finds. The screen says so when there are
     * more, rather than quietly pretending the page is all of it.
     */
    val total: Long = 0,
    /**
     * True while [results] is a whole list remembered from an earlier walk of
     * this category, with a fresh walk running underneath.
     *
     * That walk's pages are held back until its last, which replaces the list
     * in one go. Each page used to go straight in: the remembered list was on
     * screen for a fraction of a second, then swapped for the few dozen files
     * the walk had reached so far - so the category looked as though it was
     * scanning from nothing after all.
     */
    val refreshingRemembered: Boolean = false,
) {
    val inSelectionMode: Boolean get() = selectionActive || selected.isNotEmpty()

    /** True when every visible item is ticked, so the control can offer the
     *  opposite action. */
    val allSelected: Boolean
        get() = results.isNotEmpty() && selected.size == results.size

    /** True when the walk found more than the page being shown. */
    val truncated: Boolean get() = total > results.size
}

class SearchViewModel(
    private val repository: FileRepository,
    private val clipboard: FileClipboard,
    private val settings: AppSettings,
    private val roots: List<String>,
    /** What the Downloads category lists, all of it and nothing else. */
    private val downloadsPath: String,
    /** Favourites and pins, which a rename has to carry to the new name. */
    private val paths: PathPrefs,
    /**
     * Shared with the process, not owned by this screen.
     *
     * See FileManagerApp: one was created per screen, each holding the
     * results it found, and they were only released if the view model was
     * cleared and a collector then got round to the object behind it.
     */
    private val session: SearchSession,
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

    /**
     * What the last completed walk searched for.
     *
     * Narrowing is only valid against a superset, so the query the session
     * holds results for has to be remembered. Null when the last walk was
     * cancelled or hit its cap, where what is held is not the whole answer.
     */
    private var searchedFor: SearchCriteria? = null

    private data class SearchCriteria(val query: String, val category: Category?)

    /**
     * Every keystroke searches, as it should - but most keystrokes never
     * touch the disk.
     *
     * Substring matching only ever narrows: a name containing "mad" must also
     * contain "ma", so the results for "mad" are a subset of the results for
     * "ma". Extending the query therefore just filters what is already in
     * hand, which is instant and needs no walk at all. Only a query that is
     * not an extension of the one already searched for - the first character,
     * or deleting past it - has to go to disk.
     */
    fun onQueryChange(query: String) {
        val before = _state.value
        // Whatever is on screen no longer answers the query, remembered or not.
        _state.update { it.copy(query = query, refreshingRemembered = false) }

        // Emptying the box in a category goes back to the category's own list,
        // which is the one thing remembered - so it is shown at once instead of
        // the last query's results while the whole category is walked again.
        val category = before.category
        if (query.isBlank() && before.query.isNotBlank() && category != null) {
            show(category)
            return
        }

        if (narrowInSession(query)) {
            return
        }
        scheduleWalk()
    }

    /** Show this category, or show everything if it is already the one shown. */
    fun toggleCategory(category: Category) {
        show(if (_state.value.category == category) null else category)
    }

    /** Whether [applyCategory] has run for this screen. */
    private var arrived = false

    /**
     * Open on the category whose tile was tapped on the home screen.
     *
     * Once per screen, not once per call. The call comes again whenever the
     * activity is recreated - turning the phone, or a change of theme - and
     * each time it threw the list away and started the walk over, and put
     * back the category if the user had since switched to another.
     */
    fun applyCategory(category: Category) {
        if (arrived) return
        arrived = true
        show(category)
    }

    /**
     * Switch the screen to [next]'s list, or to nothing.
     *
     * What is on screen belongs to the category being left, so it goes at
     * once. Switching used to leave it there under the new heading while the
     * new walk ran - and since what a category remembers was only ever put
     * into an empty screen, it was never shown on a switch at all.
     */
    private fun show(next: Category?) {
        cancelSearch()
        // What the session holds was found for the category being left.
        searchedFor = null
        _state.update {
            it.withResults(emptyList()).copy(
                category = next,
                // Moving to a different list, which has its own stored layout.
                viewMode = storedViewMode(next),
                total = 0,
                hasSearched = false,
                // A selection belongs to the list it was made in.
                selectionActive = false,
                refreshingRemembered = false,
                resultsEpoch = it.resultsEpoch + 1,
            )
        }
        showCached(next, _state.value.query)
        scheduleWalk()
    }

    fun clear() {
        cancelSearch()
        searchedFor = null
        session.clear()
        _state.value = SearchState(viewMode = storedViewMode(null))
    }

    /**
     * Narrow what the session already holds, if the new query only narrows it.
     *
     * Returns false when a real walk is needed. The filtering itself happens in
     * Rust, over results that never left it.
     */
    private fun narrowInSession(query: String): Boolean {
        val held = searchedFor ?: return false
        val current = _state.value

        if (query.isBlank()) return false
        if (held.category != current.category) return false
        // Only an extension is safe. "ma" -> "mad" narrows; "mad" -> "max"
        // or "mad" -> "m" could both match files the walk never looked at.
        if (!query.startsWith(held.query, ignoreCase = true)) return false

        // No walk is running, so nothing can arrive late and overwrite this.
        cancelSearch()

        viewModelScope.launch {
            val page = runCatching { repository.narrowSearch(session, query, PAGE_SIZE) }
                .getOrNull() ?: return@launch
            // The query may have moved on while this was in flight.
            if (_state.value.query != query) return@launch
            _state.update {
                it.withResults(page.entries).copy(
                    total = page.total.toLong(),
                    isSearching = false,
                    hasSearched = true,
                    resultsEpoch = it.resultsEpoch + 1,
                )
            }
        }
        return true
    }

    private fun scheduleWalk() {
        cancelSearch()

        val current = _state.value
        if (current.query.isBlank() && current.category == null) {
            _state.update {
                it.withResults(emptyList()).copy(
                    hasSearched = false,
                    isSearching = false,
                    total = 0,
                )
            }
            searchedFor = null
            session.clear()
            return
        }

        // What the session holds is about to be replaced, so it can no longer
        // answer for the query it was collected under. Without this, deleting
        // a letter to start a new walk and then retyping it would narrow
        // against that walk's half-finished results and show a fraction of
        // the matches as though they were all of them.
        searchedFor = null

        // Marked as searching now, not after the debounce below. Opening a
        // category from the home screen goes straight here with nothing to
        // show, and for those first two hundred milliseconds the screen said
        // "Search by name, or pick a category" - which is what it says when
        // nothing is happening at all.
        //
        // The results are deliberately left alone until the walk actually
        // starts: while someone is still typing, the previous list is better
        // than an empty one.
        _state.update { it.copy(isSearching = true) }

        val mine = generation.incrementAndGet()

        searchJob = viewModelScope.launch {
            // A short pause only before a walk. Narrowing above is instant and
            // never waits; this exists so that typing three characters from
            // empty starts one walk rather than three that cancel each other.
            delay(WALK_DEBOUNCE_MS)

            val token = CancelToken()
            cancelToken = token
            // Cleared only when there is nothing worth keeping. With a
            // cached list already on screen, emptying it here would produce
            // the blank moment the cache exists to remove.
            _state.update { current ->
                if (current.results.isEmpty()) {
                    current.withResults(emptyList()).copy(
                        isSearching = true,
                        total = 0,
                        resultsEpoch = current.resultsEpoch + 1,
                    )
                } else {
                    current.copy(isSearching = true)
                }
            }

            // Pages arrive already ordered newest-first and already capped, so
            // there is nothing to merge, sort or accumulate on this side.
            // Atomic because pages are delivered from the walk's own threads.
            // The last one comes from the calling thread, so a plain var would
            // in practice be read correctly - but relying on that is the kind
            // of reasoning that stops being true when the code moves.
            val everythingHeld = AtomicBoolean(true)
            val firstPage = AtomicBoolean(true)
            val observer = object : SearchObserver {
                override fun onPage(page: SearchPage) {
                    if (generation.get() != mine) return
                    everythingHeld.set(page.complete)
                    // Outside the update, which may run its block more than once.
                    val first = firstPage.getAndSet(false)
                    _state.update {
                        // A whole remembered list stays until this walk has a
                        // whole answer to put in its place. See
                        // refreshingRemembered.
                        if (it.refreshingRemembered && !page.finished) return@update it
                        it.withResults(page.entries).copy(
                            total = page.total.toLong(),
                            hasSearched = true,
                            isSearching = !page.finished,
                            refreshingRemembered = false,
                            // A walk's first page starts a different list - a
                            // new query's - and the rest add to it. One that
                            // replaces a remembered list is that list brought
                            // up to date, and whoever is reading it keeps
                            // their place in it.
                            resultsEpoch = if (first && !it.refreshingRemembered) {
                                it.resultsEpoch + 1
                            } else {
                                it.resultsEpoch
                            },
                        )
                    }
                }
            }

            val filter = SearchFilter(
                query = current.query,
                // Downloads is a folder, not a type: every type, in one place.
                categories = listOfNotNull((current.category as? Category.OfType)?.type),
                minSize = null,
                maxSize = null,
                modifiedAfter = null,
                includeHidden = false,
                // Uncapped: the session holds everything so that narrowing has
                // the whole answer to work from, and only a page of it ever
                // crosses to this side.
                limit = 0u,
            )

            val ran = runCatching {
                repository.runSearch(
                    session = session,
                    roots = rootsFor(current.category),
                    filter = filter,
                    pageSize = PAGE_SIZE,
                    cacheKey = cacheKeyFor(current.category, current.query),
                    observer = observer,
                    cancel = token,
                )
            }

            if (generation.get() != mine) return@launch
            if (ran.isFailure) {
                _state.update { it.copy(isSearching = false) }
                searchedFor = null
                return@launch
            }
            // Only a walk that ran to completion holds every match; anything
            // cut short would make later narrowing silently drop results.
            // Only when the walk finished and the session still holds every
            // match. Past its retention cap the oldest are dropped, and
            // narrowing over what is left would answer from a subset while
            // looking like the whole thing.
            searchedFor = if (token.isCancelled() || !everythingHeld.get()) {
                null
            } else {
                SearchCriteria(current.query, current.category)
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
        // Emptied, not destroyed: the session outlives this screen, but what
        // it found does not need to. Leaving several megabytes of results
        // behind every time a category is closed is what made the app slower
        // the more it was used.
        session.clear()
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
     * Replace the results, dropping ticks for anything no longer among them.
     *
     * The selection used to survive a change of query untouched. Ticking three
     * files, typing another letter, and pressing Delete deleted the three that
     * were no longer on screen - and the count in the title claimed they were.
     *
     * The emptiness check is not only tidiness: this runs on every batch of a
     * walk that can deliver hundreds, and building the set of visible paths
     * costs a pass over everything found so far.
     */
    private fun SearchState.withResults(next: List<FileEntry>): SearchState = copy(
        results = next,
        selected = if (selected.isEmpty()) {
            selected
        } else {
            val visible = next.mapTo(HashSet(next.size)) { it.path }
            selected.filterTo(HashSet()) { it in visible }
        },
    )

    /** Where [category]'s files are: Downloads in one folder, the rest anywhere. */
    private fun rootsFor(category: Category?): List<String> =
        if (category == Category.Downloads) listOf(downloadsPath) else roots

    /**
     * Where a category's last results are filed, or empty for a free-text
     * search.
     *
     * Only categories are remembered. They are a fixed handful and each is
     * opened over and over; typed queries are unbounded in number and mostly
     * typed once.
     */
    private fun cacheKeyFor(category: Category?, query: String): String =
        if (category != null && query.isBlank()) "category:${category.key}" else ""

    /**
     * Show what this category showed last time, while a fresh walk runs.
     *
     * These results are however old the last visit was, which is the trade:
     * the screen starts with files on it instead of a spinner, and corrects
     * itself a moment later. On a full device that spinner was several
     * seconds of every visit to the same category.
     */
    private fun showCached(category: Category?, query: String) {
        val key = cacheKeyFor(category, query)
        if (key.isEmpty()) return

        viewModelScope.launch {
            val page = runCatching { repository.cachedSearch(session, key) }.getOrNull()
                ?: return@launch
            _state.update { current ->
                // Only into the empty screen it was fetched for. The walk may
                // have got there first - it is quicker than this on a small
                // folder - and replacing what it found with what was
                // remembered would be going backwards.
                if (current.hasSearched || current.results.isNotEmpty()) return@update current
                // And only if the screen is still showing what was asked for.
                if (current.category != category || current.query != query) {
                    return@update current
                }
                current.withResults(page.entries).copy(
                    total = page.total.toLong(),
                    hasSearched = true,
                    // A whole list is kept until the walk has a whole one to
                    // replace it with. Part of one, left by a walk the user
                    // did not wait for, only until the walk's first page,
                    // which is already as much and more up to date.
                    refreshingRemembered = page.finished,
                    resultsEpoch = current.resultsEpoch + 1,
                )
            }
        }
    }

    /**
     * Which stored layout this screen is currently showing.
     *
     * A category is its own list and keeps its own layout; no category is a
     * plain search, which keeps one of its own.
     */
    private fun scopeFor(category: Category?): ViewScope =
        category?.let { ViewScope.category(it.key) } ?: ViewScope.Search

    private fun storedViewMode(category: Category?): ViewMode =
        settings.viewMode(scopeFor(category)).value.toViewMode()

    fun setViewMode(mode: ViewMode) {
        settings.setViewMode(scopeFor(_state.value.category), mode.toSetting())
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
        // selectionActive as well as the ticks. Clearing only the ticks left
        // the screen in selection mode with nothing selected, when selection
        // had been entered from the menu.
        _state.update {
            it.copy(
                selected = emptySet(),
                selectionActive = false,
                message = "Copied. Paste in any folder.",
            )
        }
    }

    fun cutSelection() {
        clipboard.cut(_state.value.selected.toList())
        _state.update {
            it.copy(
                selected = emptySet(),
                selectionActive = false,
                message = "Cut. Paste in any folder.",
            )
        }
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
            val newPath = File(File(path).parentFile, newName).absolutePath
            val ok = runCatching { repository.rename(path, newName) }.getOrDefault(false)
            if (!ok) {
                // Only claim a clash when there is one. A rename can also fail
                // for want of permission, and "already exists" sent people
                // looking for a file that was not there.
                val clash = File(newPath).exists()
                _state.update {
                    it.copy(
                        message = if (clash) {
                            "A file named \"$newName\" already exists"
                        } else {
                            "Could not rename \"${File(path).name}\""
                        },
                    )
                }
                return@launch
            }

            paths.move(path, newPath)
            session.forget(listOf(path))

            // Replaced in place rather than dropped. Dropping it assumed a new
            // name no longer matches the search, which is rarely true and never
            // true in a category - renaming a photo from Images made it vanish
            // from the list of images it is still in.
            val renamed = runCatching { repository.entriesFor(listOf(newPath)) }
                .getOrNull()
                ?.firstOrNull()
            val query = _state.value.query
            val kept = renamed?.takeIf {
                query.isBlank() || it.name.contains(query, ignoreCase = true)
            }

            _state.update { current ->
                val results = if (kept != null) {
                    current.results.map { if (it.path == path) kept else it }
                } else {
                    current.results.filterNot { it.path == path }
                }
                current.withResults(results).copy(
                    total = if (kept != null) current.total else (current.total - 1).coerceAtLeast(0),
                )
            }
        }
    }

    /**
     * Drop paths from the visible results and from what the session holds.
     *
     * Both, because the session is what narrowing reads. Without telling it,
     * the next keystroke would filter over the deleted files and put them
     * back on screen.
     */
    private fun removeFromResults(paths: List<String>, message: String?) {
        val gone = paths.toSet()
        session.forget(paths)
        _state.update { current ->
            val remaining = current.results.filterNot { it.path in gone }
            current.copy(
                results = remaining,
                // The total counts what the walk found, so it drops too.
                total = (current.total - (current.results.size - remaining.size)).coerceAtLeast(0),
                selected = emptySet(),
                selectionActive = false,
                message = message,
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        /** Only before a disk walk. Narrowing never waits. */
        const val WALK_DEBOUNCE_MS = 220L

        /**
         * How many results cross from Rust at a time.
         *
         * The walk itself is uncapped - the session keeps everything, so
         * narrowing has the whole answer to work from - but handing all of it
         * over costs about seven microseconds an entry, which on a loose query
         * is most of the time the search takes. A thousand rows is far more
         * than anyone scrolls before typing another letter, and the screen
         * says how many there really are.
         */
        const val PAGE_SIZE: UInt = 1000u
    }
}
