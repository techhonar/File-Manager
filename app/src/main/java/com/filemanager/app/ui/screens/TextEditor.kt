package com.filemanager.app.ui.screens

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.filemanager.app.FileManagerApp
import com.filemanager.app.data.EditorPrefs
import com.filemanager.app.data.editor.EditHistory
import com.filemanager.app.data.editor.Language
import com.filemanager.app.data.editor.TextSearch
import com.filemanager.app.data.editor.TokenKind
import com.filemanager.app.data.editor.indentNewLine
import com.filemanager.app.data.editor.lineAndColumn
import com.filemanager.app.data.editor.lineCount
import com.filemanager.app.data.editor.lineStart
import com.filemanager.app.data.editor.wordCount
import com.filemanager.app.data.viewer.Editing
import com.filemanager.app.data.viewer.TextEncoding
import com.filemanager.app.data.viewer.TextFiles
import com.filemanager.app.ui.components.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * A text file's edit in progress. Kept by a ViewModel, which outlives the
 * screen being rebuilt when the phone turns - unsaved typing would otherwise
 * go with it.
 *
 * Every change goes through here, so each is in the undo history.
 */
class TextEdit : ViewModel() {
    /** The file being edited - a different one after Save as. Null before any editing. */
    var file by mutableStateOf<File?>(null)
        private set
    var value by mutableStateOf(TextFieldValue(""))
        private set
    var encoding by mutableStateOf(TextEncoding.UTF8)
        private set
    var crlf by mutableStateOf(false)
        private set
    var editing by mutableStateOf(false)
        private set
    var changed by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Counts saves, so the viewer behind knows to read the file again. */
    var saves by mutableIntStateOf(0)
        private set

    /** A new file opens straight into editing - once, not again after closing. */
    var autoStarted = false

    private val history = EditHistory()

    fun begin(file: File, ready: Editing.Ready) {
        this.file = file
        value = TextFieldValue(ready.text)
        encoding = ready.encoding
        crlf = ready.crlf
        history.clear()
        changed = false
        editing = true
        refresh()
    }

    /** From the text field: typing, pasting, and the keyboard's own edits. */
    fun update(new: TextFieldValue) {
        val old = value
        if (new.text == old.text) {
            value = new
            return
        }
        // Not while the keyboard is still composing a word: it would lose track.
        val indented = if (new.composition == null) indentNewLine(old.text, new.text, new.selection.start) else null
        record(old, indented?.let { (text, cursor) -> TextFieldValue(text, TextRange(cursor)) } ?: new)
    }

    /** [text] in place of what is between [start] and [end], the cursor after it. */
    fun replace(start: Int, end: Int, text: String) {
        val old = value
        val from = start.coerceIn(0, old.text.length)
        val to = end.coerceIn(from, old.text.length)
        record(old, TextFieldValue(old.text.substring(0, from) + text + old.text.substring(to), TextRange(from + text.length)))
    }

    /** [text] in place of the selection, or at the cursor. */
    fun insert(text: String) = replace(value.selection.min, value.selection.max, text)

    fun replaceAll(text: String) {
        record(value, TextFieldValue(text, TextRange(value.selection.min.coerceAtMost(text.length))))
    }

    fun select(start: Int, end: Int = start) {
        val length = value.text.length
        value = value.copy(selection = TextRange(start.coerceIn(0, length), end.coerceIn(0, length)))
    }

    fun undo() {
        history.undo(value.text)?.let { (text, cursor) -> show(text, cursor) }
        refresh()
    }

    fun redo() {
        history.redo(value.text)?.let { (text, cursor) -> show(text, cursor) }
        refresh()
    }

    fun setFormat(encoding: TextEncoding, crlf: Boolean) {
        if (encoding == this.encoding && crlf == this.crlf) return
        this.encoding = encoding
        this.crlf = crlf
        changed = true
    }

    /** [text] was saved to [to]; unchanged since, unless typing went on while saving. */
    fun saved(to: File, text: String) {
        file = to
        changed = value.text != text
        saves++
    }

    // The text stays, so the editor still shows it while fading out.
    fun end() {
        editing = false
        changed = false
    }

    private fun record(old: TextFieldValue, new: TextFieldValue) {
        history.record(old.text, new.text, old.selection.end, new.selection.end, SystemClock.uptimeMillis())
        value = new
        changed = true
        refresh()
    }

    private fun show(text: String, cursor: Int) {
        value = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
        changed = true
    }

    private fun refresh() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }
}

