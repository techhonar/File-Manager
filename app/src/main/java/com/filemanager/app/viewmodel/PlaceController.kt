package com.filemanager.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.remote.RemoteEntry
import com.filemanager.app.data.remote.RemotePaths
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.transfer.Direction
import com.filemanager.app.data.transfer.Transfer
import com.filemanager.app.data.transfer.TransferCenter
import com.filemanager.app.data.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions
import java.io.File

/**
 * Move or Copy pressed on a selection: what is chosen, and what the screen it
 * was chosen on wants to hear. [onStarted] as the copy begins - the screen
 * leaves selection then, not when the sheet opens, so Cancel on the sheet
 * goes back to the selection. [onDone] once it is over, with each item that
 * left its place: where it is now, or null for one no longer on the phone.
 */
data class PlaceRequest(
    val paths: List<String>,
    val move: Boolean,
    val onStarted: () -> Unit = {},
    val onDone: (moved: List<Pair<String, String?>>) -> Unit = {},
)

/** Where the Select folder sheet is looking: a folder on the phone, or on a server. */
sealed interface PlaceSpot {
    val path: String

    data class Phone(val volume: StorageVolume, override val path: String) : PlaceSpot

    data class Server(val server: RemoteServer, override val path: String) : PlaceSpot
}

/** The Select folder sheet, as it stands. */
data class PlaceSheet(
    /** What is being copied or moved. */
    val sources: List<String>,
    val move: Boolean,
    /** Those of [sources] that are folders: null until that has been looked up. */
    val sourceFolders: Set<String>? = null,
    val spot: PlaceSpot,
    /** What the folder on show holds: folders to go into, files only to see. */
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    /**
     * Whether Copy here or Move here can be pressed. Not while the folder is
     * loading or could not be read; not for a move into the folder all of it
     * is already in, which would change nothing - One UI greys Move here out
     * there too; not into anything being copied or moved itself; and not to
     * a server when only folders are chosen, as only files are uploaded.
     */
    val canPlace: Boolean
        get() = !isLoading && error == null && when (spot) {
            is PlaceSpot.Phone -> !(move && sources.all { it.substringBeforeLast('/') == spot.path }) &&
                sources.none { spot.path == it || spot.path.startsWith("$it/") }
            is PlaceSpot.Server -> sourceFolders != null && sources.any { it !in sourceFolders }
        }

    /** A line under the list, where there is something to say about the choice. */
    val note: String?
        get() = when {
            spot !is PlaceSpot.Server || sourceFolders.isNullOrEmpty() -> null
            sources.all { it in sourceFolders } -> "Only files can go to a server, not folders."
            else -> "Folders are left out: only files go to a server."
        }
}

/** How a copy or move from the sheet went, for the snackbar. */
data class PlaceOutcome(
    val message: String,
    /** The folder on the phone the items went to, for Show in folder; null where there is none to show. */
    val folder: String?,
    /** The first item to arrive, marked when that folder opens. */
    val highlight: String?,
)

/**
 * The Select folder sheet and the copy or move it starts, as One UI has them:
 * Move or Copy on a selection opens the sheet on the folder the files are in;
 * the user walks to where they should go - on the phone, on its card, or on a
 * saved server - and presses Copy here or Move here. The copy then runs in
 * TransferCenter, with a pop-up saying how far it is that can be hidden or
 * used to cancel, and how it ended is said with Show in folder.
 *
 * This replaces Cut, Copy and a Paste button that waited in every folder
 * until the user found the right one. [transfers], [remote] and [servers] are
 * asked for only when needed: each costs something to make, and most copies
 * never go near a server.
 */
