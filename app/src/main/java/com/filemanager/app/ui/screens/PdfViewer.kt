package com.filemanager.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.viewer.PdfDocument
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far pages can be zoomed in. */
private const val MAX_ZOOM = 5f

/** Where a double tap zooms to. */
private const val TAP_ZOOM = 2.5f

/**
 * Pages are redrawn at up to this multiple of the screen's width when zoomed,
 * so text stays sharp. More would cost memory for little gain: a whole page
 * at twice the width is already some 25 MB.
 */
private const val MAX_SHARPNESS = 2f

/**
 * The pages of a PDF, one under another, with pinch and double-tap zoom.
 *
 * Zooming scales the list as a picture rather than laying it out again: the
 * list keeps scrolling itself, with its own fling, and the zoom decides which
 * part of it is on screen. Seen from the top-left corner, the screen shows the
 * list's top 1/zoom, across as far as the sideways pan says. To keep the
 * point between the fingers still, a zoom moves that pan and scrolls the list.
 * Pages are redrawn sharper once a zoom settles.
 */
@Composable
fun PdfViewer(document: PdfDocument, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    // Always between -(zoom - 1) x width and 0: never past either edge.
    var panX by remember { mutableFloatStateOf(0f) }
    var sharpness by remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val gap = 8.dp
        val pageWidth = width - with(density) { (gap * 2).toPx() }

        fun panBy(pan: Offset) {
            panX = (panX + pan.x).coerceIn(-(zoom - 1f) * width, 0f)
            if (pan.y != 0f) listState.dispatchRawDelta(-pan.y / zoom)
        }

        fun zoomBy(factor: Float, focus: Offset) {
            val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
            if (next == zoom) return
            val change = next / zoom
            // The list point under the fingers is focus.y / zoom below its
            // visible top; scrolling by the difference keeps it there.
            listState.dispatchRawDelta(focus.y / zoom - focus.y / next)
            panX = (focus.x - (focus.x - panX) * change).coerceIn(-(next - 1f) * width, 0f)
            zoom = next
        }

        // Redrawing at every step of a pinch would be wasted; once it ends,
        // one redraw at the zoom it ended on.
        fun settle() {
            sharpness = ((zoom * 4f).roundToInt() / 4f).coerceIn(1f, MAX_SHARPNESS)
        }

        val gestures = Modifier
            .pointerInput(width, height) {
                awaitEachGesture {
                    // Seen before the list does, so a pinch is never taken
                    // for a scroll.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    var panning = false
                    var drag = Offset.Zero
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            pinched = true
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid != Offset.Unspecified) {
                                zoomBy(event.calculateZoom(), centroid)
                                panBy(event.calculatePan())
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        // One finger scrolls the list up and down itself;
                        // sideways, which the list leaves alone, pans a
                        // zoomed page.
                        val after = awaitPointerEvent(PointerEventPass.Main)
                        if (fingers == 1 && zoom > 1f && after.changes.none { it.isConsumed }) {
                            val delta = after.calculatePan()
                            if (!panning) {
                                drag += delta
                                panning = abs(drag.x) > viewConfiguration.touchSlop
                            }
                            if (panning) {
                                panBy(delta)
                                after.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (after.changes.any { it.pressed })
                    if (pinched) settle()
                }
            }
            .pointerInput(width, height) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        scope.launch {
                            val target = if (zoom > 1.05f) 1f else TAP_ZOOM
                            animate(zoom, target, animationSpec = tween(280)) { value, _ ->
                                zoomBy(value / zoom, at)
                            }
                            settle()
                        }
                    },
                )
            }

        // Around the list rather than over it: a parent sees each touch both
        // before the list does and after, and passes on what it leaves.
        Box(Modifier.fillMaxSize().then(gestures)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                // Room below the last page to scroll it into the part of the list
                // that a zoom leaves on screen.
                contentPadding = PaddingValues(
                    start = gap,
                    top = gap,
                    end = gap,
                    bottom = gap + with(density) { (height * (1f - 1f / zoom)).toDp() },
                ),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(document.pageCount) { index ->
                    PdfPage(
                        document = document,
                        index = index,
                        renderWidth = (pageWidth * sharpness).roundToInt().coerceAtLeast(1),
                    )
                }
            }

            PageIndicator(
                visible = listState.isScrollInProgress,
                page = remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }.value,
                count = document.pageCount,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, renderWidth: Int) {
    var page by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    // The previous drawing stays up until the sharper one is ready.
    LaunchedEffect(index, renderWidth) {
        document.render(index, renderWidth)?.let { page = it.asImageBitmap() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(document.pageRatios[index].coerceIn(0.05f, 20f))
            .background(Color.White),
    ) {
        page?.let {
            Image(
                bitmap = it,
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

/** "3 / 12" while scrolling, fading once the list comes to rest. */
@Composable
private fun PageIndicator(visible: Boolean, page: Int, count: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(durationMillis = 400, delayMillis = 700)),
    ) {
        Text(
            text = "$page / $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