/** Opens [file] in [edit], or says why it can't be. */
suspend fun startEditing(context: Context, file: File, edit: TextEdit) {
    val result = withContext(Dispatchers.IO) {
        if (!file.canWrite()) null else runCatching { TextFiles.openForEditing(file) }.getOrNull()
    }
    val refusal = when (result) {
        is Editing.Ready -> return edit.begin(file, result)
        Editing.TooLarge -> "This file is too large to edit here. Open it with another app to edit it."
        Editing.NotText -> "This file isn't plain text this app can edit without damaging it."
        null -> "This file can't be changed."
    }
    Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
}

private enum class EditorDialog { DISCARD, GO_TO_LINE, SAVE_AS, FORMAT, TEXT_SIZE }

/** Past this many characters code is shown without colours, which would slow typing. */
private const val HIGHLIGHT_LIMIT = 150_000

/** What the symbol bar offers: what is fiddly to reach on a phone keyboard. */
private val SYMBOLS = listOf("⇥" to "\t") + listOf(
    "{", "}", "(", ")", "[", "]", "<", ">", "\"", "'", "=", ";", ":", "/", "\\", "#", "*", "-", "+",
    "_", "&", "|", "!", "?", "$", "%", "@", "~", "`", ",", ".",
).map { it to it }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditor(edit: TextEdit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { (context.applicationContext as FileManagerApp).settings }
    val prefs by settings.editor.collectAsState()
    val file = edit.file ?: return
    val value = edit.value
    val language = remember(file.name) { Language.of(file.name) }
    val focus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }

    var saving by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }

    val matches = remember(value.text, query, matchCase, wholeWord, finding) {
        if (finding) TextSearch.findAll(value.text, query, matchCase, wholeWord) else emptyList()
    }
    val shown = current.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun saveTo(target: File) {
        if (saving) return
        saving = true
        val text = edit.value.text
        val encoding = edit.encoding
        val crlf = edit.crlf
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { TextFiles.save(target, text, encoding, crlf) }.exceptionOrNull()
            }
            saving = false
            if (error == null) {
                edit.saved(target, text)
                toast(if (target == file) "Saved" else "Saved as ${target.name}")
            } else {
                toast("Couldn't save: ${error.message ?: "the file can't be written"}")
            }
        }
    }

    fun showMatch(index: Int, among: List<IntRange> = matches) {
        if (among.isEmpty()) return
        current = index.mod(among.size)
        val match = among[current]
        edit.select(match.first, match.last + 1)
    }

    // A new search starts at the first match after the cursor.
    LaunchedEffect(query, matchCase, wholeWord, finding) {
        if (!finding || matches.isEmpty()) return@LaunchedEffect
        val from = edit.value.selection.min
        showMatch(matches.indexOfFirst { it.first >= from }.takeIf { it >= 0 } ?: 0)
    }

    fun openFind() {
        // What is selected, if it is on one line, is what gets looked for.
        val selected = value.text.substring(value.selection.min, value.selection.max)
        if (selected.isNotEmpty() && '\n' !in selected) query = selected
        finding = true
    }

    val close: () -> Unit = {
        if (edit.changed) {
            dialog = EditorDialog.DISCARD
        } else {
            edit.end()
        }
    }
    BackHandler {
        if (finding) finding = false else close()
    }

    val shortcuts: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
            false
        } else {
            when (event.key) {
                Key.S -> saveTo(file).let { true }
                Key.Z -> (if (event.isShiftPressed) edit.redo() else edit.undo()).let { true }
                Key.Y -> edit.redo().let { true }
                Key.F -> openFind().let { true }
                Key.G -> { dialog = EditorDialog.GO_TO_LINE; true }
                else -> false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (edit.changed) "${file.name} •" else file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Default.Close, "Stop editing") }
                },
                actions = {
                    IconButton(onClick = edit::undo, enabled = edit.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                    }
                    IconButton(onClick = edit::redo, enabled = edit.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                    }
                    IconButton(onClick = { saveTo(file) }, enabled = edit.changed && !saving) {
                        Icon(Icons.Default.Check, "Save")
                    }
                    EditorMenu(
                        prefs = prefs,
                        canColour = language != null,
                        onPrefs = settings::setEditor,
                        onFind = ::openFind,
                        onDialog = { dialog = it },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                // Above the keyboard: the symbol bar and status line ride on it.
                .imePadding()
                .fillMaxSize(),
        ) {
            if (finding) {
                FindBar(
                    query = query,
                    onQuery = { query = it },
                    replacement = replacement,
                    onReplacement = { replacement = it },
                    matchCase = matchCase,
                    onMatchCase = { matchCase = it },
                    wholeWord = wholeWord,
                    onWholeWord = { wholeWord = it },
                    position = when {
                        query.isEmpty() -> ""
                        matches.isEmpty() -> "None"
                        matches.size >= TextSearch.MAX_MATCHES -> "${shown + 1}/${matches.size}+"
                        else -> "${shown + 1}/${matches.size}"
                    },
                    onPrevious = { showMatch(shown - 1) },
                    onNext = { showMatch(shown + 1) },
                    onReplace = {
                        matches.getOrNull(shown)?.let { match ->
                            edit.replace(match.first, match.last + 1, replacement)
                            showMatch(shown, TextSearch.findAll(edit.value.text, query, matchCase, wholeWord))
                        }
                    },
                    onReplaceAll = {
                        val (text, count) = TextSearch.replaceAll(value.text, query, replacement, matchCase, wholeWord)
                        if (count > 0) edit.replaceAll(text)
                        toast(if (count == 1) "Replaced 1" else "Replaced $count")
                    },
                    onClose = { finding = false },
                    focus = findFocus,
                )
                LaunchedEffect(Unit) { findFocus.requestFocus() }
            }

            EditorArea(
                edit = edit,
                prefs = prefs,
                language = language,
                matches = matches,
                current = shown,
                focus = focus,
                onKey = shortcuts,
                modifier = Modifier.weight(1f),
            )

            StatusBar(value, edit.encoding, edit.crlf)
            if (prefs.symbolBar) SymbolBar(onInsert = edit::insert)
        }
    }

    when (dialog) {
        EditorDialog.DISCARD -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to ${file.name} haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    edit.end()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Keep editing") }
            },
        )
        EditorDialog.GO_TO_LINE -> {
            val lines = lineCount(value.text)
            TextInputDialog(
                title = "Go to line",
                label = "Line, 1 to $lines",
                initial = "",
                confirmLabel = "Go",
                onConfirm = { input ->
                    dialog = null
                    val line = input.trim().toIntOrNull()
                    if (line == null) {
                        toast("That isn't a line number")
                    } else {
                        edit.select(lineStart(edit.value.text, line.coerceIn(1, lines)))
                        focus.requestFocus()
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        EditorDialog.SAVE_AS -> TextInputDialog(
            title = "Save as",
            label = "File name",
            initial = file.name,
            confirmLabel = "Save",
            onConfirm = { name ->
                dialog = null
                val target = File(file.parentFile, name.trim())
                if (target != file && target.exists()) {
                    toast("There's already a file named ${target.name}")
                } else {
                    saveTo(target)
                }
            },
            onDismiss = { dialog = null },
        )
        EditorDialog.FORMAT -> FormatDialog(edit, onDismiss = { dialog = null })
        EditorDialog.TEXT_SIZE -> TextSizeDialog(prefs, settings::setEditor, onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun EditorMenu(
    prefs: EditorPrefs,
    canColour: Boolean,
    onPrefs: (EditorPrefs) -> Unit,
    onFind: () -> Unit,
    onDialog: (EditorDialog) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "More options") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        @Composable
        fun item(text: String, action: () -> Unit) {
            DropdownMenuItem(
                text = { Text(text) },
                onClick = {
                    expanded = false
                    action()
                },
            )
        }

        @Composable
        fun toggle(text: String, on: Boolean, change: (Boolean) -> EditorPrefs) {
            // Stays open, to try the difference.
            DropdownMenuItem(
                text = { Text(text) },
                onClick = { onPrefs(change(!on)) },
                trailingIcon = { Checkbox(checked = on, onCheckedChange = null) },
            )
        }

        item("Find and replace", onFind)
        item("Go to line") { onDialog(EditorDialog.GO_TO_LINE) }
        item("Save as…") { onDialog(EditorDialog.SAVE_AS) }
        item("Encoding and line endings…") { onDialog(EditorDialog.FORMAT) }
        item("Text size…") { onDialog(EditorDialog.TEXT_SIZE) }
        HorizontalDivider()
        toggle("Word wrap", prefs.wordWrap) { prefs.copy(wordWrap = it) }
        toggle("Line numbers", prefs.lineNumbers) { prefs.copy(lineNumbers = it) }
        if (canColour) toggle("Syntax colours", prefs.highlight) { prefs.copy(highlight = it) }
        toggle("Symbol bar", prefs.symbolBar) { prefs.copy(symbolBar = it) }
    }
}

@Composable
private fun EditorArea(
    edit: TextEdit,
    prefs: EditorPrefs,
    language: Language?,
    matches: List<IntRange>,
    current: Int,
    focus: FocusRequester,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val value = edit.value
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = prefs.textSize.sp,
        // Fixed, so that every line - the line numbers' too - is the same height.
        lineHeight = (prefs.textSize * 1.45f).sp,
        color = colors.onSurface,
    )
    val palette = syntaxPalette(dark = colors.background.luminance() < 0.5f, accent = colors.primary)
    val findColor = colors.primary.copy(alpha = 0.2f)
    val currentColor = colors.primary.copy(alpha = 0.5f)
    val colouring = remember(value.text, language, prefs.highlight, palette, matches, current) {
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        if (prefs.highlight && language != null && value.text.length <= HIGHLIGHT_LIMIT) {
            for (token in language.tokens(value.text)) {
                spans += AnnotatedString.Range(palette.getValue(token.kind), token.start, token.end)
            }
        }
        matches.forEachIndexed { index, match ->
            val background = if (index == current) currentColor else findColor
            spans += AnnotatedString.Range(SpanStyle(background = background), match.first, match.last + 1)
        }
        Colouring(spans)
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textWidth by remember { mutableIntStateOf(0) }
    val vertical = rememberScrollState()
    val sideways = rememberScrollState()
    val top = 8.dp
    val start = 8.dp

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewport = maxHeight
        Row(Modifier.fillMaxSize().verticalScroll(vertical)) {
            if (prefs.lineNumbers) {
                val numbers = remember(value.text, layout) { lineNumbers(value.text, layout) }
                Text(
                    text = numbers,
                    style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.End),
                    softWrap = false,
                    modifier = Modifier.padding(start = 8.dp, top = top),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { textWidth = it.width }
                    .then(if (prefs.wordWrap) Modifier else Modifier.horizontalScroll(sideways)),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = edit::update,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    visualTransformation = colouring,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        // Unwrapped, lines run as wide as they are; the field
                        // is still at least the screen's width, to tap into.
                        .then(
                            if (prefs.wordWrap) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.widthIn(min = with(density) { textWidth.toDp() })
                            },
                        )
                        // A tap anywhere below the text still lands in it.
                        .heightIn(min = viewport)
                        .padding(start = start, end = 12.dp, top = top, bottom = 24.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent(onKey),
                )
            }
        }
    }

    // Keeps the cursor on screen. The field only does that itself when it
    // first gains focus; this area scrolls as a whole, gutter and all, so it
    // is done here - after typing, undo, go to line and each find.
    LaunchedEffect(value.selection, layout, vertical.viewportSize, textWidth, prefs.wordWrap) {
        val result = layout ?: return@LaunchedEffect
        val offset = value.selection.end
        if (offset > result.layoutInput.text.length) return@LaunchedEffect
        val cursor = result.getCursorRect(offset)
        val margin = cursor.height
        val above = with(density) { top.toPx() } + cursor.top - margin
        val below = with(density) { top.toPx() } + cursor.bottom + margin
        val height = vertical.viewportSize
        if (above < vertical.value) {
            vertical.animateScrollTo(above.roundToInt().coerceAtLeast(0))
        } else if (height > 0 && below > vertical.value + height) {
            vertical.animateScrollTo((below - height).roundToInt())
        }
        if (!prefs.wordWrap && textWidth > 0) {
            val x = with(density) { start.toPx() } + cursor.left
            if (x - margin < sideways.value) {
                sideways.animateScrollTo((x - margin).roundToInt().coerceAtLeast(0))
            } else if (x + margin > sideways.value + textWidth) {
                sideways.animateScrollTo((x + margin - textWidth).roundToInt())
            }
        }
    }
}

/**
 * The numbers down the side, one per line of the file and as many rows down
 * as that line wraps to, so each sits beside the line it counts.
 */
private fun lineNumbers(text: String, layout: TextLayoutResult?): String {
    val usable = layout?.takeIf { it.layoutInput.text.length == text.length }
    val out = StringBuilder()
    var number = 1
    var start = 0
    while (true) {
        out.append(number)
        val end = text.indexOf('\n', start)
        if (end < 0) break
        val rows = if (usable == null) 1 else usable.getLineForOffset(end) - usable.getLineForOffset(start) + 1
        repeat(rows) { out.append('\n') }
        start = end + 1
        number++
    }
    return out.toString()
}

/** Colours laid over the text as it is shown; the text itself is untouched. */
private class Colouring(private val spans: List<AnnotatedString.Range<SpanStyle>>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val length = text.length
        val fitting = spans.filter { it.end <= length }
        return TransformedText(AnnotatedString(text.text, fitting), OffsetMapping.Identity)
    }
}

