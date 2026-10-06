package com.filemanager.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pull to refresh for a list under a [OneUiScreen]'s title, which refreshes
 * only on a pull that starts at the top: with the list there, [atTop], and
 * the title all the way open, [titleState].
 *
 * A swipe that scrolled up to the top used to carry on into a pull and
 * refresh as it arrived there, when all it was after was the top. The rest
 * of such a swipe now opens the title out again, as One UI does, and a pull
 * from there refreshes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshFromTop(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    /** Whether the list is scrolled to its top, asked as each touch starts. */
    atTop: () -> Boolean,
    titleState: TitleState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pull = rememberPullToRefreshState()
    val listAtTop by rememberUpdatedState(atTop)
    // Settled as a finger comes down, before it has moved: a swipe is a pull
    // or a scroll from its start, whatever it turns into on the way.
    var fromTop by remember { mutableStateOf(true) }

    Box(
        modifier
            .pointerInput(titleState) {
                awaitEachGesture {
                    // Watched on its way down to the list, not taken.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fromTop = listAtTop() && titleState.isOpen
                }
            }
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = pull,
                // Off, the rest of a swipe goes on past it to the title.
                enabled = fromTop,
                onRefresh = onRefresh,
            ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = pull,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
