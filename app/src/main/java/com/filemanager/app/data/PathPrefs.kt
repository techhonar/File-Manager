package com.filemanager.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Paths the user has marked, remembered across restarts.
 *
 * Two sets, both keyed by absolute path. A path is the only identifier a file
 * has here - there is no database and no per-file id - so anything this app
 * renames or moves has to call [move] to carry the marks with it. A rename by
 * another app still loses them; that is the accepted cost of not maintaining
 * an index that would have to be kept in step with the filesystem.
 */
class PathPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("paths", Context.MODE_PRIVATE)

    private val _favoriteOrder = MutableStateFlow(readFavorites())
    private val _favorites = MutableStateFlow<Set<String>>(LinkedHashSet(_favoriteOrder.value))

    /** The favourites, to ask whether a path is one. */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /**
     * The favourites in the user's order: as they were added, unless dragged
     * into another on the favourites screen. A list, because a set that only
     * changed its order is equal to the old one, and a state flow does not
     * pass on a value equal to the last - a drag was never seen.
     */
    val favoriteOrder: StateFlow<List<String>> = _favoriteOrder.asStateFlow()

    private val _pinned = MutableStateFlow(read(KEY_PINNED))
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    /** All of them, or none of them: see [toggle]. New ones go at the end. */
    fun toggleFavorite(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val current = favoriteSet()
        writeFavorites(if (paths.all { it in current }) current - paths.toSet() else current + paths)
    }

    fun togglePinned(paths: Collection<String>) = toggle(KEY_PINNED, _pinned, paths)

    fun isFavorite(path: String): Boolean = path in _favorites.value

    fun isPinned(path: String): Boolean = path in _pinned.value

    /**
     * Put the favourites shown in [shownOrder] - as dragged on the favourites
     * screen. Any not shown there, hidden ones, keep their places.
     */
    fun reorderFavorites(shownOrder: List<String>) {
        writeFavorites(reordered(_favoriteOrder.value, shownOrder))
    }

    /** Forget a path entirely, for when the file behind it is deleted. */
    fun forget(paths: Collection<String>) {
        if (paths.isEmpty()) return
        writeFavorites(favoriteSet() - paths.toSet())
        write(KEY_PINNED, _pinned, _pinned.value - paths.toSet())
    }

    /**
     * Carry every mark at or under [from] across to [to].
     *
     * For a rename or a move. Marks are keyed by path, so renaming a folder
     * used to move only a mark on the folder itself: a favourite photo inside
     * "Trip" kept pointing at "Trip/best.jpg" after the folder became
     * "Trip 2024", and the favourites screen then forgot it as a file that no
     * longer existed. Everything beneath goes with it now.
     *
     * Done as one write per set rather than by toggling, which removed a mark
     * that happened to be at [to] already instead of keeping it.
     */
    fun move(from: String, to: String) {
        rekeyed(favoriteSet(), from, to)?.let(::writeFavorites)
        rekeyed(_pinned.value, from, to)?.let { write(KEY_PINNED, _pinned, it) }
    }

    /**
     * Add all of them, or remove all of them.
     *
     * Deciding from the whole selection rather than per path means a mixed
     * selection resolves one way instead of inverting each item, which is what
     * the user expects from a single button.
     */
    private fun toggle(
        key: String,
        flow: MutableStateFlow<Set<String>>,
        paths: Collection<String>,
    ) {
        if (paths.isEmpty()) return
        val current = flow.value
        val allMarked = paths.all { it in current }
        write(key, flow, if (allMarked) current - paths.toSet() else current + paths)
    }

    private fun write(key: String, flow: MutableStateFlow<Set<String>>, value: Set<String>) {
        // A copy, because SharedPreferences hands back its own instance and
        // mutating it in place is documented as undefined.
        prefs.edit().putStringSet(key, value.toSet()).apply()
        flow.value = value
    }

    /** In order, from the list: the set flow may hold an older order, as said above. */
    private fun favoriteSet(): Set<String> = LinkedHashSet(_favoriteOrder.value)

    private fun writeFavorites(value: Collection<String>) {
        val order = value.toList()
        // A string set keeps no order, so the order is kept beside it. The
        // set is kept as it was, for a version that knows only that.
        prefs.edit()
            .putStringSet(KEY_FAVORITES, order.toSet())
            .putString(KEY_FAVORITES_ORDER, JSONArray(order).toString())
            .apply()
        _favoriteOrder.value = order
        _favorites.value = LinkedHashSet(order)
    }

    private fun read(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun readFavorites(): List<String> {
        val order = runCatching {
            val stored = JSONArray(prefs.getString(KEY_FAVORITES_ORDER, null) ?: "[]")
            (0 until stored.length()).map(stored::getString)
        }.getOrDefault(emptyList())
        return ordered(read(KEY_FAVORITES), order).toList()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_FAVORITES_ORDER = "favorites_order"
        const val KEY_PINNED = "pinned"
    }
}

/**
 * [members] in [order], then any it does not have - marked before there was
 * an order to keep - by name, which is how the favourites screen listed them.
 */
internal fun ordered(members: Set<String>, order: List<String>): Set<String> {
    val result = LinkedHashSet<String>()
    order.filterTo(result) { it in members }
    members.filterNot { it in result }
        .sortedBy { it.substringAfterLast('/').lowercase() }
        .toCollection(result)
    return result
}

/**
 * [all] with the paths in [shown] put in that order, each taking one of the
 * places the shown ones held. The rest - hidden, so not shown - stay put.
 */
internal fun reordered(all: List<String>, shown: List<String>): List<String> {
    val members = all.toSet()
    val wanted = shown.filter { it in members }.distinct()
    val moving = wanted.toSet()
    val next = wanted.iterator()
    return all.map { if (it in moving) next.next() else it }
}

/**
 * [marks] with everything at or under [from] moved to [to], or null if none of
 * them were affected.
 *
 * Separate from [PathPrefs] so it can be tested without a Context. The
 * trailing slash on the prefix is the part that matters: without it, moving
 * "Trip" would also re-key "Trip 2019", which merely starts the same way.
 */
internal fun rekeyed(marks: Set<String>, from: String, to: String): Set<String>? {
    if (from == to) return null
    val prefix = "$from/"
    val moves = { mark: String -> mark == from || mark.startsWith(prefix) }
    if (marks.none(moves)) return null
    // Each in its place: a renamed favourite keeps its place in the order.
    return marks.mapTo(LinkedHashSet()) { if (moves(it)) to + it.removePrefix(from) else it }
}
