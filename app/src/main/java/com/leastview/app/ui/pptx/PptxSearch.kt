package com.leastview.app.ui.pptx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.office.pptx.PptxDeck
import com.leastview.app.office.pptx.Shape
import com.leastview.app.office.pptx.TableShape
import com.leastview.app.office.pptx.TextShape
import com.leastview.app.ui.docx.Highlight

/**
 * One occurrence. [unit] says where inside [shape]: a paragraph index for text shapes,
 * or `row * 1000 + column` for table cells. [start]..[end] index into that unit's text.
 */
class PptxHit(val slide: Int, val shape: Shape, val unit: Int, val start: Int, val end: Int)

class PptxSearch(private val deck: PptxDeck) {
    var query by mutableStateOf("")
        private set
    val hits = mutableStateListOf<PptxHit>()
    var current by mutableIntStateOf(-1)
        private set

    private var byShape: Map<Shape, List<Int>> = emptyMap()
    private var revision by mutableIntStateOf(0)

    val currentHit: PptxHit? get() = hits.getOrNull(current)

    fun run(q: String, keepPosition: Boolean = false) {
        val previous = if (keepPosition) currentHit else null
        query = q
        hits.clear()
        val needle = q.trim()
        if (needle.isEmpty()) {
            byShape = emptyMap()
            current = -1
            revision++
            return
        }
        val found = ArrayList<PptxHit>()
        val index = HashMap<Shape, MutableList<Int>>()
        fun scan(slide: Int, shape: Shape, unit: Int, text: String) {
            var from = 0
            while (true) {
                val i = text.indexOf(needle, from, ignoreCase = true)
                if (i < 0) break
                index.getOrPut(shape) { ArrayList() }.add(found.size)
                found += PptxHit(slide, shape, unit, i, i + needle.length)
                from = i + needle.length
            }
        }
        deck.slides.forEachIndexed { si, slide ->
            for (shape in slide.shapes) when (shape) {
                is TextShape -> shape.paragraphs.forEachIndexed { pi, p -> scan(si, shape, pi, p.runs.joinToString("") { it.text }) }
                is TableShape -> shape.rows.forEachIndexed { ri, row -> row.cells.forEachIndexed { ci, cell -> scan(si, shape, ri * 1000 + ci, cell.text) } }
                else -> Unit
            }
        }
        hits.addAll(found)
        byShape = index
        current = when {
            found.isEmpty() -> -1
            previous != null -> found.indexOfFirst { it.shape === previous.shape && it.unit == previous.unit && it.start == previous.start }.takeIf { it >= 0 } ?: 0
            else -> 0
        }
        revision++
    }

    fun next() {
        if (hits.isNotEmpty()) current = (current + 1) % hits.size
    }

    fun previous() {
        if (hits.isNotEmpty()) current = (current - 1 + hits.size) % hits.size
    }

    /** Highlights for one paragraph / table cell. Reading this subscribes the caller to result changes. */
    fun highlights(shape: Shape, unit: Int): List<Highlight> {
        revision
        val idx = byShape[shape] ?: return emptyList()
        val cur = current
        return idx.mapNotNull { i -> hits.getOrNull(i)?.takeIf { it.unit == unit }?.let { Highlight(it.start, it.end, i == cur) } }
    }
}
