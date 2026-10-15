package com.leastview.app.pdf

import android.content.Context
import com.leastview.app.tools.LineFamily
import com.leastview.app.tools.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer

data class LineKey(val page: Int, val index: Int)

/** One line of text on a PDF page, with where it sits (fractions of the displayed page, y down) and how it looks. */
class TextLine(
    val page: Int,
    val index: Int,
    val text: String,
    /** Left edge, baseline and width. */
    val x: Float,
    val baseline: Float,
    val width: Float,
    val sizePt: Float,
    val family: LineFamily,
    val bold: Boolean,
    val italic: Boolean,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
) {
    val key: LineKey get() = LineKey(page, index)

    /** Top and bottom of the line's box as page fractions (cap height above the baseline, descenders below). */
    val top: Float get() = baseline - 0.95f * sizePt / pageHeightPt
    val bottom: Float get() = baseline + 0.28f * sizePt / pageHeightPt
}

/**
 * Reads the text of a PDF line by line, with positions, so lines can be edited in place.
 * Words that are far apart on the same baseline (columns, tab stops) become separate lines.
 */
class PdfLineSource(context: Context, private val file: File) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var doc: PDDocument? = null
    private val cache = HashMap<Int, List<TextLine>>()

    suspend fun lines(page: Int): List<TextLine> = withContext(Dispatchers.Default) {
        lock.withLock { cache[page] ?: extract(page).also { cache[page] = it } }
    }

    fun close() {
        val d = doc
        doc = null
        // close under the lock so an extraction that is still running finishes first
        if (d != null) CoroutineScope(Dispatchers.IO).launch { lock.withLock { runCatching { d.close() } } }
    }

    private fun extract(page: Int): List<TextLine> {
        PdfOps.init(appContext)
        val d = doc ?: PdfOps.load(file).also { doc = it }
        val pdPage = d.getPage(page)
        val box = pdPage.cropBox
        val rotated = pdPage.rotation % 180 != 0
        val pw = if (rotated) box.height else box.width
        val ph = if (rotated) box.width else box.height

        val collector = LineCollector()
        collector.sortByPosition = true
        collector.startPage = page + 1
        collector.endPage = page + 1
        collector.writeText(d, object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        })
        collector.flush()

        return collector.segments.mapIndexedNotNull { i, s ->
            val text = s.words.joinToString(" ").trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val name = s.first.font?.name.orEmpty().lowercase()
            TextLine(
                page = page, index = i, text = text,
                x = s.first.xDirAdj / pw, baseline = s.first.yDirAdj / ph,
                width = ((s.right - s.first.xDirAdj) / pw).coerceAtLeast(0.001f),
                sizePt = s.first.fontSizeInPt.coerceIn(4f, 200f),
                family = when {
                    listOf("courier", "mono", "consolas", "typewriter").any { it in name } -> LineFamily.MONO
                    listOf("times", "georgia", "cambria", "garamond", "palatino", "minion", "book", "roman").any { it in name } ||
                        ("serif" in name && "sans" !in name) -> LineFamily.SERIF
                    else -> LineFamily.SANS
                },
                bold = listOf("bold", "black", "heavy", "semibold", "demi").any { it in name } || name.endsWith("-b"),
                italic = listOf("italic", "oblique").any { it in name } || name.endsWith("-i") || name.endsWith("-it"),
                pageWidthPt = pw, pageHeightPt = ph,
            )
        }
    }

    private class Segment(val first: TextPosition) {
        val words = ArrayList<String>()
        var right = first.xDirAdj
    }

    private class LineCollector : PDFTextStripper() {
        val segments = ArrayList<Segment>()
        private var current: Segment? = null

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || text.isBlank()) return
            val first = textPositions.first()
            val last = textPositions.last()
            val size = first.fontSizeInPt.coerceAtLeast(1f)
            current?.let { c ->
                val gap = first.xDirAdj - c.right
                val rise = kotlin.math.abs(first.yDirAdj - c.first.yDirAdj)
                // a big gap (next column) or a different baseline starts a new line
                if (gap > 2.2f * size || gap < -size || rise > 0.5f * size) flush()
            }
            val seg = current ?: Segment(first).also { current = it }
            seg.words += text.trim()
            seg.right = last.xDirAdj + last.widthDirAdj
        }

        override fun writeLineSeparator() = flush()

        fun flush() {
            current?.let { segments += it }
            current = null
        }
    }
}
