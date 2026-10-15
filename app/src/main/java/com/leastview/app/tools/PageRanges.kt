package com.leastview.app.tools

/** Parses page ranges typed by the user, e.g. "1-3, 5, 8-" (1-based, inclusive; "8-" means 8 to the last page). */
object PageRanges {
    class Invalid(message: String) : Exception(message)

    /** Returns 0-based, non-overlapping ranges in the order typed. Throws [Invalid] with a message fit for the user. */
    fun parse(input: String, pageCount: Int): List<IntRange> {
        val text = input.trim()
        if (text.isEmpty()) throw Invalid("Enter the pages to use, for example 1-3, 5, 8-10.")
        val out = ArrayList<IntRange>()
        for (raw in text.split(',', ';')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val bits = part.split('-').map { it.trim() }
            val range = when {
                bits.size == 1 -> bits[0].toPage(part).let { it..it }
                bits.size == 2 -> {
                    val a = bits[0].toPage(part)
                    val b = if (bits[1].isEmpty()) pageCount else bits[1].toPage(part)
                    if (a > b) throw Invalid("\"$part\" starts after it ends.")
                    a..b
                }
                else -> throw Invalid("\"$part\" isn't a valid page range.")
            }
            if (range.first < 1 || range.last > pageCount) {
                throw Invalid("\"$part\" is outside this document (it has $pageCount page${if (pageCount == 1) "" else "s"}).")
            }
            val zeroBased = (range.first - 1)..(range.last - 1)
            out.firstOrNull { it.first <= zeroBased.last && zeroBased.first <= it.last }?.let {
                throw Invalid("\"$part\" overlaps ${label(it)}. Ranges can't overlap.")
            }
            out += zeroBased
        }
        if (out.isEmpty()) throw Invalid("Enter the pages to use, for example 1-3, 5, 8-10.")
        return out
    }

    private fun String.toPage(part: String): Int =
        toIntOrNull()?.takeIf { it > 0 } ?: throw Invalid("\"$part\" isn't a valid page range.")

    fun label(r: IntRange): String = if (r.first == r.last) "${r.first + 1}" else "${r.first + 1}-${r.last + 1}"
}
