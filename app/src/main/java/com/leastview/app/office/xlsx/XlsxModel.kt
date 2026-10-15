package com.leastview.app.office.xlsx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.office.OoxmlPackage

class XStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: String? = null,
    val fill: String? = null,
    val halign: String? = null,
    val wrap: Boolean = false,
    val numFmt: String? = null,
)

class XCell(
    val display: String,
    val number: Double?,
    val isText: Boolean,
    val style: XStyle,
    val hasFormula: Boolean,
    val formula: String? = null,
)

class MergeRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

class CellEdit(val text: String)

class Sheet(val name: String, val path: String) {
    /** 0-based row -> 0-based column -> cell. */
    val cells = HashMap<Int, HashMap<Int, XCell>>()
    val colWidthChars = HashMap<Int, Double>()
    val rowHeightPt = HashMap<Int, Double>()
    val hiddenRows = HashSet<Int>()
    val hiddenCols = HashSet<Int>()
    val merges = ArrayList<MergeRange>()
    var defaultColWidthChars = 8.43
    var maxRow = 0
    var maxCol = 0

    /** Pending user edits, applied to the XML on save. Keyed by (row, col). */
    val edits = LinkedHashMap<Pair<Int, Int>, CellEdit>()

    /** Bumped whenever a cell changes so Compose re-reads it. */
    var version by mutableIntStateOf(0)
        private set

    fun cell(r: Int, c: Int): XCell? = cells[r]?.get(c)

    fun put(r: Int, c: Int, cell: XCell) {
        cells.getOrPut(r) { HashMap() }[c] = cell
        if (r + 1 > maxRow) maxRow = r + 1
        if (c + 1 > maxCol) maxCol = c + 1
    }

    /** Replaces a cell's value with user-entered [text]; numbers stay numbers. */
    fun edit(r: Int, c: Int, text: String) {
        val old = cell(r, c)
        val num = parseCellNumber(text)
        val style = old?.style ?: XStyle()
        val display = if (num != null) NumFmt.format(num, style.numFmt) else text
        put(r, c, XCell(display, num, num == null && text.isNotEmpty(), style, hasFormula = false))
        edits[r to c] = CellEdit(text)
        version++
    }

    // (row, col) -> merge covering it. Skipped for absurdly large merges so a whole-column merge can't blow up memory.
    private val mergeIndex: Map<Long, MergeRange> by lazy {
        val m = HashMap<Long, MergeRange>()
        for (mr in merges) {
            if ((mr.r2 - mr.r1 + 1).toLong() * (mr.c2 - mr.c1 + 1) > 20_000) continue
            for (r in mr.r1..mr.r2) for (c in mr.c1..mr.c2) m[r.toLong() shl 20 or c.toLong()] = mr
        }
        m
    }

    /** The merged range that contains (r, c), if any. */
    fun mergeCovering(r: Int, c: Int): MergeRange? = mergeIndex[r.toLong() shl 20 or c.toLong()]
}

class Workbook(val pkg: OoxmlPackage, val sheets: List<Sheet>)

private val plainNumber = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

/**
 * A number exactly as a spreadsheet would accept it typed in. Stricter than String.toDouble(), which
 * also takes "NaN", "Infinity", "12f" and hex floats, none of which may ever be written as a <v>.
 */
fun parseCellNumber(text: String): Double? {
    val t = text.trim()
    if (!plainNumber.matches(t)) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}

fun columnName(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) {
        n--
        sb.insert(0, 'A' + (n % 26))
        n /= 26
    }
    return sb.toString()
}

fun columnIndex(letters: String): Int {
    var n = 0
    for (ch in letters) n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
    return n - 1
}

/** "B12" -> (row 11, col 1). */
fun parseRef(ref: String): Pair<Int, Int>? {
    val letters = ref.takeWhile { it.isLetter() }
    val digits = ref.substring(letters.length)
    val row = digits.toIntOrNull() ?: return null
    if (letters.isEmpty()) return null
    return (row - 1) to columnIndex(letters)
}
