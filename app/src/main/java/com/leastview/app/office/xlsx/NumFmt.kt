package com.leastview.app.office.xlsx

import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Formats a cell's number the way Excel would show it, from its number-format id / code. */
object NumFmt {
    private val builtin = mapOf(
        1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        14 to "dd/mm/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "dd/mm/yyyy h:mm",
        37 to "#,##0;(#,##0)", 38 to "#,##0;(#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 49 to "@",
    )

    fun codeFor(id: Int, custom: Map<Int, String>): String? = custom[id] ?: builtin[id]

    fun format(v: Double, code: String?): String {
        if (code == null || code.equals("General", ignoreCase = true)) return general(v)
        if (code == "@") return general(v)
        val sections = splitSections(code)
        val section = when {
            v < 0 && sections.size > 1 -> sections[1]
            v == 0.0 && sections.size > 2 -> sections[2]
            else -> sections[0]
        }
        val value = if (v < 0 && sections.size > 1) -v else v
        val clean = stripDirectives(section)
        return if (isDate(clean)) formatDate(value, clean) else formatNumber(if (v < 0 && sections.size <= 1) v else value, clean)
    }

    fun general(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (v == Math.rint(v) && Math.abs(v) < 1e11) return v.toLong().toString()
        val abs = Math.abs(v)
        if (abs >= 1e11 || (abs < 1e-9 && v != 0.0)) {
            return String.format(Locale.US, "%.5E", v).replace(Regex("0+E"), "E").replace(".E", "E")
        }
        return BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in code) {
            if (c == '"') quoted = !quoted
            if (c == ';' && !quoted) { out += sb.toString(); sb.clear() } else sb.append(c)
        }
        out += sb.toString()
        return out
    }

    /** Removes [Red]-style colour/condition directives and turns [$₹-4009] into ₹. */
    private fun stripDirectives(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '[') {
                val end = s.indexOf(']', i)
                if (end < 0) break
                val inner = s.substring(i + 1, end)
                when {
                    inner.startsWith("$") -> sb.append("\"").append(inner.substring(1).substringBefore('-')).append("\"")
                    inner.equals("h", true) || inner.equals("hh", true) || inner.equals("m", true) ||
                        inner.equals("mm", true) || inner.equals("s", true) || inner.equals("ss", true) -> sb.append(inner)
                }
                i = end + 1
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun isDate(code: String): Boolean {
        var quoted = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '"') quoted = !quoted
            else if (!quoted) {
                if (c == '\\' || c == '_' || c == '*') { i += 2; continue }
                if (c.lowercaseChar() in "ymdhs" && !(code.regionMatches(i, "General", 0, 7, true))) return true
            }
            i++
        }
        return false
    }

    private fun formatNumber(v0: Double, code: String): String {
        var v = v0
        var percent = false
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        var seenDigit = false
        var decimals = 0
        var afterPoint = false
        var thousands = false
        var minInt = 0
        var sci = false
        var i = 0
        var quoted = false
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { quoted = !quoted; i++; continue }
                quoted -> (if (seenDigit) suffix else prefix).append(c)
                c == '\\' && i + 1 < code.length -> { (if (seenDigit) suffix else prefix).append(code[i + 1]); i++ }
                c == '_' -> { (if (seenDigit) suffix else prefix).append(' '); i++ }
                c == '*' -> i++
                c == '0' || c == '#' || c == '?' -> if (!sci) {
                    seenDigit = true
                    if (afterPoint) decimals++ else if (c == '0') minInt++
                }
                c == '.' -> if (seenDigit || code.getOrNull(i + 1)?.let { it in "0#?" } == true) { afterPoint = true; seenDigit = true } else prefix.append(c)
                c == ',' -> if (seenDigit && !afterPoint) thousands = true
                c == '%' -> { percent = true; (if (seenDigit) suffix else prefix).append('%') }
                c == 'E' || c == 'e' -> { sci = true; i += 1 }
                else -> (if (seenDigit) suffix else prefix).append(c)
            }
            i++
        }
        if (!seenDigit) return prefix.toString() + suffix.toString()
        if (percent) v *= 100
        if (sci) return prefix.toString() + String.format(Locale.US, "%.${decimals}E", v) + suffix
        val pattern = StringBuilder(if (thousands) "#,##0" else if (minInt > 0) "0".repeat(minInt) else "#")
        if (decimals > 0) pattern.append('.').append("0".repeat(decimals))
        val df = DecimalFormat(pattern.toString(), DecimalFormatSymbols(Locale.US))
        var body = df.format(v)
        if (minInt == 0 && body.startsWith("0.")) body = body.removePrefix("0")
        if (body == "-0") body = "0"
        return prefix.toString() + body + suffix
    }

    // ---- dates ----

    private sealed interface Tok {
        data class Lit(val s: String) : Tok
        data class F(val code: String) : Tok
    }

    private fun formatDate(serial: Double, code: String): String {
        val days = Math.floor(serial).toLong()
        var secs = Math.round((serial - days) * 86400.0)
        var date = LocalDate.of(1899, 12, 30).plusDays(days)
        if (secs >= 86400) { secs -= 86400; date = date.plusDays(1) }
        val time = LocalTime.ofSecondOfDay(secs.coerceIn(0, 86399))
        val dt = LocalDateTime.of(date, time)

        val toks = tokenize(code)
        val hasAmPm = toks.any { it is Tok.F && (it.code.equals("AM/PM", true) || it.code.equals("A/P", true)) }
        val sb = StringBuilder()
        for ((idx, t) in toks.withIndex()) {
            when (t) {
                is Tok.Lit -> sb.append(t.s)
                is Tok.F -> {
                    val c = t.code.lowercase()
                    sb.append(
                        when {
                            c.startsWith("y") -> if (c.length <= 2) "%02d".format(dt.year % 100) else dt.year.toString()
                            c.startsWith("d") -> when (c.length) {
                                1 -> dt.dayOfMonth.toString()
                                2 -> "%02d".format(dt.dayOfMonth)
                                3 -> dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                                else -> dt.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
                            }
                            c.startsWith("m") -> if (isMinute(toks, idx)) {
                                if (c.length == 1) dt.minute.toString() else "%02d".format(dt.minute)
                            } else when (c.length) {
                                1 -> dt.monthValue.toString()
                                2 -> "%02d".format(dt.monthValue)
                                3 -> dt.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                                else -> dt.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
                            }
                            c == "h" || c == "hh" -> {
                                val h = if (hasAmPm) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour
                                if (c.length == 1) h.toString() else "%02d".format(h)
                            }
                            c == "[h]" -> (days * 24 + dt.hour).toString()
                            c.startsWith("s") -> if (c.length == 1) dt.second.toString() else "%02d".format(dt.second)
                            c == "am/pm" -> if (dt.hour < 12) "AM" else "PM"
                            c == "a/p" -> if (dt.hour < 12) "A" else "P"
                            else -> ""
                        },
                    )
                }
            }
        }
        return sb.toString()
    }

    /** "m" means minutes right after an hour token or right before a seconds token; otherwise it is the month. */
    private fun isMinute(toks: List<Tok>, idx: Int): Boolean {
        fun fmt(t: Tok?) = (t as? Tok.F)?.code?.lowercase()
        for (k in idx - 1 downTo 0) {
            if (toks[k] is Tok.F) { val c = fmt(toks[k]); return c == "h" || c == "hh" || c == "[h]" }
        }
        for (k in idx + 1 until toks.size) {
            if (toks[k] is Tok.F) { val c = fmt(toks[k]); return c == "s" || c == "ss" }
        }
        return false
    }

    private fun tokenize(code: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        var quoted = false
        val lit = StringBuilder()
        fun flushLit() { if (lit.isNotEmpty()) { out += Tok.Lit(lit.toString()); lit.clear() } }
        while (i < code.length) {
            val c = code[i]
            if (c == '"') { quoted = !quoted; i++; continue }
            if (quoted) { lit.append(c); i++; continue }
            if (c == '\\' && i + 1 < code.length) { lit.append(code[i + 1]); i += 2; continue }
            if (c == '_' && i + 1 < code.length) { lit.append(' '); i += 2; continue }
            if (code.regionMatches(i, "AM/PM", 0, 5, true)) { flushLit(); out += Tok.F("AM/PM"); i += 5; continue }
            if (code.regionMatches(i, "A/P", 0, 3, true)) { flushLit(); out += Tok.F("A/P"); i += 3; continue }
            if (c == '[') {
                val end = code.indexOf(']', i)
                if (end > 0) { flushLit(); out += Tok.F(code.substring(i, end + 1)); i = end + 1; continue }
            }
            if (c.lowercaseChar() in "ymdhs") {
                var j = i
                while (j < code.length && code[j].lowercaseChar() == c.lowercaseChar()) j++
                flushLit(); out += Tok.F(code.substring(i, j)); i = j; continue
            }
            lit.append(c); i++
        }
        flushLit()
        return out
    }
}
