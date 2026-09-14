package com.filemanager.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    }
}
