package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository

/**
 * Extracting an archive opened from a screen with no extract of its own:
 * recent files, favourites, storage, a download from a server, search's
 * Open. A folder and search keep theirs, to look again at what they show
 * once it is done.
 *
 * One for the activity rather than one per screen, so those screens need
 * nothing of their own for it, and it survives the phone being turned.
 */
class ExtractViewModel(repository: FileRepository, volumeRoots: List<String>) : ViewModel() {
    val extractor = ExtractController(repository, volumeRoots, viewModelScope) {}
}