/** Code colours that read on the theme's background, light or dark. */
@Composable
private fun syntaxPalette(dark: Boolean, accent: Color): Map<TokenKind, SpanStyle> = remember(dark, accent) {
    val string = if (dark) Color(0xFF8BD38B) else Color(0xFF1B7F2A)
    mapOf(
        TokenKind.KEYWORD to SpanStyle(color = accent, fontWeight = FontWeight.SemiBold),
        TokenKind.STRING to SpanStyle(color = string),
        TokenKind.COMMENT to SpanStyle(
            color = if (dark) Color(0xFF8E8E99) else Color(0xFF787884),
            fontStyle = FontStyle.Italic,
        ),
        TokenKind.NUMBER to SpanStyle(color = if (dark) Color(0xFF6CCFDB) else Color(0xFF0A6C94)),
        TokenKind.TAG to SpanStyle(color = accent),
        TokenKind.ATTRIBUTE to SpanStyle(color = if (dark) Color(0xFFD7A1E6) else Color(0xFF8A2BA0)),
        TokenKind.HEADING to SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        TokenKind.EMPHASIS to SpanStyle(fontWeight = FontWeight.Bold),
        TokenKind.CODE to SpanStyle(color = string),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    replacement: String,
    onReplacement: (String) -> Unit,
    matchCase: Boolean,
    onMatchCase: (Boolean) -> Unit,
    wholeWord: Boolean,
    onWholeWord: (Boolean) -> Unit,
    position: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    focus: FocusRequester,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FindField(query, onQuery, "Find", Modifier.weight(1f).focusRequester(focus))
                Text(
                    text = position,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = onPrevious) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
                IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close find") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FindField(replacement, onReplacement, "Replace with", Modifier.weight(1f))
                TextButton(onClick = onReplace, enabled = position.isNotEmpty() && position != "None") {
                    Text("Replace")
                }
                TextButton(onClick = onReplaceAll, enabled = position.isNotEmpty() && position != "None") {
                    Text("All")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = matchCase, onClick = { onMatchCase(!matchCase) }, label = { Text("Match case") })
                FilterChip(selected = wholeWord, onClick = { onWholeWord(!wholeWord) }, label = { Text("Whole words") })
            }
        }
    }
}

