package com.filemanager.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A row of a list being dragged by its handle to a new place in it.
 *
 * The rows are taken to be all the same height, as a file list's are, so
 * where the dragged one is headed comes from how many rows it has been
 * dragged - not from the list's layout, which runs a frame behind each move
 * and would have the row jump back and forth across its neighbour.
 */
@Stable
class RowDrag<T>(private val keyOf: (T) -> Any) {

    /** The key of the row being dragged; null when none is. */
    var dragging by mutableStateOf<Any?>(null)
        private set

    /** The order on screen while a row is dragged; null otherwise. */
    var order by mutableStateOf<List<T>?>(null)
        private set

    private var start = emptyList<T>()
    private var from = 0
    private var rowHeight = 1f
    private var moved by mutableFloatStateOf(0f)

    /** The row keyed [key], [rowHeightPx] tall, picked up from [items]. */
    fun start(items: List<T>, key: Any, rowHeightPx: Int) {
        val index = items.indexOfFirst { keyOf(it) == key }
        if (index < 0 || rowHeightPx <= 0) return
        start = items
        from = index
        rowHeight = rowHeightPx.toFloat()
        moved = 0f
        dragging = key
        order = items
    }

    fun drag(dy: Float) {
        if (dragging == null) return
        moved += dy
        val to = (from + (moved / rowHeight).roundToInt()).coerceIn(0, start.lastIndex)
        if (to != place) order = start.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** How far the dragged row is drawn from the place it holds now: under the finger. */
    fun offsetOf(key: Any): Float = if (key != dragging) 0f else moved - (place - from) * rowHeight

    /** Let go. The order it ended in, or null when nothing was being dragged. */
    fun end(): List<T>? {
        val ended = order.takeIf { dragging != null }
        dragging = null
        order = null
        moved = 0f
        return ended
    }

    private val place: Int
        get() = order?.indexOfFirst { keyOf(it) == dragging }?.takeIf { it >= 0 } ?: from
}

/**
 * The ⌃⌄ handle One UI drags a row by. Holding it never picks the row - the
 * press goes no further - so a slow start to a drag does not select it.
 */
@Composable
fun DragHandle(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Icon(
        Icons.Default.UnfoldMore,
        contentDescription = "Drag to move",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(40.dp)
            // Taken here, so the row's own tap and hold never see it.
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onDrag = { change, amount ->
                        change.consume()
                        move(amount.y)
                    },
                )
            },
    )
}
