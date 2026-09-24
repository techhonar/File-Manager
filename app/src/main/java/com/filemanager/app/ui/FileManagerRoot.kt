package com.filemanager.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.filemanager.app.FileManagerApp
import com.filemanager.app.MainActivity
import com.filemanager.app.data.ftpd.FtpService
import com.filemanager.app.data.external.Incoming
import com.filemanager.app.data.external.accepts
import com.filemanager.app.data.external.mimeTypeOf
import com.filemanager.app.data.install.isBundle
import com.filemanager.app.data.viewer.viewerKind
import com.filemanager.app.data.update.AppUpdater
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.ui.screens.FtpServerScreen
import com.filemanager.app.ui.screens.RemoteBrowserScreen
import com.filemanager.app.ui.screens.RemoteServersScreen
import com.filemanager.app.viewmodel.FtpServerViewModel
import com.filemanager.app.viewmodel.RemoteBrowserViewModel
import com.filemanager.app.viewmodel.RemoteServersViewModel
import androidx.compose.runtime.rememberCoroutineScope
import com.filemanager.app.data.MediaOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.filemanager.app.data.ShareFiles
import com.filemanager.app.data.StorageVolumes
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.screens.AboutScreen
import com.filemanager.app.ui.screens.BrowserScreen
import com.filemanager.app.ui.screens.HomeScreen
import com.filemanager.app.ui.screens.PermissionScreen
import com.filemanager.app.ui.screens.RecentScreen
import com.filemanager.app.ui.screens.SearchScreen
import com.filemanager.app.ui.screens.StorageScreen
import com.filemanager.app.ui.screens.ThemeScreen
import com.filemanager.app.ui.screens.TrashScreen
import com.filemanager.app.ui.screens.ViewerScreen
import com.filemanager.app.viewmodel.BrowserViewModel
import com.filemanager.app.ui.screens.FavoritesScreen
import com.filemanager.app.viewmodel.FavoritesViewModel
import com.filemanager.app.viewmodel.HomeViewModel
import com.filemanager.app.ui.components.UpdateDialog
import com.filemanager.app.viewmodel.RecentViewModel
import com.filemanager.app.viewmodel.SearchViewModel
import com.filemanager.app.viewmodel.StorageViewModel
import com.filemanager.app.viewmodel.TrashViewModel
import com.filemanager.app.viewmodel.ViewModelFactory
import java.io.File
import com.filemanager.app.viewmodel.Category
import uniffi.filemanager_core.FileEntry

/**
 * Navigation destinations.
 *
 * The `*_PATTERN` constants are what NavHost registers and what the bottom
 * bar compares against; the functions below build the concrete route to
 * navigate to. The two differ whenever a destination takes an argument.
 */
private object Routes {
    const val HOME = "home"
    const val BROWSE_PATTERN = "browse/{path}?highlight={highlight}"
    const val SEARCH_PATTERN = "search?category={category}"
    const val STORAGE = "storage"
    const val TRASH = "trash"
    const val RECENT = "recent"
    const val ABOUT = "about"
    const val THEME = "theme"
    const val FAVORITES = "favorites"
    const val NETWORK = "network"
    const val FTP_SERVER = "ftpserver"
    const val REMOTE_PATTERN = "remote/{serverId}"
    const val VIEWER_PATTERN = "viewer/{path}"

    /** Ids are UUIDs, so they need no encoding. */
    fun remote(serverId: String): String = "remote/$serverId"

    /**
     * Paths contain slashes, so they must be encoded into the route.
     *
     * Encoded here and never decoded by hand: Navigation decodes an argument
     * itself before handing it over. Decoding it a second time turned a "+"
     * in a folder's name into a space, so "C++" could not be opened, and a
     * "%" - "50% off" - into a broken escape that threw and closed the app.
     * Uri.encode rather than URLEncoder, which writes a space as a "+" that
     * Navigation's decoding leaves alone.
     */
    fun browse(path: String, highlight: String? = null): String {
        val encoded = Uri.encode(path)
        // Show-in-folder passes the file so the browser can scroll to it;
        // opening a folder normally passes nothing.
        return if (highlight == null) {
            "browse/$encoded"
        } else {
            "browse/$encoded?highlight=${Uri.encode(highlight)}"
        }
    }

