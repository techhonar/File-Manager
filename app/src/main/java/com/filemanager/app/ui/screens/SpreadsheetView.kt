package com.filemanager.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.filemanager.app.data.viewer.CellAlign
import com.filemanager.app.data.viewer.CellVertical
import com.filemanager.app.data.viewer.SheetCell
import com.filemanager.app.data.viewer.SheetColumn
import com.filemanager.app.data.viewer.SheetRow
import com.filemanager.app.data.viewer.Spreadsheet
import com.filemanager.app.data.viewer.Worksheet
import com.filemanager.app.data.viewer.columnName
import com.filemanager.app.data.viewer.columnOf
import com.filemanager.app.data.viewer.general
import com.filemanager.app.ui.theme.OneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * An Excel workbook in the viewer: each sheet as a grid that scrolls both
 * ways, with its column letters and row numbers held at the edges as Excel
 * holds them, and the sheets as tabs underneath. Tapping a cell shows all of
 * it below the grid - text too long for its column, or a number too wide.
 */

@Composable
internal fun SpreadsheetView(book: Spreadsheet, onOpenWith: () -> Unit) {
    if (book.sheetNames.isEmpty()) {
        Message("This workbook has no sheets to show here.", onOpenWith)
        return
    }
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    val index = chosen.coerceIn(0, book.sheetNames.lastIndex)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Each sheet afresh: read with the spinner showing, rather than
            // the last sheet staying up meanwhile, and shown from A1 rather
            // than where the last one was scrolled to.
            key(index) {
                val load by produceState<Load<Worksheet>>(Load.Reading, book) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            Load.Read(book.sheet(index))
                        } catch (e: Throwable) {
                            Load.Failed(describe(e))
                        }
                    }
                }
                LoadedPane(load, onOpenWith) { SheetGrid(it) }
            }
        }
        if (book.sheetNames.size > 1) SheetTabs(book.sheetNames, index) { chosen = it }
    }
}

/** Excel's usual text, Calibri 11, which a column of the usual width fits about nine digits of. */
private val CellText = TextStyle(fontSize = 12.sp)

/** The cell a tap chose: a row of the sheet as shown - the first, for a cell merged down - and a column. */
private data class CellPosition(val row: Int, val column: Int)

@Composable
private fun SheetGrid(read: Worksheet) {
    if (read.rows.isEmpty() || read.columns.isEmpty()) {
        Message(
            if (read.hasDrawings) "This sheet has only charts or pictures, which can't be shown here." else "This sheet is empty.",
            onOpenWith = null,
        )
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // A unit of the sheet - one of Excel's pixels - drawn the size of an
        // sp, so the grid grows with the text when the phone's font is made larger.
        val unit = density.density * density.fontScale
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        // Filled out once, past the edges of the pane: it grows as the large
        // title above it collapses, and a sheet made again on every frame of
        // that lost the chosen cell and measured all its text again.
        val sheet = remember(read, unit) { padToFill(read, width * 1.25f / unit, height * 1.5f / unit) }
        val selection = remember(read) { mutableStateOf<CellPosition?>(null) }
        SheetGrid(sheet, unit, viewport = width.coerceAtLeast(1f), selection = selection)
    }
}

