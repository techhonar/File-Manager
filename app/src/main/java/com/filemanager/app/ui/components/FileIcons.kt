package com.filemanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.filemanager.app.ui.theme.CategoryColors
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
