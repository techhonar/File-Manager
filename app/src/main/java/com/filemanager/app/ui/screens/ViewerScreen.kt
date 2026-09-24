package com.filemanager.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.external.copyForViewing
import com.filemanager.app.data.viewer.DocAlign
import com.filemanager.app.data.viewer.DocBlock
import com.filemanager.app.data.viewer.DocCell
import com.filemanager.app.data.viewer.DocImage
import com.filemanager.app.data.viewer.DocParagraph
import com.filemanager.app.data.viewer.DocStyle
import com.filemanager.app.data.viewer.DocTable
import com.filemanager.app.data.viewer.Docx
import com.filemanager.app.data.viewer.DocxDocument
import com.filemanager.app.data.viewer.PdfDocument
import com.filemanager.app.data.viewer.TextContent
import com.filemanager.app.data.viewer.TextFiles
import com.filemanager.app.data.viewer.ViewerKind
import com.filemanager.app.data.viewer.viewerKind
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.theme.OneUi
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** A file being read for the viewer: still reading, failed with a message, or read. */
private sealed interface Load<out T> {
    data object Reading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Read<T>(val value: T) : Load<T>
}

/**
 * The app's own viewer for text, PDFs and Word documents. "Open with" hands
 * the file to another app, for anything this one does not show - or for
 * editing it.
 */
@Composable
fun ViewerScreen(
    path: String,
    onOpenWith: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Given when the name can't say: another app's file may have none.
    kind: ViewerKind? = null,
    // Off for another app's file: what is shown is a copy, and saving it
    // would change nothing the sender sees.
    editable: Boolean = true,
) {
    val file = remember(path) { File(path) }
    val shown = kind ?: viewerKind(file.name)
    val openWith = { onOpenWith(path) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edit: TextEdit? = if (editable && shown == ViewerKind.TEXT) viewModel(key = "edit $path") { TextEdit() } else null

    Crossfade(targetState = edit?.opened != null, animationSpec = PaneFade, label = "edit") { editing ->
        if (editing && edit != null) {
            TextEditor(file, edit, modifier)
            return@Crossfade
        }
        OneUiScreen(
            title = file.name,
            modifier = modifier,
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = {
                if (edit != null) {
                    IconButton(onClick = { scope.launch { startEditing(context, file, edit) } }) {
                        Icon(Icons.Default.Edit, "Edit")
                    }
                }
                IconButton(onClick = openWith) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with")
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (shown) {
                    // Read again after each save, to show what was saved.
                    ViewerKind.TEXT -> Viewer(file, { TextFiles.read(it) }, openWith, edit?.saves ?: 0) { TextView(it) }
                    ViewerKind.DOCX -> Viewer(file, { Docx.read(it) }, openWith) { DocxView(it, file) }
                    ViewerKind.PDF -> PdfPane(file, openWith)
                    null -> Message("This file can't be shown here.", openWith)
                }
            }
        }
    }
}

/**
 * A file another app asked to have shown - opened from its "Open with", say.
 * Copied in before it is read, since it is only lent while this is open;
 * back returns to the app that sent it.
 */
@Composable
fun ExternalViewerScreen(
    uri: Uri,
    kind: ViewerKind,
    onOpenWith: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val copy by produceState<Result<File>?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { copyForViewing(context, uri) } }
    }
    val file = copy?.getOrNull()
    when {
        file != null -> ViewerScreen(
            path = file.path,
            onOpenWith = onOpenWith,
            onNavigateBack = onClose,
            kind = kind,
            editable = false,
        )
        copy == null -> Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            Alignment.Center,
        ) { CircularProgressIndicator() }
        else -> OneUiScreen(
            title = "Can't open file",
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                Message("This file couldn't be read from the app that sent it.", onOpenWith = null)
            }
        }
    }
}

/** Reads [file] with [read] away from the main thread, then shows it with [content]. */
@Composable
private fun <T : Any> Viewer(
    file: File,
    read: (File) -> T,
    onOpenWith: () -> Unit,
    version: Int = 0,
    content: @Composable (T) -> Unit,
) {
    // Keeps showing what it has while reading again for a new [version].
    val load by produceState<Load<T>>(Load.Reading, file, version) {
        value = withContext(Dispatchers.IO) {
            try {
                Load.Read(read(file))
            } catch (e: Throwable) {
                Load.Failed(describe(e))
            }
        }
    }
    LoadedPane(load, onOpenWith, content)
}