@Composable
private fun SheetGrid(sheet: Worksheet, unit: Float, viewport: Float, selection: MutableState<CellPosition?>) {
    val density = LocalDensity.current
    val grid = remember(sheet, unit) { GridLayout(sheet, unit) }
    val horizontal = rememberScrollState()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    // Read only where it is drawn, so choosing a cell redraws the rows it is
    // in rather than measuring every row again.
    val select = remember(selection) {
        { position: CellPosition -> selection.value = if (selection.value == position) null else position }
    }
    // The part of the sheet across that is measured: what is on screen and a
    // screen either side, moving on a screen at a time. A row measured all
    // of its hundred columns as it scrolled into view, for the four shown.
    val window = remember(horizontal, viewport, grid) {
        derivedStateOf {
            val from = (horizontal.value / viewport).toInt() * viewport - grid.headerWidth - viewport
            from..(from + 4 * viewport)
        }
    }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme.outlineVariant, scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.onSurface, scheme.primary) {
        GridColors(
            line = scheme.outlineVariant,
            header = scheme.surfaceVariant,
            headerText = scheme.onSurfaceVariant,
            text = scheme.onSurface,
            selection = scheme.primary,
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (sheet.truncated) {
            Text(
                text = "This sheet is large, so only its beginning is shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (sheet.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal)) {
                Column(Modifier.width(with(density) { (grid.headerWidth + grid.width).toDp() }).fillMaxHeight()) {
                    ColumnHeaders(sheet, grid, horizontal, measurer, colors)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(sheet.rows, key = { _, row -> row.number }) { index, row ->
                            SheetRowView(
                                index = index,
                                row = row,
                                grid = grid,
                                horizontal = horizontal,
                                measurer = measurer,
                                colors = colors,
                                window = { window.value },
                                selected = { selection.value },
                                onTap = select,
                            )
                        }
                    }
                }
            }
        }
        SelectedCell(sheet, selection)
    }
}

/** The chosen cell in full, under the grid. Reads the choice itself, so only this changes with it. */
@Composable
private fun SelectedCell(sheet: Worksheet, selection: MutableState<CellPosition?>) {
    val position = selection.value ?: return
    val (address, text) = describeCell(sheet, position)
    CellBar(address, text, onClose = { selection.value = null })
}

/**
 * [sheet] with empty columns and rows after its own to fill [width] by
 * [height] - in the sheet's units - as Excel always shows more of the grid
 * than is used, and as text running on past the last column needs.
 */
private fun padToFill(sheet: Worksheet, width: Float, height: Float): Worksheet {
    val lastColumn = sheet.columns.lastOrNull()?.name?.let(::columnOf) ?: 0
    val columns = sheet.columns.toMutableList()
    var across = columns.sumOf { it.width.toDouble() }.toFloat()
    var next = lastColumn + 1
    while (across < width && next <= MAX_PADDED_COLUMN) {
        columns += SheetColumn(columnName(next++), PADDING_COLUMN_WIDTH)
        across += PADDING_COLUMN_WIDTH
    }
    val rows = sheet.rows.toMutableList()
    var down = rows.sumOf { it.height.toDouble() }.toFloat()
    var number = (rows.lastOrNull()?.number ?: 0) + 1
    while (down < height) {
        rows += SheetRow(number++, PADDING_ROW_HEIGHT, emptyList())
        down += PADDING_ROW_HEIGHT
    }
    if (columns.size == sheet.columns.size && rows.size == sheet.rows.size) return sheet
    return Worksheet(columns, rows, sheet.rightToLeft, sheet.hasDrawings, sheet.truncated)
}

private const val PADDING_COLUMN_WIDTH = 64f
private const val PADDING_ROW_HEIGHT = 20f
private const val MAX_PADDED_COLUMN = 16_384

private class GridColors(val line: Color, val header: Color, val headerText: Color, val text: Color, val selection: Color)

/** Where a sheet's columns and rows are drawn, in pixels. */
private class GridLayout(val sheet: Worksheet, val unit: Float) {
    /** How many of the columns are drawn: as many as one layout can measure across. */
    val count: Int

    /** Each column's start edge, and the end edge of the last one drawn. */
    val starts: FloatArray

    init {
        val edges = ArrayList<Float>(sheet.columns.size + 1).apply { add(0f) }
        for (column in sheet.columns) {
            val next = edges.last() + column.width * unit
            if (next > MAX_PIXELS) break
            edges += next
        }
        starts = edges.toFloatArray()
        count = starts.size - 1
    }

    val width: Float get() = starts[count]

    val headerWidth: Float = run {
        val digits = (sheet.rows.lastOrNull()?.number ?: 1).toString().length
        maxOf(digits * 7f + 14f, 30f) * unit
    }

    val headerHeight: Float = 22f * unit

    val padding: Float = 3f * unit

    /** Each row's top, from the first, and the bottom of the last: where a cell merged down lies across its rows. */
    val rowTops: FloatArray = FloatArray(sheet.rows.size + 1).also { tops ->
        for ((i, row) in sheet.rows.withIndex()) tops[i + 1] = tops[i] + height(row)
    }

