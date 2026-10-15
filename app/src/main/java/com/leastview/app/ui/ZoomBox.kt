package com.leastview.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** [scale] is always 1: zooming happens in a graphics layer and never re-composes the content. */
class ZoomInfo(val scale: Float, val viewWidthPx: Int, val verticalPadding: Dp)

/**
 * Pinch-to-zoom (around the fingers), two-finger pan and double-tap zoom (around the tap) for a
 * vertically scrolling child. The child keeps scrolling natively; when it hits its end while zoomed,
 * the leftover drag pans the zoomed view so every edge of the page stays reachable.
 *
 * The scale and offset live in plain state read only by the graphics layer, so zooming costs a redraw per
 * frame and no recomposition or re-layout of the page below.
 */
@Composable
fun ZoomBox(modifier: Modifier = Modifier, maxScale: Float = 4f, content: @Composable (ZoomInfo) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun clampOffsets() {
        tx = tx.coerceIn(-(width * (scale - 1f)), 0f)
        ty = ty.coerceIn(-(height * (scale - 1f)), 0f)
    }

    /** Sets a new scale keeping the content under ([fx], [fy]) where it is on screen. */
    fun zoomAbout(newScale: Float, fx: Float, fy: Float) {
        val s = newScale.coerceIn(1f, maxScale)
        val k = s / scale
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        scale = s
        clampOffsets()
    }

    val leftoverToPan = remember {
        object : NestedScrollConnection {
            // the list couldn't use all of a vertical drag: spend the rest panning the zoomed layer
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (scale <= 1f || available.y == 0f) return Offset.Zero
                val before = ty
                ty = (ty + available.y * scale).coerceIn(-(height * (scale - 1f)), 0f)
                return Offset(0f, (ty - before) / scale)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                width = it.width
                height = it.height
                clampOffsets()
            }
            .nestedScroll(leftoverToPan)
            // two fingers: seen before the child, so the list doesn't scroll while the user pinches
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f || pan != Offset.Zero) {
                                zoomAbout(scale * zoom, centroid.x, centroid.y)
                                tx += pan.x
                                ty += pan.y
                                clampOffsets()
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // one finger, zoomed in: drags the child didn't use (sideways ones for a list, any for a picture) move the view
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.singleOrNull()
                        if (scale > 1f && change != null && !change.isConsumed) {
                            val d = change.positionChange()
                            if (d != Offset.Zero) {
                                tx += d.x
                                ty += d.y
                                clampOffsets()
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at ->
                    val from = scale
                    val to = if (scale > 1.05f) 1f else 2.5f
                    scope.launch {
                        animate(0f, 1f, animationSpec = tween(220)) { f, _ -> zoomAbout(from + (to - from) * f, at.x, at.y) }
                    }
                })
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = tx
                translationY = ty
            },
        ) {
            content(ZoomInfo(1f, width, 0.dp))
        }
    }
}
