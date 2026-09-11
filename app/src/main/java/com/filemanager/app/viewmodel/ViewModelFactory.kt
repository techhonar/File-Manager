package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.StorageVolume

/**
 * Constructs ViewModels that need arguments (a start path, the volume list).
 *
 * Compose's `viewModel()` can only build no-arg ViewModels on its own, so
 * anything with constructor parameters comes through here.
 */
class ViewModelFactory(
    private val repository: FileRepository,
    private val volumes: List<StorageVolume>,
    private val primaryPath: String,
    private val startPath: String = primaryPath,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(repository, volumes, primaryPath) as T

        modelClass.isAssignableFrom(BrowserViewModel::class.java) ->
            BrowserViewModel(repository, startPath) as T

        modelClass.isAssignableFrom(SearchViewModel::class.java) ->
            SearchViewModel(repository, volumes.map { it.path }) as T

        modelClass.isAssignableFrom(StorageViewModel::class.java) ->
            StorageViewModel(repository, primaryPath) as T

        modelClass.isAssignableFrom(TrashViewModel::class.java) ->
            TrashViewModel(repository) as T

        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
