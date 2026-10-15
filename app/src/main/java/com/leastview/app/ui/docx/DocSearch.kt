package com.leastview.app.ui.docx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.office.docx.Block
import com.leastview.app.office.docx.DocParagraph
import com.leastview.app.office.docx.DocTable
import com.leastview.app.office.docx.DocxDocument

class DocHit(val paragraph: DocParagraph, val start: Int, val end: Int, val topIndex: Int)

/** A range inside one paragraph that should be painted as a search hit. */
class Highlight(val start: Int, val end: Int, val isCurrent: Boolean)

/** Case-insensitive search through every paragraph (including table cells) of an open document. */
class DocSearch(private val doc: DocxDocument) {
    var query by mutableStateOf("")
        private set
    val hits = mutableStateListOf<DocHit>()
    var current by mutableIntStateOf(-1)
        private set

    private var byParagraph: Map<Long, List<Int>> = emptyMap()
    private var revision by mutableIntStateOf(0)

    val currentHit: DocHit? get() = hits.getOrNull(current)

    /** Runs the search again; [keepPosition] keeps the selected result stable after edits. */
    fun run(q: String, keepPosition: Boolean = false) {
        val previous = if (keepPosition) currentHit else null
        query = q
        hits.clear()
        val needle = q.trim()
        if (needle.isEmpty()) {
            byParagraph = emptyMap()
            current = -1
            revision++
            return
        }
        val index = topLevelIndex(doc.blocks)
        val map = HashMap<Long, MutableList<Int>>()
        val found = ArrayList<DocHit>()
        for (p in doc.allParagraphs()) {
            if (p.text.length < needle.length) continue
            var from = 0
            while (true) {
                val i = p.text.indexOf(needle, from, ignoreCase = true)
                if (i < 0) break
                map.getOrPut(p.id) { ArrayList() }.add(found.size)
                found += DocHit(p, i, i + needle.length, index[p.id] ?: 0)
                from = i + needle.length
            }
        }
        hits.addAll(found)
        byParagraph = map
        current = when {
            found.isEmpty() -> -1
            previous != null -> found.indexOfFirst { it.paragraph === previous.paragraph && it.start == previous.start }.takeIf { it >= 0 } ?: 0
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

    /** Highlights to paint in [p]. Reading this subscribes the caller to result changes. */
    fun highlightsFor(p: DocParagraph): List<Highlight> {
        revision // subscribe
        val cur = current
        val idx = byParagraph[p.id] ?: return emptyList()
        return idx.mapNotNull { i -> hits.getOrNull(i)?.let { Highlight(it.start, it.end, i == cur) } }
    }

    /** Paragraph id -> position of the top-level block (page item) that contains it. */
    private fun topLevelIndex(blocks: List<Block>): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        fun walk(b: Block, top: Int) {
            when (b) {
                is DocParagraph -> out[b.id] = top
                is DocTable -> b.rows.forEach { row -> row.forEach { cell -> cell.blocks.forEach { walk(it, top) } } }
            }
        }
        blocks.forEachIndexed { i, b -> walk(b, i) }
        return out
    }
}
