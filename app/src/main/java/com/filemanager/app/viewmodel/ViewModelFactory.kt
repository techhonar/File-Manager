package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import com.filemanager.app.data.StorageVolume

/**
 * Constructs ViewModels that need arguments (a start path, the volume list).
 *
 * Compose's `viewModel()` can only build no-arg ViewModels on its own, so
 * anything with constructor parameters comes through here.
 */
class ViewModelFactory(
    private val repository: FileRepository,
    private val clipboard: FileClipboard,
    private val paths: PathPrefs,
    private val settings: AppSettings,
    private val volumes: List<StorageVolume>,
    private val primaryPath: String,
    private val startPath: String = primaryPath,
    private val highlightPath: String? = null,
    /** See BrowserViewModel: passed in so no ViewModel holds a Context. */
    private val ownerAppOf: suspend (String) -> String? = { null },
    private val hasRemovableSlot: Boolean = false,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(repository, volumes, primaryPath, hasRemovableSlot) as T

        modelClass.isAssignableFrom(BrowserViewModel::class.java) ->
            BrowserViewModel(
                repository, clipboard, paths, settings, startPath, highlightPath, ownerAppOf,
            ) as T

        modelClass.isAssignableFrom(SearchViewModel::class.java) ->
            SearchViewModel(repository, clipboard, settings, volumes.map { it.path }) as T

        modelClass.isAssignableFrom(StorageViewModel::class.java) ->
            StorageViewModel(repository, primaryPath) as T

        modelClass.isAssignableFrom(FavoritesViewModel::class.java) ->
            FavoritesViewModel(repository, paths, settings) as T

        modelClass.isAssignableFrom(RecentViewModel::class.java) ->
            RecentViewModel(repository, clipboard, primaryPath) as T

        modelClass.isAssignableFrom(TrashViewModel::class.java) ->
            TrashViewModel(repository) as T

        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
