package com.leastview.app.office.pptx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.office.OoxmlPackage
import org.w3c.dom.Document
import org.w3c.dom.Element

class PRun(
    val text: String,
    val sizePt: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val color: String?,
    val font: String?,
)

class PPara(
    val runs: List<PRun>,
    val align: String?,
    val bullet: String?,
    val marLEmu: Long,
    val indentEmu: Long,
    val spaceBeforePt: Float,
    val lineMult: Float,
)

class TCell(val text: String, val sizePt: Float, val bold: Boolean, val color: String?, val fill: String?)
class TRow(val heightEmu: Long, val cells: List<TCell>)

sealed class Shape(
    val x: Long, val y: Long, val w: Long, val h: Long,
    val rotDeg: Float, val flipH: Boolean, val flipV: Boolean,
)

class TextShape(
    x: Long, y: Long, w: Long, h: Long, rot: Float, flipH: Boolean, flipV: Boolean,
    val geom: String,
    val fill: String?,
    val line: String?,
    val lineWidthEmu: Long,
    val insetsEmu: LongArray,
    val anchor: String,
    val fontScale: Float,
    val isLine: Boolean,
    val element: Element,
    private val reparse: () -> List<PPara>,
    paragraphs: List<PPara>,
) : Shape(x, y, w, h, rot, flipH, flipV) {
    var paragraphs by mutableStateOf(paragraphs)
        private set

    val plainText: String get() = paragraphs.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
    val hasText: Boolean get() = paragraphs.any { p -> p.runs.any { it.text.isNotEmpty() } }

    fun refresh() {
        paragraphs = reparse()
    }
}

class PicShape(
    x: Long, y: Long, w: Long, h: Long, rot: Float, flipH: Boolean, flipV: Boolean,
    val path: String,
    /** left, top, right, bottom crop as fractions of the source image. */
    val crop: FloatArray,
) : Shape(x, y, w, h, rot, flipH, flipV)

class TableShape(
    x: Long, y: Long, w: Long, h: Long,
    val colWidthsEmu: List<Long>,
    val rows: List<TRow>,
) : Shape(x, y, w, h, 0f, false, false)

class PlaceholderShape(
    x: Long, y: Long, w: Long, h: Long,
    val label: String,
) : Shape(x, y, w, h, 0f, false, false)

class Slide(
    val path: String,
    val dom: Document,
    val background: String?,
    val shapes: List<Shape>,
) {
    var dirty = false
}

class PptxDeck(
    val pkg: OoxmlPackage,
    val widthEmu: Long,
    val heightEmu: Long,
    val slides: List<Slide>,
)