    /** For each row of a cell merged down, by row and column: the rows it covers. */
    private val blocks = HashMap<Long, IntRange>()

    init {
        val open = HashMap<Int, Int>()
        val rows = HashMap<Int, ArrayList<Int>>()
        for ((i, row) in sheet.rows.withIndex()) {
            for (cell in row.cells) {
                if (!cell.continues && !cell.continued) continue
                if (!cell.continued) open[cell.column] = i
                val first = open[cell.column] ?: i
                rows.getOrPut(cell.column) { ArrayList() } += i
                if (!cell.continues) {
                    for (r in rows.remove(cell.column).orEmpty()) blocks[key(r, cell.column)] = first..i
                    open.remove(cell.column)
                }
            }
        }
    }

    /** The rows [cell], in row [index], covers when it is merged down; null for one in a row of its own. */
    fun blockOf(index: Int, cell: SheetCell): IntRange? =
        if (cell.continues || cell.continued) blocks[key(index, cell.column)] else null

    /** The column under [x], measured from the sheet's start edge. */
    fun columnAt(x: Float): Int {
        var low = 0
        var high = count - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= x) low = middle else high = middle - 1
        }
        return low
    }

    /** The cell's end edge: past the columns it spans, merged. */
    fun end(cell: SheetCell): Float = starts[minOf(cell.column + cell.span, count)]

    /** At least as tall as its tallest text, whatever height the sheet gives it. */
    fun height(row: SheetRow): Float {
        val tallest = row.cells.maxOfOrNull { if (it.continued) 1f else it.look.scale } ?: 1f
        return maxOf(row.height, 16f * tallest + 4f) * unit
    }

    private fun key(row: Int, column: Int) = (row.toLong() shl 32) or column.toLong()

    companion object {
        /** Comfortably within the widest a layout can be, at any density and font size. */
        const val MAX_PIXELS = 200_000f
    }
}

@Composable
private fun ColumnHeaders(sheet: Worksheet, grid: GridLayout, horizontal: ScrollState, measurer: TextMeasurer, colors: GridColors) {
    val density = LocalDensity.current
    val height = with(density) { grid.headerHeight.toDp() }
    val style = CellText.copy(textAlign = TextAlign.Center)
    Row(Modifier.height(height)) {
        // The corner above the row numbers, held at the edge with them.
        Box(
            Modifier
                .zIndex(1f)
                .offset { IntOffset(horizontal.value, 0) }
                .width(with(density) { grid.headerWidth.toDp() })
                .fillMaxHeight()
                .background(colors.header)
                .drawBehind { edgeLines(colors.line) },
        )
        Box(
            Modifier
                .width(with(density) { grid.width.toDp() })
                .fillMaxHeight()
                .background(colors.header)
                .drawWithCache {
                    val rightToLeft = layoutDirection == LayoutDirection.Rtl
                    val labels = (0 until grid.count).map { c ->
                        val layout = measurer.measure(sheet.columns[c].name, style, softWrap = false, maxLines = 1, layoutDirection = layoutDirection)
                        val start = grid.starts[c]
                        val width = grid.starts[c + 1] - start
                        val x = start + (width - layout.size.width) / 2
                        val left = if (rightToLeft) size.width - x - layout.size.width else x
                        layout to Offset(left, (size.height - layout.size.height) / 2)
                    }
                    val lines = Path().apply {
                        for (c in 0..grid.count) {
                            val x = mirror(grid.starts[c], rightToLeft, size.width)
                            moveTo(x, 0f)
                            lineTo(x, size.height)
                        }
                        moveTo(0f, size.height)
                        lineTo(size.width, size.height)
                    }
                    val stroke = Stroke(lineWidth())
                    onDrawBehind {
                        drawPath(lines, colors.line, style = stroke)
                        for ((layout, at) in labels) drawText(layout, colors.headerText, at)
                    }
                },
        )
    }
}

