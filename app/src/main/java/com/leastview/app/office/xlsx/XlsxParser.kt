package com.leastview.app.office.xlsx

import com.leastview.app.office.NS_R
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.attr
import com.leastview.app.office.attrNs
import com.leastview.app.office.kid
import com.leastview.app.office.kidsNamed
import com.leastview.app.office.parseRelationships
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

object XlsxParser {

    fun parse(pkg: OoxmlPackage): Workbook {
        val wbDom = pkg.xml("xl/workbook.xml") ?: error("Not an Excel workbook (xl/workbook.xml is missing)")
        val rels = parseRelationships(pkg, "xl/_rels/workbook.xml.rels", "xl/")
        val theme = ThemeColors.load(pkg)
        val styles = StyleTable.load(pkg, theme)
        val shared = SharedStrings.load(pkg)

        val sheets = ArrayList<Sheet>()
        val sheetsEl = wbDom.documentElement.kid("sheets")
        for (s in sheetsEl?.kidsNamed("sheet").orEmpty()) {
            val name = s.attr("name") ?: continue
            val path = s.attrNs(NS_R, "id")?.let { rels[it]?.target } ?: continue
            val sheet = Sheet(name, path)
            pkg.bytes(path)?.let { readSheet(it, shared, styles, sheet) }
            sheets += sheet
        }
        if (sheets.isEmpty()) error("This workbook has no sheets")
        return Workbook(pkg, sheets)
    }

    private fun saxFactory() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>, styles: StyleTable, sheet: Sheet) {
        val handler = object : DefaultHandler() {
            val buf = StringBuilder()
            var capture = false
            var inRPh = false
            var ref: String? = null
            var type: String? = null
            var styleIdx = 0
            var value: String? = null
            var inline = StringBuilder()
            var hasInline = false
            var formula: String? = null
            var hasFormula = false
            var inFormula = false
            var curRow = -1
            var nextCol = 0

            override fun startElement(uri: String?, local: String, qName: String?, a: Attributes) {
                when (local) {
                    "sheetFormatPr" -> a.getValue("defaultColWidth")?.toDoubleOrNull()?.let { sheet.defaultColWidthChars = it }
                    "col" -> {
                        val min = a.getValue("min")?.toIntOrNull()?.minus(1) ?: return
                        val max = (a.getValue("max")?.toIntOrNull()?.minus(1) ?: min).coerceAtMost(min + 2000)
                        val w = a.getValue("width")?.toDoubleOrNull()
                        val hidden = a.getValue("hidden") == "1" || a.getValue("hidden") == "true"
                        for (c in min..max) {
                            if (w != null) sheet.colWidthChars[c] = w
                            if (hidden) sheet.hiddenCols += c
                        }
                    }
                    "row" -> {
                        curRow = a.getValue("r")?.toIntOrNull()?.minus(1) ?: (curRow + 1)
                        nextCol = 0
                        a.getValue("ht")?.toDoubleOrNull()?.let { sheet.rowHeightPt[curRow] = it }
                        if (a.getValue("hidden") == "1" || a.getValue("hidden") == "true") sheet.hiddenRows += curRow
                    }
                    "c" -> {
                        ref = a.getValue("r")
                        type = a.getValue("t")
                        styleIdx = a.getValue("s")?.toIntOrNull() ?: 0
                        value = null
                        inline.setLength(0)
                        hasInline = false
                        formula = null
                        hasFormula = false
                    }
                    "v" -> { buf.setLength(0); capture = true }
                    "f" -> { buf.setLength(0); capture = true; inFormula = true; hasFormula = true }
                    "is" -> hasInline = true
                    "rPh" -> inRPh = true
                    "t" -> if (hasInline && !inRPh) { buf.setLength(0); capture = true }
                    "mergeCell" -> a.getValue("ref")?.let { r ->
                        val parts = r.split(':')
                        val s = parseRef(parts[0])
                        val e = parseRef(parts.getOrElse(1) { parts[0] })
                        if (s != null && e != null) sheet.merges += MergeRange(s.first, s.second, e.first, e.second)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) buf.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String, qName: String?) {
                when (local) {
                    "v" -> { value = buf.toString(); capture = false }
                    "f" -> { formula = buf.toString(); capture = false; inFormula = false }
                    "t" -> if (hasInline && !inRPh && capture) { inline.append(buf); capture = false }
                    "rPh" -> inRPh = false
                    "c" -> finishCell()
                }
            }

            fun finishCell() {
                val parsed = ref?.let { parseRef(it) }
                val row = parsed?.first ?: curRow
                val col = parsed?.second ?: nextCol
                nextCol = col + 1
                if (row < 0) return
                val style = styles.style(styleIdx)
                val v = value
                val cell: XCell? = when (type) {
                    "s" -> shared.getOrNull(v?.toIntOrNull() ?: -1)?.let { XCell(it, null, true, style, hasFormula, formula) }
                    "str" -> XCell(v.orEmpty(), null, true, style, hasFormula, formula)
                    "inlineStr" -> XCell(inline.toString(), null, true, style, hasFormula, formula)
                    "b" -> XCell(if (v == "1") "TRUE" else "FALSE", null, false, style, hasFormula, formula)
                    "e" -> XCell(v.orEmpty(), null, false, style, hasFormula, formula)
                    else -> {
                        val num = v?.toDoubleOrNull()
                        when {
                            num != null -> XCell(NumFmt.format(num, style.numFmt), num, false, style, hasFormula, formula)
                            !v.isNullOrEmpty() -> XCell(v, null, true, style, hasFormula, formula)
                            style.fill != null -> XCell("", null, false, style, hasFormula, formula)
                            else -> null
                        }
                    }
                }
                if (cell != null) sheet.put(row, col, cell)
            }
        }
        saxFactory().newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), handler)
    }
}

