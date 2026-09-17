package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.ftpd.FtpServerConfig
import com.filemanager.app.data.ftpd.FtpServerController
import com.filemanager.app.data.ftpd.FtpServerSettings
import com.filemanager.app.data.ftpd.NetworkAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FtpServerUiState(
    /** What the form currently shows, saved or not. */
    val draft: FtpServerConfig = FtpServerConfig(),
    /** The config the running server was started with, null when stopped. */
    val running: FtpServerConfig? = null,
    /** Local IPv4, or null when there is no network to serve on. */
    val address: String? = null,
    val message: String? = null,
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
}
