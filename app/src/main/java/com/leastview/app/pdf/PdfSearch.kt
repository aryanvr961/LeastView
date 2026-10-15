package com.leastview.app.pdf

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.tools.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer

/** One occurrence of the query: the page and its highlight boxes as fractions (0..1) of that page. */
class PdfMatch(val page: Int, val rects: List<RectF>)

/** Text of one page with, for each character, its box as fractions of the page (null for inserted spaces/newlines). */
private class PageText(val text: String, val boxes: Array<RectF?>)

/**
 * Searches the text inside a PDF. Text comes from PdfBox page by page, so results appear while the
 * rest of the document is still being scanned, and each page is only extracted once.
 */
class PdfSearchState(context: Context, private val file: File, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext

    var query by mutableStateOf("")
        private set
    val matches = mutableStateListOf<PdfMatch>()
    var current by mutableIntStateOf(-1)
        private set

    /** Pages scanned so far / total, while a scan runs. */
    var scanned by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var scanning by mutableStateOf(false)
        private set

    /** Set when the search can't give a meaningful answer (scanned PDF, password, damaged file). */
    var problem by mutableStateOf<String?>(null)
        private set

    val currentMatch: PdfMatch? get() = matches.getOrNull(current)

    private var job: Job? = null
    private var doc: PDDocument? = null
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Int, PageText>(16, 0.75f, true) {
        private var chars = 0L
        override fun put(key: Int, value: PageText): PageText? {
            chars += value.text.length
            return super.put(key, value)
        }
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>): Boolean {
            // keep roughly 1.5M characters of page text (and their boxes) in memory
            if (chars > 1_500_000 && size > 1) { chars -= eldest.value.text.length; return true }
            return false
        }
    }

    fun updateQuery(q: String) {
        query = q
        job?.cancel()
        matches.clear()
        current = -1
        problem = null
        scanned = 0
        if (q.isBlank()) { scanning = false; return }
        scanning = true
        job = scope.launch(Dispatchers.Default) { scan(q) }
    }

    fun next() {
        if (matches.isNotEmpty()) current = (current + 1) % matches.size
    }

    fun previous() {
        if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size
    }

    fun close() {
        job?.cancel()
        scope.launch(Dispatchers.IO) { lock.withLock { runCatching { doc?.close() }; doc = null } }
    }

    private suspend fun scan(q: String) {
        try {
            PdfOps.init(appContext)
            val pages = lock.withLock { openDoc().numberOfPages }
            withContext(Dispatchers.Main) { total = pages }
            var anyText = false
            for (p in 0 until pages) {
                currentCoroutineContext().ensureActive()
                val text = pageText(p)
                if (text.text.isNotBlank()) anyText = true
                val found = find(text, q, p)
                withContext(Dispatchers.Main) {
                    scanned = p + 1
                    if (found.isNotEmpty()) {
                        matches.addAll(found)
                        if (current < 0) current = 0
                    }
                }
            }
            withContext(Dispatchers.Main) {
                scanning = false
                if (!anyText) problem = "This PDF has no searchable text. It may be a scanned image."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PdfOps.PdfProblem) {
            withContext(Dispatchers.Main) { scanning = false; problem = e.message }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { scanning = false; problem = "Couldn't search this PDF." }
        } catch (e: OutOfMemoryError) {
            cache.clear()
            withContext(Dispatchers.Main) { scanning = false; problem = "This PDF is too large to search on this device." }
        }
    }

    private fun openDoc(): PDDocument = doc ?: PdfOps.load(file).also { doc = it }

    private suspend fun pageText(page: Int): PageText = lock.withLock {
        cache[page] ?: extract(openDoc(), page).also { cache[page] = it }
    }

    private fun extract(d: PDDocument, page: Int): PageText {
        val collector = Collector()
        collector.startPage = page + 1
        collector.endPage = page + 1
        collector.writeText(d, object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        })
        val box = d.getPage(page).cropBox
        val rotated = d.getPage(page).rotation % 180 != 0
        val pw = if (rotated) box.height else box.width
        val ph = if (rotated) box.width else box.height
        val text = collector.sb.toString().replace('\n', ' ')
        val boxes = Array(text.length) { i ->
            collector.boxes[i]?.let { b ->
                RectF(b[0] / pw, b[1] / ph, (b[0] + b[2]) / pw, (b[1] + b[3]) / ph)
            }
        }
        return PageText(text, boxes)
    }

    private fun find(pt: PageText, q: String, page: Int): List<PdfMatch> {
        val out = ArrayList<PdfMatch>()
        val needle = q.trim().replace('\n', ' ')
        if (needle.isEmpty()) return out
        var from = 0
        while (true) {
            val i = pt.text.indexOf(needle, from, ignoreCase = true)
            if (i < 0) break
            out += PdfMatch(page, mergeLine(pt.boxes, i, i + needle.length))
            from = i + needle.length
        }
        return out
    }

    /** Joins the character boxes of one match into one box per line of text. */
    private fun mergeLine(boxes: Array<RectF?>, start: Int, end: Int): List<RectF> {
        val lines = ArrayList<RectF>()
        for (k in start until end) {
            val b = boxes.getOrNull(k) ?: continue
            val last = lines.lastOrNull()
            if (last != null && kotlin.math.abs(last.centerY() - b.centerY()) < (b.height() * 0.5f).coerceAtLeast(0.002f)) {
                last.union(b)
            } else lines += RectF(b)
        }
        return lines
    }

    /** Collects every character's text and bounding box (in page points, origin top-left) instead of writing text out. */
    private class Collector : PDFTextStripper() {
        val sb = StringBuilder()
        val boxes = ArrayList<FloatArray?>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val n = text.length
            for (i in 0 until n) {
                sb.append(text[i])
                val tp = if (textPositions.isEmpty()) null else textPositions[(i.toLong() * textPositions.size / n).toInt()]
                boxes += tp?.let { floatArrayOf(it.xDirAdj, it.yDirAdj - it.heightDir, it.widthDirAdj, it.heightDir) }
            }
        }

        override fun writeWordSeparator() {
            sb.append(' ')
            boxes += null
        }

        override fun writeLineSeparator() {
            sb.append('\n')
            boxes += null
        }
    }
}
