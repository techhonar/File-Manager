package com.filemanager.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filemanager.app.BuildConfig
import com.filemanager.app.data.update.GITHUB_REPOSITORY
import com.filemanager.app.ui.components.OneUiGroup
import com.filemanager.app.ui.components.OneUiRow
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.theme.OneUi
import uniffi.filemanager_core.coreVersion

/**
 * App identity, versions and what the thing actually is.
 *
 * The native core reports its own version separately from the app's. They are
 * built together so they should always agree, but a stale .so surviving in a
 * build directory is a real failure mode, and printing both makes it obvious
 * rather than mysterious.
 */
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Reading it crosses into Rust, so do it once rather than on every
    // recomposition.
    val nativeVersion = remember { runCatching { coreVersion() }.getOrDefault("unavailable") }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    OneUiScreen(
        title = "About",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "File Manager",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Browse, search and organise everything on your device. " +
                    "Storage is analysed and searched by a native core written " +
                    "in Rust, so scanning a full phone stays fast, while the " +
                    "interface is Kotlin and Jetpack Compose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = OneUi.ScreenPadding + 8.dp),
            )

            Spacer(Modifier.height(28.dp))

            OneUiGroup {
                DetailRow("Version", BuildConfig.VERSION_NAME)
                DetailRow("Build", BuildConfig.VERSION_CODE.toString())
                DetailRow("Native core", nativeVersion)
                DetailRow("Package", BuildConfig.APPLICATION_ID)
            }

            Spacer(Modifier.height(20.dp))

            OneUiGroup {
                OneUiRow(
                    title = "Source code on GitHub",
                    subtitle = "github.com/$GITHUB_REPOSITORY",
                    icon = Icons.Outlined.Code,
                    onClick = {
                        runCatching { uriHandler.openUri("https://github.com/$GITHUB_REPOSITORY") }
                            .onFailure {
                                Toast.makeText(context, "No browser to open it in", Toast.LENGTH_LONG).show()
                            }
                    },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