    /** The viewer, for the file at [path]. Encoded as [browse] explains. */
    fun viewer(path: String): String = "viewer/${Uri.encode(path)}"

    /** Search, optionally pre-filtered to one category. */
    fun search(category: Category? = null): String =
        if (category == null) "search" else "search?category=${category.key}"
}

/**
 * The app. [openFolder] is a folder another app asked to have shown, handed
 * back through [onFolderOpened] once it is; with [picking], another app is
 * waiting for a file, and tapping one gives it to [onPick].
 */
@Composable
fun FileManagerRoot(
    hasStorageAccess: Boolean,
    onRequestAccess: () -> Unit,
    openFolder: String? = null,
    onFolderOpened: () -> Unit = {},
    picking: Incoming.Pick? = null,
    onPick: (String) -> Unit = {},
    onCancelPick: () -> Unit = {},
) {
    // Nothing in this app works without all-files access, so gate the whole
    // tree rather than handling a denied permission in every screen.
    if (!hasStorageAccess) {
        PermissionScreen(onRequestAccess = onRequestAccess)
        return
    }

    val context = LocalContext.current
    val app = context.applicationContext as FileManagerApp
    val volumes = remember { StorageVolumes.list(context) }
    val primaryPath = remember { StorageVolumes.primaryPath() }
    val navController = rememberNavController()

    val ownerAppOf: suspend (String) -> String? = remember(context) {
        { path -> withContext(Dispatchers.IO) { MediaOwner.ownerAppLabel(context, path) } }
    }
    // Whether the device has a card slot at all, as opposed to an empty one.
    val hasRemovableSlot = remember(context) { StorageVolumes.hasRemovableSlot(context) }

    // Named arguments throughout: this now takes eight parameters, several of
    // them adjacent strings, and a positional call would be one transposition
    // away from wiring the wrong folder to the wrong screen.
    // Where Download in the remote browser puts things. The public Downloads
    // folder rather than somewhere app-private, so what comes off a server is
    // reachable from every other app on the phone.
    val downloadDirectory = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .apply { mkdirs() }
    }

    // Watched rather than read once: adding a server from the Network screen
    // has to show up on the home screen behind it.
    val remoteServers by app.remoteServers.servers.collectAsState()

    // One per process, not per screen: it holds the connection pool, and a
    // second instance would open its own sockets to the same servers.
    val remoteRepository = remember(context) {
        RemoteRepository(app.remoteConnections, context.cacheDir)
    }

    val factory = remember(volumes, ownerAppOf, hasRemovableSlot, remoteRepository) {
        ViewModelFactory(
            repository = app.repository,
            clipboard = app.clipboard,
            paths = app.paths,
            settings = app.settings,
            volumes = volumes,
            primaryPath = primaryPath,
            ownerAppOf = ownerAppOf,
            hasRemovableSlot = hasRemovableSlot,
            searchSession = app.searchSession,
            remoteServers = app.remoteServers,
            remoteRepository = remoteRepository,
            ftpSettings = app.ftpSettings,
            ftpController = app.ftpServer,
            startFtpService = { FtpService.start(context) },
            stopFtpService = { FtpService.stop(context) },
        )
    }

    // A split-APK bundle is installed here rather than handed on: no app on
    // the phone opens one, so handing it on did nothing at all.
    // Text, PDFs and Word documents open in the app's own viewer, which can
    // still hand them to another app.
    val openPath: (String) -> Unit = { path ->
        when {
            // Choosing for another app: a tap sends the file there instead.
            picking != null ->
                if (accepts(picking.types, mimeTypeOf(path.substringAfterLast('/')))) {
                    onPick(path)
                } else {
                    toast(context, "The app you're choosing for can't take this kind of file")
                }
            isBundle(path) -> app.bundleInstaller.install(path)
            viewerKind(path.substringAfterLast('/')) != null -> navController.navigate(Routes.viewer(path))
            else -> openWithExternalApp(context, path)
        }
    }
    val openFile: (FileEntry) -> Unit = { entry -> openPath(entry.path) }

    /**
     * Always shows the chooser, where opening normally goes straight to the
     * user's default. That is the whole point of "open with".
     */
    val openWith: (String) -> Unit = { path ->
        if (!openWithExternalApp(context, path, forceChooser = true)) {
            toast(context, "No app can open this file")
        }
    }

    val copyPath: (String) -> Unit = { path ->
        val clip = context.getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(ClipData.newPlainText("Path", path))
        // Android 13 and up shows its own copy confirmation, so a second one
        // here would be duplicate noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(context, "Path copied")
        }
    }
    val scope = rememberCoroutineScope()

    /**
     * Share files, expanding any folder into the files it contains.
     *
     * Not zipped: the recipient wants the photos, not an archive to unpack.
     * Android cannot send a directory as a stream, so the folder is walked and
     * its files are sent individually - which is also what Quick Share expects.
     */
    val shareFiles: (List<String>) -> Unit = { paths ->
        scope.launch {
            val expanded = runCatching { app.repository.expandForSharing(paths) }
                .getOrDefault(emptyList())

            when (val result = ShareFiles.share(context, expanded)) {
                is ShareFiles.Result.Sent -> Unit
                is ShareFiles.Result.NoFiles ->
                    toast(context, "Nothing to share here")
                is ShareFiles.Result.TooMany ->
                    toast(
                        context,
                        "Too many files to share at once (${result.count}). " +
                            "Android caps a single share at about ${ShareFiles.MAX_FILES}.",
                    )
                is ShareFiles.Result.NoApp ->
                    toast(context, "No app can receive these files")
            }
        }
    }

    // Where a bundle's game data was left, and what to do with it - more than
    // a toast can hold, and it may arrive after the user has moved on.
    val installNotice by app.bundleInstaller.notice.collectAsState()
    installNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = app.bundleInstaller::dismissNotice,
            confirmButton = {
                TextButton(onClick = app.bundleInstaller::dismissNotice) { Text("OK") }
            },
            title = { Text("Game data") },
            text = { Text(notice) },
        )
    }

    // Update App. The installer is opened from here rather than from the
    // updater: Android lets an app start another app's screen only while it
    // is in front, so a download that finishes while the user is elsewhere
    // waits until they come back.
    val updateState by app.updater.state.collectAsState()
    LaunchedEffect(updateState) {
        val ready = updateState as? AppUpdater.State.Ready ?: return@LaunchedEffect
        runCatching { context.startActivity(app.updater.installIntent(ready.apk)) }
            .onFailure { toast(context, "Could not open the installer") }
        app.updater.dismiss()
    }
    UpdateDialog(
        state = updateState,
        onCancel = app.updater::cancel,
        onDismiss = app.updater::dismiss,
        onRetry = app.updater::update,
    )

    LaunchedEffect(openFolder) {
        if (openFolder != null) {
            navController.navigate(Routes.browse(openFolder))
            onFolderOpened()
        }
    }

    // Measured, so the screens can end above it.
    var pickBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                // The pick bar lies over the bottom, navigation bar included.
                .then(
                    if (picking == null) {
                        Modifier
                    } else {
                        Modifier
                            .padding(bottom = pickBarHeight)
                            .consumeWindowInsets(WindowInsets.navigationBars)
                    },
                ),
            // Both screens move together, the way One UI does it: the new one
            // slides in from the side it belongs on while the old one gives
            // way a little in the same direction, each fading as it goes.
            enterTransition = {
                slideInHorizontally(ScreenMotion) { it / 5 } + fadeIn(ScreenFadeIn)
            },
            exitTransition = {
                slideOutHorizontally(ScreenMotion) { -it / 10 } + fadeOut(ScreenFadeOut)
            },
            popEnterTransition = {
                slideInHorizontally(ScreenMotion) { -it / 10 } + fadeIn(ScreenFadeIn)
            },
            popExitTransition = {
                slideOutHorizontally(ScreenMotion) { it / 5 } + fadeOut(ScreenFadeOut)
            },
        ) {
            composable(Routes.HOME) { entry ->
                val vm: HomeViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsState()

                // Refreshed each time Home comes back into view. The view model
                // outlives trips to other screens, and it loaded once, when it
                // was made - so after deleting things and coming back, Trash
                // and the storage gauges still showed the old figures, until
                // the app was restarted. Tied to the navigation entry's
                // lifecycle, so returning from another app counts too.
                DisposableEffect(entry) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
                    }
                    entry.lifecycle.addObserver(observer)
                    onDispose { entry.lifecycle.removeObserver(observer) }
                }

                OneUiScreen(
                    title = "My Files",
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.search()) }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        HomeOverflowMenu(
                            onManageStorage = { navController.navigate(Routes.STORAGE) },
                            onTrash = { navController.navigate(Routes.TRASH) },
                            onTheme = { navController.navigate(Routes.THEME) },
                            onUpdate = app.updater::update,
                            onAbout = { navController.navigate(Routes.ABOUT) },
                        )
                    },
                ) { padding ->
                    HomeScreen(
                        state = state,
                        onCategoryClick = { category ->
                            navController.navigate(Routes.search(category))
                        },
                        onRecentClick = { navController.navigate(Routes.RECENT) },
                        onVolumeClick = { navController.navigate(Routes.browse(it.path)) },
                        onTrashClick = { navController.navigate(Routes.TRASH) },
                    onFavoritesClick = { navController.navigate(Routes.FAVORITES) },
                        onManageStorageClick = { navController.navigate(Routes.STORAGE) },
                        remoteServers = remoteServers,
                        onRemoteServerClick = { server ->
                            navController.navigate(Routes.remote(server.id))
                        },
                        onManageNetworkClick = { navController.navigate(Routes.NETWORK) },
                        onFtpServerClick = { navController.navigate(Routes.FTP_SERVER) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            composable(Routes.RECENT) {
            val vm: RecentViewModel = viewModel(factory = factory)
            RecentScreen(
                viewModel = vm,
                onOpenFile = openFile,
                onShare = shareFiles,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
                route = Routes.BROWSE_PATTERN,
                arguments = listOf(
                    navArgument("highlight") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                // Already decoded by Navigation; see Routes.browse.
                val path = entry.arguments?.getString("path").orEmpty()
                val highlight = entry.arguments?.getString("highlight")

                // Keyed by path so each folder gets its own ViewModel rather
                // than reusing the previous folder's state.
                val vm: BrowserViewModel = viewModel(
                    key = path,
                    factory = ViewModelFactory(
                        repository = app.repository,
                        clipboard = app.clipboard,
                        paths = app.paths,
                        settings = app.settings,
                        volumes = volumes,
                        primaryPath = primaryPath,
                        startPath = path,
                        highlightPath = highlight,
                        ownerAppOf = ownerAppOf,
                        hasRemovableSlot = hasRemovableSlot,
                    ),
                )
                BrowserScreen(
                    viewModel = vm,
                    onOpenFile = openFile,
                    onShare = shareFiles,
                    onOpenWith = openWith,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.SEARCH_PATTERN,
                arguments = listOf(
                    navArgument("category") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val vm: SearchViewModel = viewModel(factory = factory)
                val categoryName = entry.arguments?.getString("category")

                // Handed over on every composition of this screen, including
                // the one after the activity is recreated; the view model
                // applies it only the first time. See applyCategory.
                LaunchedEffect(categoryName) {
                    categoryName?.let(Category::fromKey)?.let(vm::applyCategory)
                }
                SearchScreen(
                    viewModel = vm,
                    onOpenFile = openFile,
                    onShare = shareFiles,
                    onOpenWith = openWith,
                    onCopyPath = copyPath,
                    onShowInFolder = { path ->
                        navController.navigate(
                            Routes.browse(path.substringBeforeLast('/'), highlight = path),
                        )
                    },
                    // Arriving from a category tile means the user wants to see
                    // that category, not to type - so no keyboard.
                    autoFocus = categoryName == null,
                )
            }

            composable(Routes.STORAGE) {
                val vm: StorageViewModel = viewModel(factory = factory)
                StorageScreen(viewModel = vm, onOpenFile = openFile, onShare = shareFiles)
            }

            composable(Routes.FAVORITES) {
            val vm: FavoritesViewModel = viewModel(factory = factory)
            FavoritesScreen(
                viewModel = vm,
                onOpenFile = openFile,
                onShowInFolder = { path ->
                    navController.navigate(
                            Routes.browse(path.substringBeforeLast('/'), highlight = path),
                        )
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

            composable(Routes.NETWORK) {
                val vm: RemoteServersViewModel = viewModel(factory = factory)
                RemoteServersScreen(
                    viewModel = vm,
                    onOpenServer = { server -> navController.navigate(Routes.remote(server.id)) },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Routes.FTP_SERVER) {
                val vm: FtpServerViewModel = viewModel(factory = factory)
                FtpServerScreen(
                    viewModel = vm,
                    volumes = volumes,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Routes.REMOTE_PATTERN) { entry ->
                val serverId = entry.arguments?.getString("serverId").orEmpty()
                val server = app.remoteServers.byId(serverId)

                if (server == null) {
                    // Deleted while its screen was still on the back stack.
                    LaunchedEffect(serverId) { navController.popBackStack() }
                } else {
                    // Keyed by id so each server gets its own ViewModel rather
                    // than reusing the previous one's folder and selection.
                    val vm: RemoteBrowserViewModel = viewModel(
                        key = serverId,
                        factory = ViewModelFactory(
                            repository = app.repository,
                            clipboard = app.clipboard,
                            paths = app.paths,
                            settings = app.settings,
                            volumes = volumes,
                            primaryPath = primaryPath,
                            remoteRepository = remoteRepository,
                            remoteServer = server,
                        ),
                    )
                    RemoteBrowserScreen(
                        viewModel = vm,
                        onOpenDownloaded = { file -> openPath(file.absolutePath) },
                        downloadDirectory = downloadDirectory,
                        onNavigateBack = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.THEME) {
                val theme by app.settings.theme.collectAsState()
                ThemeScreen(
                    theme = theme,
                    settings = app.settings,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

        composable(Routes.ABOUT) {
                AboutScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(Routes.VIEWER_PATTERN) { entry ->
                // Already decoded by Navigation; see Routes.browse.
                ViewerScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onOpenWith = openWith,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Routes.TRASH) {
                val vm: TrashViewModel = viewModel(factory = factory)
                TrashScreen(viewModel = vm, onNavigateBack = { navController.popBackStack() })
            }
        }

        if (picking != null) {
            PickBar(
                onCancel = onCancelPick,
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentHeight(Alignment.Bottom)
                    .onSizeChanged { pickBarHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

/** Says a file is being chosen for another app, and lets the choosing be called off. */
@Composable
private fun PickBar(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tap a file to send it",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** How far and how fast screens move when one replaces another. */
private val ScreenMotion = tween<IntOffset>(durationMillis = 340, easing = FastOutSlowInEasing)
private val ScreenFadeIn = tween<Float>(durationMillis = 260, delayMillis = 60, easing = LinearOutSlowInEasing)
private val ScreenFadeOut = tween<Float>(durationMillis = 180, easing = FastOutLinearInEasing)

/** The home screen's overflow menu: what has no tile of its own. */
@Composable
private fun HomeOverflowMenu(
    onManageStorage: () -> Unit,
    onTrash: () -> Unit,
    onTheme: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, "More options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Manage storage") },
            onClick = { onManageStorage(); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("Trash") },
            onClick = { onTrash(); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("Theme") },
            onClick = { onTheme(); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("Update App") },
            onClick = { onUpdate(); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("About") },
            onClick = { onAbout(); expanded = false },
        )
    }
}

/**
 * Hand a file to whichever app can open it.
 *
 * Goes through FileProvider because Android blocks file:// URIs across app
 * boundaries - passing the raw path throws FileUriExposedException.
 *
 * With [forceChooser] the picker is always shown; without it the user's
 * default opens directly, which is what tapping a file should do.
 */
internal fun openWithExternalApp(
    context: android.content.Context,
    path: String,
    forceChooser: Boolean = false,
): Boolean {
    val file = File(path)
    if (!file.isFile) return false

    // Guarded, because FileProvider throws for a file outside its roots and an
    // uncaught throw here takes the whole app down. The roots now cover every
    // mount, so this should not fire; if a device mounts storage somewhere
    // unexpected, the file fails to open instead of the app closing.
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrElse { return false }
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val intent = if (forceChooser) {
        Intent.createChooser(view, "Open with")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // This app opens these types too now; offering it back to itself
            // would only reopen the file where it already is.
            .putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, MainActivity::class.java)),
            )
    } else {
        view
    }

    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        // Nothing installed handles this type. Silent for a plain tap; the
        // caller decides whether to say so.
        false
    }
}

/** Brief message for an action that could not proceed. */
private fun toast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}
