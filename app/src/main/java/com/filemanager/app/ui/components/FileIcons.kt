package com.filemanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.filemanager.app.ui.theme.CategoryColors
import uniffi.filemanager_core.FileCategory

/** The icon and accent colour for a category, used by every list and tile. */
fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.DIRECTORY -> Icons.Default.Folder
    FileCategory.IMAGE -> Icons.Default.Image
    FileCategory.VIDEO -> Icons.Default.Videocam
    FileCategory.AUDIO -> Icons.Default.MusicNote
    FileCategory.DOCUMENT -> Icons.Default.Description
    FileCategory.ARCHIVE -> Icons.Default.FolderZip
    FileCategory.APK -> Icons.Default.Android
    FileCategory.OTHER -> Icons.Default.InsertDriveFile
}

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
    FileCategory.AUDIO -> "Audio"
    FileCategory.DOCUMENT -> "Documents"
    FileCategory.ARCHIVE -> "Archives"
    FileCategory.APK -> "Installation files"
    FileCategory.OTHER -> "Other"
}