object SharedStrings {
    fun load(pkg: OoxmlPackage): List<String> {
        val bytes = pkg.bytes("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        val handler = object : DefaultHandler() {
            val sb = StringBuilder()
            var inSi = false
            var inT = false
            var inRPh = false
            override fun startElement(uri: String?, local: String, qName: String?, a: Attributes) {
                when (local) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "rPh" -> inRPh = true
                    "t" -> inT = inSi && !inRPh
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.append(ch, start, length)
            }
            override fun endElement(uri: String?, local: String, qName: String?) {
                when (local) {
                    "t" -> inT = false
                    "rPh" -> inRPh = false
                    "si" -> { out += sb.toString(); inSi = false }
                }
            }
        }
        val f = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        f.newSAXParser().parse(InputSource(ByteArrayInputStream(bytes)), handler)
        return out
    }
}

/** theme1.xml colour scheme, indexed the way spreadsheet "theme" colours refer to it. */
class ThemeColors(private val palette: List<String>) {
    fun get(index: Int): String? = palette.getOrNull(index)

    companion object {
        // Office default, used when the workbook carries no theme part.
        private val fallback = listOf("FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47")

        fun load(pkg: OoxmlPackage): ThemeColors {
            val dom = pkg.xml("xl/theme/theme1.xml") ?: return ThemeColors(fallback)
            val scheme = dom.documentElement.kid("themeElements")?.kid("clrScheme") ?: return ThemeColors(fallback)
            fun c(name: String): String? = scheme.kid(name)?.let { n ->
                n.kid("srgbClr")?.attr("val") ?: n.kid("sysClr")?.attr("lastClr")
            }
            val dk1 = c("dk1") ?: "000000"; val lt1 = c("lt1") ?: "FFFFFF"
            val dk2 = c("dk2") ?: "44546A"; val lt2 = c("lt2") ?: "E7E6E6"
            val accents = (1..6).map { c("accent$it") ?: "808080" }
            // spreadsheet theme indexes swap dk/lt: 0=lt1 1=dk1 2=lt2 3=dk2 4..9=accents
            return ThemeColors(listOf(lt1, dk1, lt2, dk2) + accents)
        }
    }
}

class StyleTable private constructor(private val xfs: List<XStyle>) {
    fun style(index: Int): XStyle = xfs.getOrNull(index) ?: XStyle()

    companion object {
        fun load(pkg: OoxmlPackage, theme: ThemeColors): StyleTable {
            val root = pkg.xml("xl/styles.xml")?.documentElement ?: return StyleTable(emptyList())

            val custom = HashMap<Int, String>()
            for (n in root.kid("numFmts")?.kidsNamed("numFmt").orEmpty()) {
                val id = n.attr("numFmtId")?.toIntOrNull() ?: continue
                custom[id] = n.attr("formatCode") ?: continue
            }

            fun colorOf(e: org.w3c.dom.Element?): String? {
                e ?: return null
                e.attr("rgb")?.let { return it.takeLast(6) }
                e.attr("theme")?.toIntOrNull()?.let { idx ->
                    val base = theme.get(idx) ?: return null
                    return tint(base, e.attr("tint")?.toDoubleOrNull() ?: 0.0)
                }
                return null
            }

            class FontInfo(val bold: Boolean, val italic: Boolean, val color: String?)
            val fonts = root.kid("fonts")?.kidsNamed("font").orEmpty().map { f ->
                FontInfo(f.kid("b") != null && f.kid("b")?.attr("val") != "0", f.kid("i") != null && f.kid("i")?.attr("val") != "0", colorOf(f.kid("color")))
            }
            val fills = root.kid("fills")?.kidsNamed("fill").orEmpty().map { f ->
                val p = f.kid("patternFill")
                if (p?.attr("patternType") == "solid") colorOf(p.kid("fgColor")) else null
            }

            val xfs = root.kid("cellXfs")?.kidsNamed("xf").orEmpty().map { xf ->
                val font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0)
                val numFmtId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
                val align = xf.kid("alignment")
                XStyle(
                    bold = font?.bold == true,
                    italic = font?.italic == true,
                    color = font?.color,
                    fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0),
                    halign = align?.attr("horizontal"),
                    wrap = align?.attr("wrapText") == "1",
                    numFmt = NumFmt.codeFor(numFmtId, custom),
                )
            }
            return StyleTable(xfs)
        }

        private fun tint(hex: String, t: Double): String {
            if (t == 0.0 || hex.length != 6) return hex
            fun ch(i: Int): Int {
                val v = hex.substring(i, i + 2).toInt(16)
                val out = if (t > 0) v + (255 - v) * t else v * (1 + t)
                return out.toInt().coerceIn(0, 255)
            }
            return "%02X%02X%02X".format(ch(0), ch(2), ch(4))
        }
    }
}