@Composable
private fun SheetRowView(
    index: Int,
    row: SheetRow,
    grid: GridLayout,
    horizontal: ScrollState,
    measurer: TextMeasurer,
    colors: GridColors,
    window: () -> ClosedFloatingPointRange<Float>,
    selected: () -> CellPosition?,
    onTap: (CellPosition) -> Unit,
) {
    val density = LocalDensity.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.height(with(density) { grid.height(row).toDp() })) {
        Box(
            Modifier
                .zIndex(1f)
                .offset { IntOffset(horizontal.value, 0) }
                .width(with(density) { grid.headerWidth.toDp() })
                .fillMaxHeight()
                .background(colors.header)
                // Taken here, or a tap on the number went through to a cell
                // hidden under it and chose that.
                .pointerInput(Unit) { detectTapGestures { } }
                .drawBehind { edgeLines(colors.line) },
            contentAlignment = Alignment.Center,
        ) {
            Text(row.number.toString(), style = CellText, color = colors.headerText, maxLines = 1)
        }
        Box(
            Modifier
                .width(with(density) { grid.width.toDp() })
                .fillMaxHeight()
                // Drawn rather than composed, so TalkBack is told the row's cells here.
                .semantics { contentDescription = describeRow(row, grid.sheet) }
                .pointerInput(row, grid, rightToLeft) {
                    detectTapGestures { offset ->
                        val x = if (rightToLeft) size.width - offset.x else offset.x
                        val column = grid.columnAt(x)
                        // A tap anywhere on a merged cell chooses the whole of it.
                        val cell = row.cells.firstOrNull { column >= it.column && column < it.column + it.span }
                        val first = cell?.let { grid.blockOf(index, it)?.first } ?: index
                        onTap(CellPosition(first, cell?.column ?: column))
                    }
                }
                .drawWithCache {
                    val drawing = rowDrawing(index, row, grid, measurer, colors, this, window())
                    val stroke = Stroke(lineWidth())
                    val outline = 2.dp.toPx()
                    onDrawBehind {
                        for (fill in drawing.fills) drawRect(fill.color, fill.topLeft, fill.size)
                        drawPath(drawing.lines, colors.line, style = stroke)
                        for (text in drawing.texts) {
                            clipRect(text.clipLeft, 0f, text.clipRight, size.height) {
                                drawText(text.layout, text.color, text.topLeft)
                            }
                        }
                        selected()?.let { drawSelection(it, index, row, grid, rightToLeft, colors.selection, outline) }
                    }
                },
        )
    }
}

/**
 * The outline of the chosen cell where it crosses this row: all of it for a
 * cell in one row, and for one merged down, its sides, with the top in its
 * first row and the bottom in its last.
 */
private fun DrawScope.drawSelection(
    chosen: CellPosition,
    index: Int,
    row: SheetRow,
    grid: GridLayout,
    rightToLeft: Boolean,
    color: Color,
    width: Float,
) {
    if (chosen.column >= grid.count) return
    val cell = row.cells.firstOrNull { it.column == chosen.column }
    val block = cell?.let { grid.blockOf(index, it) } ?: (index..index)
    if (chosen.row != block.first) return
    val start = grid.starts[chosen.column]
    val end = cell?.let { grid.end(it) } ?: grid.starts[chosen.column + 1]
    val left = if (rightToLeft) size.width - end else start
    val right = left + (end - start)
    val inset = width / 2
    drawLine(color, Offset(left + inset, 0f), Offset(left + inset, size.height), width)
    drawLine(color, Offset(right - inset, 0f), Offset(right - inset, size.height), width)
    if (index == block.first) drawLine(color, Offset(left, inset), Offset(right, inset), width)
    if (index == block.last) drawLine(color, Offset(left, size.height - inset), Offset(right, size.height - inset), width)
}

private class Fill(val color: Color, val topLeft: Offset, val size: Size)

private class PlacedText(val layout: TextLayoutResult, val color: Color, val topLeft: Offset, val clipLeft: Float, val clipRight: Float)

private class RowDrawing(val fills: List<Fill>, val lines: Path, val texts: List<PlacedText>)

/** Which side of the cell [align] puts the text, whichever way the sheet reads: left, right or the middle. */
private fun sideOf(align: CellAlign, rightToLeft: Boolean): CellAlign = when (align) {
    CellAlign.START -> if (rightToLeft) CellAlign.RIGHT else CellAlign.LEFT
    CellAlign.END -> if (rightToLeft) CellAlign.LEFT else CellAlign.RIGHT
    else -> align
}

