package com.filemanager.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.filemanager.app.data.StorageVolumes
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.screens.BrowserScreen
import com.filemanager.app.ui.screens.HomeScreen
import com.filemanager.app.ui.screens.PermissionScreen
import com.filemanager.app.ui.screens.RecentScreen
import com.filemanager.app.ui.screens.SearchScreen
import com.filemanager.app.ui.screens.StorageScreen
import com.filemanager.app.ui.screens.TrashScreen
import com.filemanager.app.viewmodel.BrowserViewModel
import com.filemanager.app.viewmodel.HomeViewModel
import com.filemanager.app.viewmodel.SearchViewModel
import com.filemanager.app.viewmodel.StorageViewModel
import com.filemanager.app.viewmodel.TrashViewModel
import com.filemanager.app.viewmodel.ViewModelFactory
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import uniffi.filemanager_core.FileCategory
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
    const val BROWSE_PATTERN = "browse/{path}"
    const val SEARCH_PATTERN = "search?category={category}"
    const val STORAGE = "storage"
    const val TRASH = "trash"
    const val RECENT = "recent"

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

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = factory)
            val state by vm.state.collectAsState()

            OneUiScreen(
                title = "My Files",
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.search()) }) {
                        Icon(Icons.Default.Search, "Search")
                    }
                    HomeOverflowMenu(
                        onManageStorage = { navController.navigate(Routes.STORAGE) },
                        onTrash = { navController.navigate(Routes.TRASH) },
                    )
                },
            ) { padding ->
                HomeScreen(
                    state = state,
                    onCategoryClick = { category ->
                        navController.navigate(Routes.search(category))
                    },
                    onDownloadsClick = {
                        navController.navigate(Routes.browse(StorageVolumes.downloadsPath()))
                    },
                    onRecentClick = { navController.navigate(Routes.RECENT) },
                    onVolumeClick = { navController.navigate(Routes.browse(it.path)) },
                    onTrashClick = { navController.navigate(Routes.TRASH) },
                    onManageStorageClick = { navController.navigate(Routes.STORAGE) },
                    onFileClick = openFile,
                    modifier = Modifier.padding(padding),
                )
            }
        }

        composable(Routes.RECENT) {
            val vm: HomeViewModel = viewModel(factory = factory)
            val state by vm.state.collectAsState()

            RecentScreen(
                entries = state.recent,
                isLoading = state.isLoading,
                onOpenFile = openFile,
                onNavigateBack = { navController.popBackStack() },
            )
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