class PlaceController(
    private val repository: FileRepository,
    private val volumes: List<StorageVolume>,
    private val transfers: () -> TransferCenter,
    private val remote: () -> RemoteRepository,
    private val servers: () -> List<RemoteServer>,
    private val scope: CoroutineScope,
) {
    private val _sheet = MutableStateFlow<PlaceSheet?>(null)

    /** Null while the sheet is closed. */
    val sheet: StateFlow<PlaceSheet?> = _sheet.asStateFlow()

    private val _serverChoices = MutableStateFlow<List<RemoteServer>>(emptyList())

    /** The saved servers, offered beside the phone's storage. */
    val serverChoices: StateFlow<List<RemoteServer>> = _serverChoices.asStateFlow()

    private val _running = MutableStateFlow<Transfer?>(null)

    /** The copy or move started from the sheet, while it runs. */
    val running: StateFlow<Transfer?> = _running.asStateFlow()

    private val _popupHidden = MutableStateFlow(false)

    /** Hide pop-up pressed: the notification still shows how it goes. */
    val popupHidden: StateFlow<Boolean> = _popupHidden.asStateFlow()

    private val _outcome = MutableStateFlow<PlaceOutcome?>(null)
    val outcome: StateFlow<PlaceOutcome?> = _outcome.asStateFlow()

    /** The screen that asked, while its sheet is open. */
    private var request: PlaceRequest? = null

    /** Open the sheet for [asked], on the folder the first item is in. */
    fun open(asked: PlaceRequest) {
        if (asked.paths.isEmpty()) return
        request = asked
        _serverChoices.value = runCatching(servers).getOrDefault(emptyList())
        val from = asked.paths.first().substringBeforeLast('/')
        val volume = volumeOf(from)
        val spot = when {
            volume != null -> PlaceSpot.Phone(volume, from)
            else -> PlaceSpot.Phone(volumes.firstOrNull() ?: return, volumes.first().path)
        }
        _sheet.value = PlaceSheet(sources = asked.paths, move = asked.move, spot = spot)
        browse(spot)
        // A look at each item on disk: off the main thread, as a selection can
        // be hundreds of files and every look a round trip through FUSE.
        scope.launch {
            val folders = withContext(Dispatchers.IO) { asked.paths.filterTo(HashSet()) { File(it).isDirectory } }
            _sheet.update { current -> if (current?.sources == asked.paths) current.copy(sourceFolders = folders) else current }
        }
    }

    /** The top of [volume]: internal storage, or a card. */
    fun openVolume(volume: StorageVolume) = browse(PlaceSpot.Phone(volume, volume.path))

    /** The folder [server] opens on. */
    fun openServer(server: RemoteServer) =
        browse(PlaceSpot.Server(server, RemotePaths.normalise(server.basePath)))

    /** Into [path]: a folder on show, or one along the breadcrumb. */
    fun enter(path: String) {
        when (val spot = _sheet.value?.spot ?: return) {
            is PlaceSpot.Phone -> browse(spot.copy(path = path))
            is PlaceSpot.Server -> browse(spot.copy(path = path))
        }
    }

    /** A new folder called [name] in the one on show, which is then opened. */
    fun newFolder(name: String) {
        val spot = _sheet.value?.spot ?: return
        scope.launch {
            val made = runCatching {
                when (spot) {
                    is PlaceSpot.Phone -> repository.createFolder(spot.path, name)
                    is PlaceSpot.Server -> {
                        remote().makeDirectory(spot.server, spot.path, name)
                        true
                    }
                }
            }
            if (made.getOrDefault(false)) {
                enter(
                    when (spot) {
                        is PlaceSpot.Phone -> File(spot.path, name).path
                        is PlaceSpot.Server -> RemotePaths.join(spot.path, name)
                    },
                )
            } else {
                _sheet.update {
                    it?.copy(error = made.exceptionOrNull()?.userMessage("Could not make the folder") ?: "Could not make the folder")
                }
            }
        }
    }

    /** Close the sheet without copying anything. */
    fun close() {
        _sheet.value = null
        request = null
    }

    /** Copy here, or Move here: start it, and follow it until it is over. */
    fun confirm() {
        val current = _sheet.value?.takeIf { it.canPlace } ?: return
        val asked = request
        close()
        asked?.onStarted?.invoke()
        val transfer = when (val spot = current.spot) {
            is PlaceSpot.Phone -> transfers().place(current.sources, spot.path, current.move)
            is PlaceSpot.Server -> {
                // Known by now: canPlace waits for it on a server.
                val folders = current.sourceFolders.orEmpty()
                transfers().upload(
                    server = spot.server,
                    files = current.sources.filterNot { it in folders }.map(::File),
                    folder = spot.path,
                    taken = current.entries.mapTo(HashSet()) { it.name },
                    move = current.move,
                    skipped = folders.size,
                )
            }
        }
        follow(transfer, current, asked)
    }

    fun hidePopup() {
        _popupHidden.value = true
    }

    fun cancelRunning() {
        _running.value?.let { transfers().cancel(it.id) }
    }

    fun consumeOutcome() {
        _outcome.value = null
    }

    private fun follow(transfer: StateFlow<Transfer>, sheet: PlaceSheet, asked: PlaceRequest?) {
        _popupHidden.value = false
        scope.launch {
            val last = transfer.onEach { _running.value = it }.first { it.outcome != null }
            _running.value = null
            val outcome = last.outcome ?: return@launch
            // What left its place: on the phone, each to where it went; to a
            // server, the originals the upload moved to the trash.
            val moved: List<Pair<String, String?>> = when {
                last.direction == Direction.MOVE -> outcome.placed.filter { (from, to) -> from != to }
                last.isMove -> withContext(Dispatchers.IO) {
                    sheet.sources.filterNot { File(it).exists() }.map { it to null }
                }
                else -> emptyList()
            }
            asked?.onDone?.invoke(moved)
            val spot = sheet.spot
            _outcome.value = PlaceOutcome(
                message = outcome.summary,
                folder = (spot as? PlaceSpot.Phone)?.path?.takeIf { outcome.succeeded > 0 },
                highlight = outcome.placed.firstOrNull()?.second,
            )
        }
    }

    private fun browse(spot: PlaceSpot) {
        _sheet.update { it?.copy(spot = spot, entries = emptyList(), isLoading = true, error = null) } ?: return
        scope.launch {
            val listed = runCatching {
                when (spot) {
                    is PlaceSpot.Phone -> repository.list(spot.path, showHidden = false, sort = FOLDERS_FIRST)
                    is PlaceSpot.Server -> remote().list(spot.server, spot.path).map { it.asEntry() }
                }
            }
            _sheet.update { now ->
                // A listing that came back after the user moved on is not shown.
                if (now == null || now.spot != spot) return@update now
                listed.fold(
                    onSuccess = { now.copy(entries = it, isLoading = false) },
                    onFailure = { now.copy(isLoading = false, error = it.userMessage("Could not open this folder")) },
                )
            }
        }
    }

    /** The volume [path] is on: the deepest whose folder holds it. */
    private fun volumeOf(path: String): StorageVolume? =
        volumes.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    private companion object {
        val FOLDERS_FIRST = SortOptions(SortKey.NAME, descending = false, dirsFirst = true)
    }
}

/** A server's entry drawn as the phone's are: in the sheet's list, with nothing to show inside. */
private fun RemoteEntry.asEntry() = FileEntry(
    name = name,
    path = path,
    size = size.coerceAtLeast(0L).toULong(),
    isDir = isDir,
    isHidden = name.startsWith('.'),
    modifiedMs = modifiedMs.coerceAtLeast(0L).toULong(),
    category = if (isDir) FileCategory.DIRECTORY else FileCategory.OTHER,
)

/** Holds the PlaceController for the whole app, at its root, over every screen. */
class PlaceViewModel(
    repository: FileRepository,
    volumes: List<StorageVolume>,
    transfers: () -> TransferCenter,
    remote: () -> RemoteRepository,
    servers: () -> List<RemoteServer>,
) : ViewModel() {
    val placer = PlaceController(repository, volumes, transfers, remote, servers, viewModelScope)
}