@Composable
private fun FindField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

@Composable
private fun StatusBar(value: TextFieldValue, encoding: TextEncoding, crlf: Boolean) {
    val (line, column) = remember(value.text, value.selection.start) { lineAndColumn(value.text, value.selection.start) }
    val words = remember(value.text) { wordCount(value.text) }
    Text(
        text = "Ln $line, Col $column  ·  ${"%,d".format(words)} words  ·  ${"%,d".format(value.text.length)} " +
            "chars  ·  ${encodingName(encoding)}  ·  ${if (crlf) "CRLF" else "LF"}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun SymbolBar(onInsert: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        LazyRow(Modifier.fillMaxWidth()) {
            items(SYMBOLS) { (label, text) ->
                // Not focusable: a tap leaves the keyboard on the text.
                Box(
                    Modifier
                        .clickable { onInsert(text) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun encodingName(encoding: TextEncoding) = when (encoding) {
    TextEncoding.UTF8 -> "UTF-8"
    TextEncoding.UTF8_BOM -> "UTF-8 with BOM"
    TextEncoding.UTF16LE -> "UTF-16 LE"
    TextEncoding.UTF16BE -> "UTF-16 BE"
}

@Composable
private fun FormatDialog(edit: TextEdit, onDismiss: () -> Unit) {
    @Composable
    fun choice(label: String, selected: Boolean, onSelect: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encoding and line endings") },
        text = {
            Column {
                Text("Encoding", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextEncoding.entries.forEach { encoding ->
                    choice(encodingName(encoding), edit.encoding == encoding) { edit.setFormat(encoding, edit.crlf) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Line endings", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                choice("Unix and Android (LF)", !edit.crlf) { edit.setFormat(edit.encoding, false) }
                choice("Windows (CRLF)", edit.crlf) { edit.setFormat(edit.encoding, true) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The file is written this way when it is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun TextSizeDialog(prefs: EditorPrefs, onPrefs: (EditorPrefs) -> Unit, onDismiss: () -> Unit) {
    var size by remember { mutableFloatStateOf(prefs.textSize.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text size") },
        text = {
            Column {
                Text(
                    text = "The quick fox { 123 }",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.roundToInt().sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Slider(
                    value = size,
                    onValueChange = {
                        size = it
                        onPrefs(prefs.copy(textSize = it.roundToInt()))
                    },
                    valueRange = 10f..28f,
                    steps = 17,
                )
                Text(
                    "${size.roundToInt()} sp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
