package com.filemanager.app.viewmodel

import uniffi.filemanager_core.FileCategory

/**
 * One of the lists the home screen's Categories grid opens.
 *
 * Mostly a type of file. Downloads is a place instead, but it is opened for the
 * same reason and wanted the same way - every file in it, newest first,
 * wherever inside the folder it landed - so it is one of these too. It used to
 * be a shortcut into the browser, which showed the folder's subfolders rather
 * than what had been downloaded.
 */
sealed interface Category {

    /**
     * Stable: it goes into routes and into the keys that remember each list's
     * layout and last results. A type's is its name, which is what those keys
     * held before Downloads was a category, so nothing stored is lost.
     */
    val key: String

    data class OfType(val type: FileCategory) : Category {
        override val key: String get() = type.name
    }

    data object Downloads : Category {
        override val key: String get() = "DOWNLOADS"
    }

    companion object {
        /** Null for a key nothing answers to, such as a type since removed. */
        fun fromKey(key: String): Category? =
            if (key == Downloads.key) {
                Downloads
            } else {
                FileCategory.entries.firstOrNull { it.name == key }?.let(::OfType)
            }
    }
}
