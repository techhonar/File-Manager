package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.userMessage
import com.filemanager.app.data.ftpd.FtpServerConfig
import com.filemanager.app.data.ftpd.FtpServerController
import com.filemanager.app.data.ftpd.FtpServerSettings
import com.filemanager.app.data.ftpd.NetworkAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions
import java.io.File

/**
 * Choosing which folder the server shares.
 *
 * Its own state rather than a path typed into a field: the path has to exist,
 * and a person typing /storage/emulated/0/DCIM by hand gets it wrong more
 * often than not - usually by guessing "sdcard" or a capital letter.
 */
data class FolderPickerState(
    val path: String,
    val folders: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** False at a volume root. Going above one reaches directories the app
     *  cannot read, which would look like an empty folder rather than a wall. */
    val canGoUp: Boolean = false,
    val error: String? = null,
)

data class FtpServerUiState(
    /** What the form currently shows, saved or not. */
    val draft: FtpServerConfig = FtpServerConfig(),
    /** The config the running server was started with, null when stopped. */
    val running: FtpServerConfig? = null,
    /** Local IPv4, or null when there is no network to serve on. */
    val address: String? = null,
    val message: String? = null,
    /** Non-null while the folder picker is open. */
    val picker: FolderPickerState? = null,
) {
    val isRunning: Boolean get() = running != null

    /** What to type into a client, or null when there is nothing to connect to. */
    val url: String?
        get() = running?.let { config ->
            address?.let { "ftp://$it:${config.port}" }
        }

    /** True when the form has been changed away from what is running, so the
     *  screen can say the changes need a restart to take effect. */
    val needsRestart: Boolean get() = running != null && running != draft
}

/**
 * The built-in FTP server's settings and running state.
 *
 * Starting and stopping go through callbacks rather than a Context: the
 * service is started with an Intent, and a ViewModel that held the Context to
 * build one would outlive the screen that gave it.
 */
class FtpServerViewModel(
    private val settings: FtpServerSettings,
    private val controller: FtpServerController,
    /** Only for listing folders in the picker. */
    private val repository: FileRepository,
    /** The storage roots, which bound how far up the picker can go. */
    private val volumes: List<StorageVolume>,
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(
        FtpServerUiState(
            draft = settings.config.value,
            running = controller.running.value,
            address = NetworkAddress.localIpv4(),
        ),
    )
    val state: StateFlow<FtpServerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The server can also be stopped from its notification, so the
            // screen follows the controller rather than assuming its own
            // buttons are the only thing that changes this.
            controller.running.collect { running ->
                _state.update { it.copy(running = running) }
            }
        }
        viewModelScope.launch {
            // Starting happens in the service, so a failure arrives here
            // rather than from the call that asked for it.
            controller.lastError.collect { error ->
                if (error != null) {
                    _state.update { it.copy(message = error) }
                    controller.clearFailure()
                }
            }
        }
    }

    fun updateDraft(config: FtpServerConfig) = _state.update { it.copy(draft = config) }

    fun start() {
        val draft = _state.value.draft
        draft.validate()?.let { problem ->
            _state.update { it.copy(message = problem) }
            return
        }
        if (NetworkAddress.localIpv4() == null) {
            _state.update {
                it.copy(message = "Connect to Wi-Fi first - there is no address to serve on")
            }
            return
        }

        // Saved before starting: the service reads the stored config rather
        // than being handed one, so an unsaved draft would start the old one.
        settings.save(draft)
        _state.update { it.copy(address = NetworkAddress.localIpv4()) }
        startService()
    }

    fun stop() {
        stopService()
    }

    /** Apply edits to an already-running server by cycling it. */
    fun restart() {
        stopService()
        start()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // --- Choosing the folder to share ---------------------------------------

    fun openFolderPicker() {
        // Starts where the server is pointed now, so adjusting one level is
        // one tap rather than a walk down from the root.
        val start = _state.value.draft.rootPath.takeIf { File(it).isDirectory }
            ?: volumes.firstOrNull()?.path
            ?: return
        browseTo(start)
    }

    fun pickerOpen(path: String) = browseTo(path)

    fun pickerUp() {
        val current = _state.value.picker ?: return
        if (!current.canGoUp) return
        browseTo(File(current.path).parent ?: return)
    }

    fun pickerConfirm() = _state.update { current ->
        val chosen = current.picker?.path ?: return@update current
        current.copy(draft = current.draft.copy(rootPath = chosen), picker = null)
    }

    fun pickerCancel() = _state.update { it.copy(picker = null) }

    private fun browseTo(path: String) {
        _state.update {
            it.copy(
                picker = FolderPickerState(
                    path = path,
                    isLoading = true,
                    canGoUp = canGoUp(path),
                ),
            )
        }
        viewModelScope.launch {
            runCatching { repository.list(path, showHidden = false, sort = FOLDER_SORT) }
                .onSuccess { entries ->
                    _state.update { current ->
                        // Ignore a listing that came back after the user moved
                        // on, which over a slow card is not hypothetical.
                        if (current.picker?.path != path) return@update current
                        current.copy(
                            picker = current.picker.copy(
                                // Only folders. This picks somewhere to serve
                                // from, and listing the files as well would
                                // bury the folders in a full camera roll.
                                folders = entries.filter { it.isDir },
                                isLoading = false,
                                error = null,
                            ),
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { current ->
                        if (current.picker?.path != path) return@update current
                        current.copy(
                            picker = current.picker.copy(
                                isLoading = false,
                                // userMessage: this listing comes from the
                                // core, whose errors often have an empty
                                // message - and `?:` catches null, not "".
                                error = failure.userMessage("Could not open that folder"),
                            ),
                        )
                    }
                }
        }
    }

    /** False at a volume root, and anywhere outside one. */
    private fun canGoUp(path: String): Boolean {
        val normalised = File(path).absolutePath
        if (volumes.any { it.path == normalised }) return false
        return volumes.any { normalised.startsWith(it.path + "/") }
    }

    private companion object {
        val FOLDER_SORT = SortOptions(SortKey.NAME, descending = false, dirsFirst = true)
    }
}
