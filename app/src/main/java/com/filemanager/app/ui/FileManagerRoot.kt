package com.filemanager.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.filemanager.app.FileManagerApp
import com.filemanager.app.data.StorageVolumes
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.screens.BrowserScreen
import com.filemanager.app.ui.screens.HomeScreen
import com.filemanager.app.ui.screens.PermissionScreen
import com.filemanager.app.ui.screens.SearchScreen
import com.filemanager.app.ui.screens.StorageScreen
import com.filemanager.app.ui.screens.TrashScreen
import com.filemanager.app.viewmodel.BrowserViewModel
import com.filemanager.app.viewmodel.HomeViewModel
import com.filemanager.app.viewmodel.SearchViewModel
import com.filemanager.app.viewmodel.StorageViewModel
import com.filemanager.app.viewmodel.TrashViewModel
import com.filemanager.app.viewmodel.ViewModelFactory
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Navigation destinations.
 *
 * The `*_PATTERN` constants are what NavHost registers and what the bottom
 * bar compares against; the functions below build the concrete route to
 * navigate to. The two differ whenever a destination takes an argument.
 */
private object Routes {
    const val HOME = "home"
    const val BROWSE_PATTERN = "browse/{path}"
    const val SEARCH_PATTERN = "search?category={category}"
    const val STORAGE = "storage"
    const val TRASH = "trash"

    /** Paths contain slashes, so they must be encoded into the route. */
    fun browse(path: String): String =
        "browse/${URLEncoder.encode(path, Charsets.UTF_8.name())}"

    /** Search, optionally pre-filtered to one category. */
    fun search(category: FileCategory? = null): String =
        if (category == null) "search" else "search?category=${category.name}"
}

@Composable
fun FileManagerRoot(
    hasStorageAccess: Boolean,
    onRequestAccess: () -> Unit,
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

    val factory = remember(volumes) {
        ViewModelFactory(app.repository, volumes, primaryPath)
    }

    val openFile: (FileEntry) -> Unit = { entry -> openWithExternalApp(context, entry) }

    Scaffold(
        bottomBar = { BottomBar(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsState()

                OneUiScreen(title = "My Files") { padding ->
                    HomeScreen(
                        state = state,
                        onCategoryClick = { category ->
                            navController.navigate(Routes.search(category))
                        },
                        onVolumeClick = { navController.navigate(Routes.browse(it.path)) },
                        onTrashClick = { navController.navigate(Routes.TRASH) },
                        onFileClick = openFile,
                        modifier = Modifier.padding(padding),
                    )
                }
            }

            composable(Routes.BROWSE_PATTERN) { entry ->
                val encoded = entry.arguments?.getString("path").orEmpty()
                val path = URLDecoder.decode(encoded, Charsets.UTF_8.name())

                // Keyed by path so each folder gets its own ViewModel rather
                // than reusing the previous folder's state.
                val vm: BrowserViewModel = viewModel(
                    key = path,
                    factory = ViewModelFactory(app.repository, volumes, primaryPath, path),
                )
                BrowserScreen(
                    viewModel = vm,
                    onOpenFile = openFile,
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

                // Applied once per arrival, so re-tapping the same tile does
                // not stack filters and the user can still clear it by hand.
                LaunchedEffect(categoryName) {
                    categoryName
                        ?.let { name -> FileCategory.entries.firstOrNull { it.name == name } }
                        ?.let(vm::applyCategory)
                }
                SearchScreen(viewModel = vm, onOpenFile = openFile)
            }

            composable(Routes.STORAGE) {
                val vm: StorageViewModel = viewModel(factory = factory)
                StorageScreen(viewModel = vm, onOpenFile = openFile)
            }

            composable(Routes.TRASH) {
                val vm: TrashViewModel = viewModel(factory = factory)
                TrashScreen(viewModel = vm, onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

/** A bottom-navigation tab: where it goes, and what marks it as current. */
private data class Tab(
    val target: String,
    val pattern: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun BottomBar(navController: NavHostController) {
    val primaryPath = remember { StorageVolumes.primaryPath() }
    val tabs = remember(primaryPath) {
        listOf(
            Tab(Routes.HOME, Routes.HOME, "Home", Icons.Default.Home),
            Tab(Routes.browse(primaryPath), Routes.BROWSE_PATTERN, "Browse", Icons.Default.Folder),
            Tab(Routes.search(), Routes.SEARCH_PATTERN, "Search", Icons.Default.Search),
            Tab(Routes.STORAGE, Routes.STORAGE, "Storage", Icons.Default.PieChart),
        )
    }
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                // Compare against the registered pattern, not the concrete
                // route -- "browse/%2Fstorage%2F..." never equals
                // "browse/{path}", so matching on the target would leave the
                // tab permanently unhighlighted.
                selected = current?.hierarchy?.any { it.route == tab.pattern } == true,
                onClick = {
                    navController.navigate(tab.target) {
                        // Standard bottom-nav behaviour: one entry per tab on
                        // the back stack, and re-tapping a tab does nothing.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}

/**
 * Hand a file to whichever app can open it.
 *
 * Goes through FileProvider because Android blocks file:// URIs across app
 * boundaries -- passing the raw path throws FileUriExposedException.
 */
private fun openWithExternalApp(context: android.content.Context, entry: FileEntry) {
    val file = File(entry.path)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val extension = file.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "*/*"

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing installed handles this type; silently ignoring is better
        // than crashing, and the user sees the file simply not open.
    }
}
