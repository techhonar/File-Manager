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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import com.filemanager.app.ui.theme.CategoryColors
import com.filemanager.app.ui.theme.LocalCategoryPalette
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

/**
 * Each kind of document in a colour of its own, as One UI has them - PDF in
 * red, Word in blue, spreadsheets in green - unless Documents has been given
 * a colour by hand under Theme, which then holds for all of them.
 */
@Composable
@ReadOnlyComposable
fun DocumentKind.color(): Color {
    val palette = LocalCategoryPalette.current
    if (palette.documentSetByHand) return palette.document
    return when (this) {
        DocumentKind.PDF -> Color(0xFFD93B40)
        DocumentKind.WORD -> Color(0xFF2563EB)
        DocumentKind.SHEET -> Color(0xFF178A4A)
        DocumentKind.SLIDES -> Color(0xFFE05A26)
        DocumentKind.TEXT -> Color(0xFF687385)
        DocumentKind.WEB -> Color(0xFF7C4DDB)
        DocumentKind.EBOOK -> Color(0xFFB06F28)
    }
}

/**
 * A document's icon in a list: a circle in its kind's colour, with its
 * extension across it - "PDF", "DOCX" - as One UI marks a text file "TXT".
 */
@Composable
fun DocumentBadge(type: DocumentType, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(type.kind.color()),
        contentAlignment = Alignment.Center,
    ) {
        DocumentLabel(type, color = Color.White, letterHeight = size * documentLabelScale(type.label))
    }
}

/**
 * A document's extension as its icon, [letterHeight] tall whatever the font
 * size setting: it has to fit the space drawn for it.
 */
@Composable
fun DocumentLabel(type: DocumentType, color: Color, letterHeight: Dp, modifier: Modifier = Modifier) {
    Text(
        text = type.label,
        color = color,
        fontSize = with(LocalDensity.current) { letterHeight.toSp() },
        fontWeight = FontWeight.Bold,
        letterSpacing = if (type.label.length > 3) (-0.02).em else 0.em,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
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
