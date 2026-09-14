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
     * How lists are laid out, and how they are sorted and filtered.
     *
     * These lived in the browser's per-folder view model, which is created
     * fresh for every folder and discarded on the way out - so choosing
     * Details or turning on hidden files lasted exactly as long as that one
     * screen. They are preferences, so they belong here.
     */
    private val _viewMode = MutableStateFlow(readEnum(KEY_VIEW, ViewModeSetting.LIST))
    val viewMode: StateFlow<ViewModeSetting> = _viewMode.asStateFlow()

    private val _sortKey = MutableStateFlow(readEnum(KEY_SORT, SortKeySetting.NAME))
    val sortKey: StateFlow<SortKeySetting> = _sortKey.asStateFlow()

    private val _sortDescending = MutableStateFlow(prefs.getBoolean(KEY_SORT_DESC, false))
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()

    /** Applies everywhere files are listed, not only in the browser. */
    private val _showHidden = MutableStateFlow(prefs.getBoolean(KEY_SHOW_HIDDEN, false))
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    fun setViewMode(mode: ViewModeSetting) {
        prefs.edit().putString(KEY_VIEW, mode.name).apply()
        _viewMode.value = mode
    }

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
        const val KEY_VIEW = "view_mode"
        const val KEY_SORT = "sort_key"
        const val KEY_SORT_DESC = "sort_descending"
        const val KEY_SHOW_HIDDEN = "show_hidden"
    }
}