/**
 * A row's fills, grid lines and text, measured once and drawn on every
 * frame - for the cells within [window] across, in the sheet's own
 * coordinates, which is all that can be on screen.
 *
 * Text too long for its cell runs on over empty cells as Excel lets it: to
 * the right from a cell's left, to the left from its right, both ways from
 * its middle, and across the following cells set to be centred across. A
 * cell merged down is laid out over all its rows, each drawing its own part.
 * Lines are left out inside merged cells, under a merged cell that goes on
 * into the next row, and where text runs on - as Excel leaves them out.
 */
private fun rowDrawing(
    index: Int,
    row: SheetRow,
    grid: GridLayout,
    measurer: TextMeasurer,
    colors: GridColors,
    scope: CacheDrawScope,
    window: ClosedFloatingPointRange<Float>,
): RowDrawing {
    val width = scope.size.width
    val height = scope.size.height
    val rightToLeft = scope.layoutDirection == LayoutDirection.Rtl
    fun across(x: Float) = mirror(x, rightToLeft, width)
    val shown = row.cells.filter { it.column < grid.count }

    // What is in each column, and the columns text from beside can't run
    // over: those with text in them, or part of a merged cell.
    val atColumn = arrayOfNulls<SheetCell>(grid.count)
    val taken = BooleanArray(grid.count)
    for (cell in shown) {
        val last = minOf(cell.column + cell.span, grid.count)
        val blocks = cell.text.isNotEmpty() || cell.span > 1 || cell.continues || cell.continued
        for (c in cell.column until last) {
            atColumn[c] = cell
            if (blocks) taken[c] = true
        }
    }
    // Column edges with no line through them in this row.
    val unbroken = BooleanArray(grid.count + 1)
    val fills = ArrayList<Fill>()
    val texts = ArrayList<PlacedText>()
    val sizes = TextSizes(measurer, scope.layoutDirection)

    for (cell in shown) {
        val start = grid.starts[cell.column]
        val end = grid.end(cell)
        for (edge in cell.column + 1 until minOf(cell.column + cell.span, grid.count)) unbroken[edge] = true
        cell.look.fill?.let { fill ->
            fills += Fill(Color(fill), Offset(minOf(across(start), across(end)), 0f), Size(end - start, height))
        }
        if (cell.text.isEmpty()) continue

        val block = grid.blockOf(index, cell)
        val wrap = cell.look.wrap && !cell.numeric
        val side = sideOf(cell.align, rightToLeft)
        // Where the text may go: its cell, and the empty ones it can run on over.
        var areaStart = start
        var areaEnd = end
        if (cell.spills && !wrap && block == null && cell.span == 1) {
            fun freeAfter(): Float {
                var c = cell.column + 1
                while (c < grid.count && !taken[c]) c++
                return grid.starts[c]
            }
            fun freeBefore(): Float {
                var c = cell.column - 1
                while (c >= 0 && !taken[c]) c--
                return grid.starts[c + 1]
            }
            var run = cell.column + 1
            if (cell.look.centerAcross) {
                while (run < grid.count && atColumn[run]?.let { it.text.isEmpty() && it.look.centerAcross } == true) run++
            }
            when {
                run > cell.column + 1 -> areaEnd = grid.starts[run]
                side == CellAlign.CENTER -> {
                    areaStart = freeBefore()
                    areaEnd = freeAfter()
                }
                // From a cell's left the text runs on to the right: onward in
                // an English sheet, back toward the start in a Persian one.
                (side == CellAlign.LEFT) != rightToLeft -> areaEnd = freeAfter()
                else -> areaStart = freeBefore()
            }
        }
        // Out of sight across: measured once scrolled near.
        if (areaEnd < window.start || areaStart > window.endInclusive) continue

        val style = styleOf(cell, side)
        val inner = (end - start - 2 * grid.padding).coerceAtLeast(1f)
        var layout = if (wrap) {
            // As wide as the cell, so the text is aligned within it.
            sizes.measure(cell.text, style, Constraints.fixedWidth(inner.toInt()))
        } else {
            // One line, as Excel shows a cell that doesn't wrap.
            sizes.line(cell.text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '), style)
        }
        // A number too wide for its column is never cut short: in General it
        // is rounded to fit, as Excel rounds it, and otherwise it is ###.
        if (cell.numeric && !wrap && layout.size.width > inner) {
            layout = cell.number?.let { sizes.fitGeneral(it, style, inner) } ?: sizes.hashes(style, inner)
        }

        val textWidth = layout.size.width.toFloat()
        val cellLeft = minOf(across(start), across(end))
        val cellRight = maxOf(across(start), across(end))
        val areaLeft = minOf(across(areaStart), across(areaEnd))
        val areaRight = maxOf(across(areaStart), across(areaEnd))
        val x = when {
            wrap -> cellLeft + grid.padding
            cell.look.centerAcross && areaEnd > end -> (areaLeft + areaRight - textWidth) / 2
            side == CellAlign.LEFT -> cellLeft + grid.padding
            side == CellAlign.RIGHT -> cellRight - grid.padding - textWidth
            else -> (cellLeft + cellRight - textWidth) / 2
        }
        // Down a merged cell's rows, set against all of them: each row draws
        // the part of the text that falls in it.
        val blockHeight = block?.let { grid.rowTops[it.last + 1] - grid.rowTops[it.first] } ?: height
        val above = block?.let { grid.rowTops[index] - grid.rowTops[it.first] } ?: 0f
        val y = when (cell.look.vertical) {
            CellVertical.TOP -> grid.padding
            CellVertical.CENTER -> (blockHeight - layout.size.height) / 2
            CellVertical.BOTTOM -> blockHeight - layout.size.height - grid.padding / 2
        }.coerceAtLeast(0f) - above

        if (areaStart < start || areaEnd > end) {
            // No lines through text that runs on.
            for (edge in grid.columnAt(areaStart) + 1..grid.columnAt(areaEnd - 1)) {
                val at = across(grid.starts[edge])
                if (at > x && at < x + textWidth) unbroken[edge] = true
            }
        }
        // On a fill, the colour the file gives the text, chosen for that fill.
        val color = cell.look.fill?.let { fill -> cell.look.color?.let(::Color) ?: onFill(Color(fill)) } ?: colors.text
        texts += PlacedText(layout, color, Offset(x, y), clipLeft = areaLeft, clipRight = areaRight)
    }

    val lines = Path()
    for (edge in 0..grid.count) {
        if (unbroken[edge]) continue
        val x = across(grid.starts[edge])
        lines.moveTo(x, 0f)
        lines.lineTo(x, height)
    }
    // The line under the row, broken under merged cells that go on below.
    var from = 0f
    for (cell in shown.filter { it.continues }) {
        val start = grid.starts[cell.column]
        if (start > from) addBottom(lines, from, start, rightToLeft, width, height)
        from = maxOf(from, grid.end(cell))
    }
    if (from < grid.width) addBottom(lines, from, grid.width, rightToLeft, width, height)
    return RowDrawing(fills, lines, texts)
}

/** How a cell's text is set: its font and, for wrapped text, the side it keeps to. */
private fun styleOf(cell: SheetCell, side: CellAlign): TextStyle = CellText.copy(
    fontSize = CellText.fontSize * cell.look.scale,
    fontWeight = if (cell.look.bold) FontWeight.Bold else null,
    fontStyle = if (cell.look.italic) FontStyle.Italic else null,
    textDecoration = listOfNotNull(
        TextDecoration.Underline.takeIf { cell.look.underline },
        TextDecoration.LineThrough.takeIf { cell.look.strike },
    ).takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
    // The cell's sides, not the start and end of the text: a right-aligned
    // cell keeps Persian and English alike to the right.
    textAlign = when (side) {
        CellAlign.LEFT -> TextAlign.Left
        CellAlign.RIGHT -> TextAlign.Right
        else -> TextAlign.Center
    },
)

/** Text measured for one row, with the sizes of a digit and of # kept for each style. */
private class TextSizes(private val measurer: TextMeasurer, private val direction: LayoutDirection) {
    private val digits = HashMap<TextStyle, Int>()
    private val hashes = HashMap<TextStyle, Int>()

    fun measure(text: String, style: TextStyle, constraints: Constraints) =
        measurer.measure(text, style, constraints = constraints, layoutDirection = direction)

    fun line(text: String, style: TextStyle) =
        measurer.measure(text, style, softWrap = false, maxLines = 1, layoutDirection = direction)

    /** [value] in as many of General's characters as fit [room], or null if not even one does. */
    fun fitGeneral(value: Double, style: TextStyle, room: Float): TextLayoutResult? {
        val digit = digits.getOrPut(style) { line("0", style).size.width.coerceAtLeast(1) }
        var characters = (room / digit).toInt().coerceIn(1, 11)
        while (characters >= 1) {
            val layout = line(general(value, characters), style)
            if (layout.size.width <= room) return layout
            characters--
        }
        return null
    }

    fun hashes(style: TextStyle, room: Float): TextLayoutResult {
        val hash = hashes.getOrPut(style) { line("#", style).size.width.coerceAtLeast(1) }
        return line("#".repeat((room / hash).toInt().coerceAtLeast(1)), style)
    }
}

private fun addBottom(path: Path, from: Float, to: Float, rightToLeft: Boolean, width: Float, height: Float) {
    path.moveTo(mirror(from, rightToLeft, width), height)
    path.lineTo(mirror(to, rightToLeft, width), height)
}

private fun mirror(x: Float, rightToLeft: Boolean, width: Float) = if (rightToLeft) width - x else x

private val DarkText = Color(0xFF1F1F1F)

/** Whichever of dark text and white stands out more on [fill], whatever the theme. */
private fun onFill(fill: Color): Color {
    val light = fill.luminance()
    val againstWhite = 1.05f / (light + 0.05f)
    val againstDark = (light + 0.05f) / (DarkText.luminance() + 0.05f)
    return if (againstDark >= againstWhite) DarkText else Color.White
}

private fun Density.lineWidth(): Float = (density * 0.5f).roundToInt().coerceAtLeast(1).toFloat()

/** The lines along a header cell's far side and bottom. */
private fun DrawScope.edgeLines(color: Color) {
    val stroke = lineWidth()
    val farSide = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width
    drawLine(color, Offset(farSide, 0f), Offset(farSide, size.height), stroke)
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), stroke)
}