/**
 * A PDF stays open while it is shown, for drawing pages as they scroll into
 * view, and is closed when the screen goes.
 */
@Composable
private fun PdfPane(file: File, onOpenWith: () -> Unit) {
    val load by produceState<Load<PdfDocument>>(Load.Reading, file) {
        val result = withContext(Dispatchers.IO) { runCatching { PdfDocument.open(file) } }
        value = result.fold({ Load.Read(it) }, { Load.Failed(describe(it)) })
        awaitDispose { result.getOrNull()?.close() }
    }
    LoadedPane(load, onOpenWith) { document ->
        if (document.pageCount == 0) Message("This PDF has no pages.", onOpenWith) else PdfViewer(document)
    }
}

@Composable
private fun <T : Any> LoadedPane(load: Load<T>, onOpenWith: () -> Unit, content: @Composable (T) -> Unit) {
    val pane = when (load) {
        Load.Reading -> Pane.LOADING
        is Load.Failed -> Pane.ERROR
        is Load.Read -> Pane.ITEMS
    }
    Crossfade(targetState = pane, animationSpec = PaneFade, label = "viewer") { shown ->
        when (shown) {
            Pane.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            // The fading-out pane reads the current state, so each looks
            // for its own case rather than assuming it.
            Pane.ERROR -> if (load is Load.Failed) Message(load.message, onOpenWith)
            else -> if (load is Load.Read) content(load.value)
        }
    }
}

private fun describe(error: Throwable): String = when (error) {
    is SecurityException -> "This PDF is protected with a password."
    is OutOfMemoryError -> "This file is too large to show here."
    else -> "This file couldn't be read. It may be damaged, or not what its name says."
}

/** [text] in the middle of the screen, offering [onOpenWith] when there is somewhere else to go. */
@Composable
private fun Message(text: String, onOpenWith: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = OneUi.ScreenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onOpenWith != null) TextButton(onClick = onOpenWith) { Text("Open with another app") }
    }
}

