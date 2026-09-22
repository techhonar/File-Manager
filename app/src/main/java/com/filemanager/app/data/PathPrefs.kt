package com.filemanager.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _favorites = MutableStateFlow(read(KEY_FAVORITES))
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _pinned = MutableStateFlow(read(KEY_PINNED))
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    fun toggleFavorite(paths: Collection<String>) = toggle(KEY_FAVORITES, _favorites, paths)

    fun togglePinned(paths: Collection<String>) = toggle(KEY_PINNED, _pinned, paths)

    fun isFavorite(path: String): Boolean = path in _favorites.value

    fun isPinned(path: String): Boolean = path in _pinned.value

    /** Forget a path entirely, for when the file behind it is deleted. */
    fun forget(paths: Collection<String>) {
        if (paths.isEmpty()) return
        write(KEY_FAVORITES, _favorites, _favorites.value - paths.toSet())
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
        rekeyed(_favorites.value, from, to)?.let { write(KEY_FAVORITES, _favorites, it) }
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

    private fun read(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_PINNED = "pinned"
    }
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
    val affected = marks.filter { it == from || it.startsWith(prefix) }
    if (affected.isEmpty()) return null
    return (marks - affected.toSet()) + affected.map { to + it.removePrefix(from) }
}
