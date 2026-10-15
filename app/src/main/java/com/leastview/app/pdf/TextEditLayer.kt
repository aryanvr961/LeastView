package com.leastview.app.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.leastview.app.tools.LineFamily
import com.leastview.app.ui.highlightColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lets the user tap a line of text on a page and retype it where it stands. Edited lines are shown the way they
 * will be saved: the original covered by the page colour, the new text on top.
 */
@Composable
fun TextEditLayer(
    page: Int,
    widthPt: Float,
    heightPt: Float,
    edit: PdfTextEdit,
    source: PdfLineSource,
    pageBitmap: () -> Bitmap?,
) {
    val lines by produceState<List<TextLine>?>(null, source, page) { value = source.lines(page) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pxPerPt = w / widthPt

        fun styleFor(e: LineEdit) = TextStyle(
            color = Color(e.color or (0xFF shl 24)),
            fontSize = with(density) { (e.sizePt * pxPerPt).toSp() },
            lineHeight = with(density) { (e.sizePt * 1.2f * pxPerPt).toSp() },
            fontFamily = when (e.line.family) {
                LineFamily.SERIF -> FontFamily.Serif
                LineFamily.MONO -> FontFamily.Monospace
                LineFamily.SANS -> FontFamily.SansSerif
            },
            fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (e.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = listOfNotNull(
                TextDecoration.Underline.takeIf { e.underline },
                TextDecoration.LineThrough.takeIf { e.strike },
            ).let { if (it.isEmpty()) null else TextDecoration.combine(it) },
            background = e.highlight?.let { highlightColors[it] } ?: Color.Unspecified,
        )

        /** Top-left of a line's text block, so its baseline lands on the original baseline. */
        fun topOf(e: LineEdit): Float = (e.line.baseline - 0.95f * e.sizePt / heightPt) * h

        // what the user has changed on this page, drawn live
        Canvas(Modifier.fillMaxSize()) {
            for (e in edit.edits.values) {
                if (e.line.page != page || !e.changed && edit.active != e.line.key) continue
                val padX = 0.12f * e.line.sizePt * pxPerPt
                val l = e.line
                drawRect(
                    Color(e.cover or (0xFF shl 24)),
                    Offset(l.x * size.width - padX, l.top * size.height - padX),
                    Size(l.width * size.width + 2 * padX, (l.bottom - l.top) * size.height + 2 * padX),
                )
                if (edit.active != l.key && e.text.isNotEmpty()) {
                    drawText(measurer, e.text, Offset(l.x * size.width, topOf(e)), styleFor(e), softWrap = false)
                }
            }
        }

        // tapping a line starts editing it; tapping elsewhere stops
        Box(
            Modifier.fillMaxSize().pointerInput(lines) {
                detectTapGestures { pos ->
                    val fx = pos.x / size.width
                    val fy = pos.y / size.height
                    val hit = lines?.firstOrNull { l ->
                        fx in (l.x - 0.012f)..(l.x + l.width + 0.012f) && fy in (l.top - 0.006f)..(l.bottom + 0.006f)
                    }
                    if (hit == null) edit.deactivate()
                    else {
                        val bmp = pageBitmap()
                        val cover = bmp?.let { sampleBackground(it, hit) } ?: 0xFFFFFFFF.toInt()
                        edit.activate(hit, cover, bmp?.let { sampleInk(it, hit, cover) } ?: 0xFF000000.toInt())
                    }
                }
            },
        )

        val active = edit.activeEdit?.takeIf { it.line.page == page }
        if (active != null) {
            ActiveLine(active, edit, ::styleFor, IntOffset((active.line.x * w).roundToInt(), topOf(active).roundToInt()), with(density) { (active.line.width * w).toDp() })
        }
    }
}

@Composable
private fun ActiveLine(
    e: LineEdit,
    edit: PdfTextEdit,
    style: (LineEdit) -> TextStyle,
    at: IntOffset,
    minWidth: androidx.compose.ui.unit.Dp,
) {
    var value by remember(e.line.key) { mutableStateOf(TextFieldValue(e.text, TextRange(e.text.length))) }
    // undo/redo change the text from outside the field, so follow the model
    val shown = if (value.text == e.text) value else TextFieldValue(e.text, TextRange(min(value.selection.end, e.text.length)))
    val focus = remember { FocusRequester() }
    LaunchedEffect(e.line.key) { focus.requestFocus() }

    BasicTextField(
        value = shown,
        onValueChange = {
            value = it
            if (it.text != e.text) edit.typing(it.text)
        },
        singleLine = true,
        textStyle = style(e),
        cursorBrush = SolidColor(Color(0xFF1A73E8)),
        modifier = Modifier
            .offset { at }
            .widthIn(min = minWidth)
            .background(Color(e.cover or (0xFF shl 24)))
            .focusRequester(focus),
    )
}

// ---------------------------------------------------------------- colours from the rendered page

/** The page colour around a line: the most common colour among pixels just outside its box. */
internal fun sampleBackground(bmp: Bitmap, l: TextLine): Int {
    val votes = HashMap<Int, Int>()
    val w = bmp.width
    val h = bmp.height
    fun vote(fx: Float, fy: Float) {
        val x = (fx * w).toInt().coerceIn(0, w - 1)
        val y = (fy * h).toInt().coerceIn(0, h - 1)
        val c = bmp.getPixel(x, y)
        // coarse buckets so anti-aliasing noise still counts as one colour
        val key = ((c shr 16 and 0xF8) shl 16) or ((c shr 8 and 0xF8) shl 8) or (c and 0xF8)
        votes[key] = (votes[key] ?: 0) + 1
    }
    val m = 0.006f
    for (i in 0..8) {
        val fx = l.x + l.width * i / 8f
        vote(fx, l.top - m)
        vote(fx, l.bottom + m)
    }
    vote(l.x - m * 2, l.baseline)
    vote(l.x + l.width + m * 2, l.baseline)
    val winner = votes.maxByOrNull { it.value }?.key ?: 0xF8F8F8
    return 0xFF000000.toInt() or (winner or 0x070707) // the bucket's brighter edge, so white stays white
}

/** The text colour: the pixel inside the line's box that differs most from the background. */
internal fun sampleInk(bmp: Bitmap, l: TextLine, background: Int): Int {
    val w = bmp.width
    val h = bmp.height
    val x0 = (l.x * w).toInt().coerceIn(0, w - 1)
    val x1 = ((l.x + l.width) * w).toInt().coerceIn(x0 + 1, w)
    val y0 = (l.top * h).toInt().coerceIn(0, h - 1)
    val y1 = (l.bottom * h).toInt().coerceIn(y0 + 1, h)
    var best = background
    var bestDistance = -1
    val stepX = maxOf(1, (x1 - x0) / 80)
    val stepY = maxOf(1, (y1 - y0) / 12)
    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            val c = bmp.getPixel(x, y)
            val d = abs((c shr 16 and 0xFF) - (background shr 16 and 0xFF)) + abs((c shr 8 and 0xFF) - (background shr 8 and 0xFF)) + abs((c and 0xFF) - (background and 0xFF))
            if (d > bestDistance) { bestDistance = d; best = c }
            x += stepX
        }
        y += stepY
    }
    return if (bestDistance < 60) { // nothing stands out: fall back to black or white, whichever reads on the background
        val lum = 0.299 * (background shr 16 and 0xFF) + 0.587 * (background shr 8 and 0xFF) + 0.114 * (background and 0xFF)
        if (lum > 140) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    } else best or (0xFF shl 24)
}