/** Said above what was read, when it is not all of the file. */
@Composable
private fun CutShort(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

// ---- Text ------------------------------------------------------------------

@Composable
private fun TextView(content: TextContent) {
    if (content.lines.isEmpty()) {
        Message("This file is empty.", onOpenWith = null)
        return
    }
    // Monospace: much of what opens here is code, logs and data, which line
    // up in columns.
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (content.truncated) {
            item { CutShort("Showing the first ${TextFiles.READ_LIMIT / (1024 * 1024)} MB of this file.") }
        }
        items(content.lines) { line ->
            Text(text = line, style = style, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ---- Word ------------------------------------------------------------------

@Composable
private fun DocxView(document: DocxDocument, file: File) {
    if (document.blocks.isEmpty()) {
        Message("This document is empty.", onOpenWith = null)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (document.truncated) {
            item { CutShort("This document is long, so only its beginning is shown.") }
        }
        for (block in document.blocks) {
            // A table row by row, so a long one scrolls like the rest.
            if (block is DocTable) {
                items(block.rows) { row -> DocTableRow(row, block.columns, file) }
                item { Box(Modifier.height(12.dp)) }
            } else {
                item { DocBlockView(block, file) }
            }
        }
    }
}

@Composable
private fun DocBlockView(block: DocBlock, file: File) {
    when (block) {
        is DocParagraph -> DocParagraphView(block)
        is DocImage -> DocImageView(block, file)
        is DocTable -> Column(Modifier.padding(bottom = 8.dp)) {
            block.rows.forEach { row -> DocTableRow(row, block.columns, file) }
        }
    }
}

@Composable
private fun DocParagraphView(paragraph: DocParagraph) {
    val typography = MaterialTheme.typography
    val style: TextStyle = when (paragraph.style) {
        DocStyle.TITLE -> typography.headlineMedium
        DocStyle.SUBTITLE -> typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        DocStyle.HEADING1 -> typography.headlineSmall
        DocStyle.HEADING2 -> typography.titleLarge
        DocStyle.HEADING3 -> typography.titleMedium
        DocStyle.HEADING4 -> typography.titleSmall
        DocStyle.BODY -> typography.bodyLarge
    }
    val text = remember(paragraph) {
        buildAnnotatedString {
            for (span in paragraph.spans) {
                val decorations = listOfNotNull(
                    TextDecoration.Underline.takeIf { span.underline },
                    TextDecoration.LineThrough.takeIf { span.strike },
                )
                withStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                    ),
                ) { append(span.text) }
            }
        }
    }
    val align = when (paragraph.align) {
        DocAlign.START -> TextAlign.Start
        DocAlign.CENTER -> TextAlign.Center
        DocAlign.END -> TextAlign.End
        DocAlign.JUSTIFY -> TextAlign.Justify
    }
    val above = if (paragraph.style == DocStyle.BODY) 0.dp else 12.dp
    Row(Modifier.fillMaxWidth().padding(start = 20.dp * paragraph.level, top = above, bottom = 6.dp)) {
        paragraph.marker?.let { marker ->
            Text(
                text = marker,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
            )
        }
        Text(
            text = text,
            style = style,
            color = if (paragraph.style == DocStyle.SUBTITLE) style.color else MaterialTheme.colorScheme.onSurface,
            textAlign = align,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DocTableRow(row: List<DocCell>, columns: List<Float>, file: File) {
    val line = MaterialTheme.colorScheme.outlineVariant
    // Every cell as tall as the row's tallest, so the borders line up.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        var column = 0
        for (cell in row) {
            val weight = columns.drop(column).take(cell.span).sum().takeIf { it > 0f } ?: 1f
            column += cell.span
            Column(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .border(Dp.Hairline, line)
                    .padding(6.dp),
            ) {
                cell.blocks.forEach { DocBlockView(it, file) }
            }
        }
    }
}

/** A picture's decoding, which fails for Windows metafiles and anything else Android can't draw. */
private sealed interface Picture {
    data object Decoding : Picture
    data object Unreadable : Picture
    data class Decoded(val bitmap: ImageBitmap) : Picture
}

@Composable
private fun DocImageView(image: DocImage, file: File) {
    // The screen's width rather than the space given: measuring that takes a
    // layout that a table row, sizing its cells to match, cannot ask.
    val maxPixels = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val picture by produceState<Picture>(Picture.Decoding, image.entry) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodePicture(file, image.entry, maxPixels) }.getOrNull()
                ?.let { Picture.Decoded(it) } ?: Picture.Unreadable
        }
    }
    // Word gives the size it shows the picture at; EMUs to dp is 9525 each,
    // at Word's 96 dots an inch.
    val shownWidth = if (image.widthEmu > 0) (image.widthEmu / 9525f).dp else Dp.Unspecified
    val ratio = when {
        image.widthEmu > 0 && image.heightEmu > 0 -> image.widthEmu.toFloat() / image.heightEmu
        else -> (picture as? Picture.Decoded)?.bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }?.coerceIn(0.05f, 20f)

    when (val shown = picture) {
        is Picture.Decoded -> Image(
            bitmap = shown.bitmap,
            contentDescription = null,
            modifier = Modifier
                .padding(vertical = 6.dp)
                .widthIn(max = shownWidth)
                .fillMaxWidth()
                .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier),
        )
        Picture.Unreadable -> Text(
            text = "[Picture that can't be shown here]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        // Held at its size while decoding, so the text below does not jump.
        Picture.Decoding -> Box(
            Modifier
                .padding(vertical = 6.dp)
                .widthIn(max = shownWidth)
                .fillMaxWidth()
                .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier),
        )
    }
}

/** Decodes a picture from inside the .docx, scaled down to about [maxPixels] wide. */
private fun decodePicture(file: File, entry: String, maxPixels: Int): ImageBitmap? = ZipFile(file).use { zip ->
    val item = zip.getEntry(entry) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    zip.getInputStream(item).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPixels) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    zip.getInputStream(item).use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
}
