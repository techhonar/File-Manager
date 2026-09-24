package com.filemanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.filemanager.app.ui.theme.CategoryColors
import com.filemanager.app.viewmodel.Category
import uniffi.filemanager_core.FileCategory

// Outlined rather than filled: the category grid draws a thin coloured glyph
// on a neutral card, and filled icons read as far heavier than that.

fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.DIRECTORY -> Icons.Outlined.Folder
    FileCategory.IMAGE -> Icons.Outlined.Image
    FileCategory.VIDEO -> Icons.Outlined.Videocam
    FileCategory.AUDIO -> Icons.Outlined.MusicNote
    FileCategory.DOCUMENT -> Icons.Outlined.Description
    FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
    FileCategory.APK -> Icons.Outlined.Android
    FileCategory.OTHER -> Icons.Outlined.InsertDriveFile
}

/** Follows the theme, where each category's colour can be set by hand. */
@Composable
@ReadOnlyComposable
fun FileCategory.color(): Color = when (this) {
    FileCategory.DIRECTORY -> CategoryColors.Directory
    FileCategory.IMAGE -> CategoryColors.Image
    FileCategory.VIDEO -> CategoryColors.Video
    FileCategory.AUDIO -> CategoryColors.Audio
    FileCategory.DOCUMENT -> CategoryColors.Document
    FileCategory.ARCHIVE -> CategoryColors.Archive
    FileCategory.APK -> CategoryColors.Apk
    FileCategory.OTHER -> CategoryColors.Other
}

fun FileCategory.label(): String = when (this) {
    FileCategory.DIRECTORY -> "Folders"
    FileCategory.IMAGE -> "Images"
    FileCategory.VIDEO -> "Videos"
    FileCategory.AUDIO -> "Audio files"
    FileCategory.DOCUMENT -> "Documents"
    FileCategory.ARCHIVE -> "Archives"
    FileCategory.APK -> "Installation files"
    FileCategory.OTHER -> "Other"
}

fun Category.icon(): ImageVector = when (this) {
    is Category.OfType -> type.icon()
    Category.Downloads -> Icons.Outlined.Download
}

@Composable
@ReadOnlyComposable
fun Category.color(): Color = when (this) {
    is Category.OfType -> type.color()
    Category.Downloads -> CategoryColors.Downloads
}

fun Category.label(): String = when (this) {
    is Category.OfType -> type.label()
    Category.Downloads -> "Downloads"
}
