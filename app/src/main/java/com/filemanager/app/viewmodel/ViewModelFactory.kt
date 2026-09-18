package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.filemanager.app.data.FileClipboard
import com.filemanager.app.data.AppSettings
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.PathPrefs
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.ftpd.FtpServerController
import com.filemanager.app.data.ftpd.FtpServerSettings
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.remote.RemoteServers
import uniffi.filemanager_core.SearchSession

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
    /** Null on screens that have nothing to do with network storage, which is
     *  most of them - constructing the clients costs a socket. */
    /** The process-wide search session; see FileManagerApp. */
    private val searchSession: SearchSession? = null,
    private val remoteServers: RemoteServers? = null,
    private val remoteRepository: RemoteRepository? = null,
    /** Which server the remote browser should open. */
    private val remoteServer: RemoteServer? = null,
    private val ftpSettings: FtpServerSettings? = null,
    private val ftpController: FtpServerController? = null,
    /** See FtpServerViewModel: the service is started with an Intent, and a
     *  ViewModel holding the Context to build one would outlive its screen. */
    private val startFtpService: () -> Unit = {},
    private val stopFtpService: () -> Unit = {},
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
            SearchViewModel(
                repository = repository,
                clipboard = clipboard,
                settings = settings,
                roots = volumes.map { it.path },
                session = requireNotNull(searchSession) { "searchSession not supplied" },
            ) as T

        modelClass.isAssignableFrom(StorageViewModel::class.java) ->
            StorageViewModel(repository, primaryPath) as T

        modelClass.isAssignableFrom(FavoritesViewModel::class.java) ->
            FavoritesViewModel(repository, paths, settings) as T

        modelClass.isAssignableFrom(RecentViewModel::class.java) ->
            RecentViewModel(repository, clipboard, primaryPath) as T

        modelClass.isAssignableFrom(TrashViewModel::class.java) ->
            TrashViewModel(repository) as T

        modelClass.isAssignableFrom(RemoteServersViewModel::class.java) ->
            RemoteServersViewModel(
                servers = requireNotNull(remoteServers) { "remoteServers not supplied" },
                repository = requireNotNull(remoteRepository) { "remoteRepository not supplied" },
            ) as T

        modelClass.isAssignableFrom(RemoteBrowserViewModel::class.java) ->
            RemoteBrowserViewModel(
                server = requireNotNull(remoteServer) { "remoteServer not supplied" },
                repository = requireNotNull(remoteRepository) { "remoteRepository not supplied" },
                clipboard = clipboard,
                localFiles = repository,
            ) as T

        modelClass.isAssignableFrom(FtpServerViewModel::class.java) ->
            FtpServerViewModel(
                settings = requireNotNull(ftpSettings) { "ftpSettings not supplied" },
                controller = requireNotNull(ftpController) { "ftpController not supplied" },
                repository = repository,
                volumes = volumes,
                startService = startFtpService,
                stopService = stopFtpService,
            ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
