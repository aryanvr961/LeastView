package com.leastview.app.ui.xlsx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.office.xlsx.Workbook

class CellHit(val sheet: Int, val row: Int, val col: Int)

/** Case-insensitive search over the displayed text of every cell in every sheet. */
class XlsxSearch(private val book: Workbook) {
    var query by mutableStateOf("")
        private set
    val hits = mutableStateListOf<CellHit>()
    var current by mutableIntStateOf(-1)
        private set

    private var bySheet: Map<Int, Set<Long>> = emptyMap()
    private var revision by mutableIntStateOf(0)

    val currentHit: CellHit? get() = hits.getOrNull(current)

    fun run(q: String, keepPosition: Boolean = false) {
        val previous = if (keepPosition) currentHit else null
        query = q
        hits.clear()
        val needle = q.trim()
        if (needle.isEmpty()) {
            bySheet = emptyMap()
            current = -1
            revision++
            return
        }
        val found = ArrayList<CellHit>()
        val index = HashMap<Int, MutableSet<Long>>()
        book.sheets.forEachIndexed { si, sheet ->
            for ((r, row) in sheet.cells) for ((c, cell) in row) {
                if (cell.display.contains(needle, ignoreCase = true)) {
                    found += CellHit(si, r, c)
                    index.getOrPut(si) { HashSet() }.add(key(r, c))
                }
            }
        }
        // reading order: sheet by sheet, row by row
        found.sortWith(compareBy({ it.sheet }, { it.row }, { it.col }))
        hits.addAll(found)
        bySheet = index
        current = when {
            found.isEmpty() -> -1
            previous != null -> found.indexOfFirst { it.sheet == previous.sheet && it.row == previous.row && it.col == previous.col }.takeIf { it >= 0 } ?: 0
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

    /** Matching cells of one sheet as packed (row, col) keys. Reading this subscribes the caller to changes. */
    fun matchesIn(sheet: Int): Set<Long> {
        revision
        return bySheet[sheet].orEmpty()
    }

    companion object {
        fun key(row: Int, col: Int): Long = row.toLong() shl 20 or col.toLong()
    }
}
