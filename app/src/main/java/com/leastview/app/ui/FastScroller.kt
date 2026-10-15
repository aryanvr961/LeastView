package com.leastview.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where a scrolling list currently is, in the units the caller wants to talk about (pages, slides, rows). */
class ScrollInfo(
    /** First and last item that is clearly on screen (0-based). */
    val first: Int,
    val last: Int,
    val count: Int,
    /** 0 at the top, 1 at the very end. */
    val fraction: Float,
    /** Average item height and distance scrolled, in px; good enough to estimate pages in a continuous document. */
    val avgItemPx: Float,
    val scrolledPx: Float,
    val viewportPx: Float,
)

/** "4–5/14": which pages are on screen, out of how many. */
fun pageRangeLabel(i: ScrollInfo): String =
    if (i.first == i.last) "${i.first + 1}/${i.count}" else "${i.first + 1}–${i.last + 1}/${i.count}"

/**
 * Drive-style handle on the right edge: appears while scrolling, shows a pill such as "4–5/14",
 * and can be dragged to jump through the document. Place it over (not inside) any zoom layer.
 */
@Composable
fun FastScroller(state: LazyListState, modifier: Modifier = Modifier, label: (ScrollInfo) -> String = ::pageRangeLabel) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) visible = true
        else {
            delay(1600)
            visible = false
        }
    }

    val info by remember(state) { derivedStateOf { measure(state) } }
    val current = info ?: return
    // nothing to jump around in
    if (current.count < 2 || (!state.canScrollForward && !state.canScrollBackward)) return

    val handle = 48.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val trackPx = with(density) { (maxHeight - handle).toPx() }.coerceAtLeast(1f)
        val shown = if (dragging) dragFraction else current.fraction
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (shown * trackPx).toInt()) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                Text(
                    label(current),
                    Modifier.background(Color(0xE6202124), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 9.dp),
                    color = Color.White,
                    fontSize = 16.sp,
                )
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(handle)
                        .background(Color(0xE6202124), CircleShape)
                        .pointerInput(state) {
                            detectDragGestures(
                                onDragStart = { dragging = true; dragFraction = measure(state)?.fraction ?: 0f },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, drag ->
                                change.consume()
                                dragFraction = (dragFraction + drag.y / trackPx).coerceIn(0f, 1f)
                                scope.launch { jumpTo(state, dragFraction) }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy((-9).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(24.dp), tint = Color.White)
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(24.dp), tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun measure(state: LazyListState): ScrollInfo? {
    val li = state.layoutInfo
    val items = li.visibleItemsInfo
    val count = li.totalItemsCount
    if (items.isEmpty() || count == 0) return null
    val viewport = (li.viewportEndOffset - li.viewportStartOffset).toFloat().coerceAtLeast(1f)
    val avg = items.map { it.size }.average().toFloat().coerceAtLeast(1f)

    val head = items.first()
    val position = head.index + (-head.offset).toFloat() / max(head.size, 1) // in items, fractional
    val maxPosition = max(1f, count - viewport / avg)
    val fraction = when {
        !state.canScrollBackward -> 0f
        !state.canScrollForward -> 1f
        else -> (position / maxPosition).coerceIn(0f, 1f)
    }

    // an item counts as "on screen" once a fair share of it (or of the screen) shows
    val clear = items.filter { it ->
        val shown = min(it.offset + it.size, li.viewportEndOffset) - max(it.offset, li.viewportStartOffset)
        shown >= 0.25f * min(it.size, viewport.toInt())
    }
    val first = (clear.firstOrNull() ?: head).index
    val last = (clear.lastOrNull() ?: head).index
    return ScrollInfo(first, last, count, fraction, avg, position * avg, viewport)
}

private suspend fun jumpTo(state: LazyListState, fraction: Float) {
    val info = measure(state) ?: return
    val maxPosition = max(1f, info.count - info.viewportPx / info.avgItemPx)
    val position = fraction * maxPosition
    val index = floor(position).toInt().coerceIn(0, info.count - 1)
    state.scrollToItem(index, ((position - index) * info.avgItemPx).toInt())
}

/**
 * For continuous documents (Word) that have no real pages: estimates page numbers from how far
 * the content has scrolled, given the height of one page in px.
 */
fun estimatedPageLabel(pageHeightPx: Float): (ScrollInfo) -> String = { i ->
    val total = max(1, ceil(i.avgItemPx * i.count / pageHeightPx).toInt())
    val top = (floor(i.scrolledPx / pageHeightPx).toInt() + 1).coerceIn(1, total)
    val bottom = (floor((i.scrolledPx + i.viewportPx - 1f) / pageHeightPx).toInt() + 1).coerceIn(top, total)
    if (top == bottom) "$top/$total" else "$top–$bottom/$total"
}