/** A row read out: its number, then each cell with something in it, by its address. */
private fun describeRow(row: SheetRow, sheet: Worksheet): String {
    val cells = row.cells.filter { it.text.isNotEmpty() && !it.continued }
    if (cells.isEmpty()) return "Row ${row.number}"
    return "Row ${row.number}: " + cells.joinToString(", ") { cell ->
        "${sheet.columns.getOrNull(cell.column)?.name.orEmpty()}${row.number} ${cell.text}"
    }
}

/** A chosen cell's address, as Excel writes it - B4 - and all of its text. A merged cell is named by its first. */
private fun describeCell(sheet: Worksheet, position: CellPosition): Pair<String, String> {
    var rowIndex = position.row.coerceIn(0, sheet.rows.lastIndex)
    var cell = sheet.rows[rowIndex].cells.firstOrNull { it.column == position.column }
    while (cell != null && cell.continued && rowIndex > 0) {
        rowIndex--
        cell = sheet.rows[rowIndex].cells.firstOrNull { it.column == position.column }
    }
    val column = sheet.columns.getOrNull(position.column)?.name.orEmpty()
    return "$column${sheet.rows[rowIndex].number}" to cell?.text.orEmpty()
}

@Composable
private fun CellBar(address: String, text: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = address,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Box(Modifier.weight(1f).heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(
                    text = text.ifEmpty { "Empty" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, "Close")
        }
    }
}

/** The workbook's sheets as pills along the bottom, as Excel puts its sheet tabs. */
@Composable
private fun SheetTabs(names: List<String>, current: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { index, name ->
            val chosen = index == current
            Box(
                modifier = Modifier
                    .clip(OneUi.PillShape)
                    .background(if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(index) }
                    .semantics {
                        role = Role.Tab
                        selected = chosen
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
