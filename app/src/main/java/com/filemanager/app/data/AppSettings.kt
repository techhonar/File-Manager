package com.filemanager.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stored counterparts of the view model's ViewMode and the core's SortKey.
 *
 * Kept separate from both so that persistence does not depend on a generated
 * binding's naming, which would silently reset everyone's preference if a
 * variant were ever renamed on the Rust side.
 */
enum class ViewModeSetting { LIST, DETAILED, GRID }

enum class SortKeySetting { NAME, SIZE, MODIFIED, TYPE }

/** Which colour scheme the app should use. */
enum class ThemeMode {
    /** Follow the device setting, including its automatic schedule. */
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Which list a layout preference belongs to.
 *
 * The layout used to be one setting for the whole app, so switching Videos to
 * a grid switched Audio and every folder to a grid as well. The right layout
 * is a property of what is being looked at - thumbnails suit a wall of video,
 * a details list suits music, and neither suits a folder of mixed files - so
 * each list keeps its own.
 *
 * The key is stored, so it has to stay stable: renaming one silently resets
 * that list to the default rather than failing.
 */
@JvmInline
value class ViewScope(val key: String) {
    companion object {
        /** Every folder in the browser, including the storage roots. */
        val Folders = ViewScope("folders")

        /** Search results with no category filter, or more than one. */
        val Search = ViewScope("search")

        /** One category tile - Images, Video, Audio, and so on. */
        fun category(name: String) = ViewScope("category_$name")
    }
}

/**
 * Preferences that outlive the process.
 *
 * SharedPreferences rather than DataStore: this holds one small value read at
 * startup, and pulling in a coroutine-based store for it would be more
 * machinery than the problem deserves.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * One layout per [ViewScope], created the first time it is asked for.
     *
     * A flow per scope rather than one map-valued flow: a screen watching the
     * folder layout should not recompose because a category's changed.
     */
    private val viewModes = mutableMapOf<String, MutableStateFlow<ViewModeSetting>>()

    /**
     * The layout for one list, which nothing else can change.
     *
     * Falls back to whatever the old single setting held, so upgrading keeps
     * the layout everyone had instead of resetting every list to List.
     */
    fun viewMode(scope: ViewScope): StateFlow<ViewModeSetting> = flowFor(scope).asStateFlow()

    fun setViewMode(scope: ViewScope, mode: ViewModeSetting) {
        prefs.edit().putString(viewKey(scope), mode.name).apply()
        flowFor(scope).value = mode
    }

    @Synchronized
    private fun flowFor(scope: ViewScope): MutableStateFlow<ViewModeSetting> =
        viewModes.getOrPut(scope.key) {
            MutableStateFlow(readEnum(viewKey(scope), readEnum(KEY_VIEW, ViewModeSetting.LIST)))
        }

    private fun viewKey(scope: ViewScope) = "${KEY_VIEW}_${scope.key}"

    /**
     * How lists are sorted and filtered, across the whole app.
     *
     * These lived in the browser's per-folder view model, which is created
     * fresh for every folder and discarded on the way out - so choosing a sort
     * order or turning on hidden files lasted exactly as long as that one
     * screen. They are preferences, so they belong here.
     *
     * Unlike the layout above these are not per-list. Sorting by size means
     * the same thing everywhere, and hidden files are one decision about what
     * the user wants to see at all.
     */
    private val _sortKey = MutableStateFlow(readEnum(KEY_SORT, SortKeySetting.NAME))
    val sortKey: StateFlow<SortKeySetting> = _sortKey.asStateFlow()

    private val _sortDescending = MutableStateFlow(prefs.getBoolean(KEY_SORT_DESC, false))
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()

    /** Applies everywhere files are listed, not only in the browser. */
    private val _showHidden = MutableStateFlow(prefs.getBoolean(KEY_SHOW_HIDDEN, false))
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    fun setSort(key: SortKeySetting, descending: Boolean) {
        prefs.edit().putString(KEY_SORT, key.name).putBoolean(KEY_SORT_DESC, descending).apply()
        _sortKey.value = key
        _sortDescending.value = descending
    }

    fun setShowHidden(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HIDDEN, show).apply()
        _showHidden.value = show
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T {
        val stored = prefs.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(fallback)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    /** Falls back to following the system if the stored value is unreadable. */
    private fun readThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        /**
         * Also the prefix for the per-scope keys, and still read on its own as
         * the fallback for a scope that has never been set.
         */
        const val KEY_VIEW = "view_mode"
        const val KEY_SORT = "sort_key"
        const val KEY_SORT_DESC = "sort_descending"
        const val KEY_SHOW_HIDDEN = "show_hidden"
    }
}
