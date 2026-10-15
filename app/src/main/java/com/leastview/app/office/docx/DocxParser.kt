package com.leastview.app.office.docx

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.leastview.app.office.NS_R
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.Rel
import com.leastview.app.office.attr
import com.leastview.app.office.attrNs
import com.leastview.app.office.descendants
import com.leastview.app.office.firstDescendant
import com.leastview.app.office.kid
import com.leastview.app.office.kids
import com.leastview.app.office.kidsNamed
import com.leastview.app.office.parseRelationships
import org.w3c.dom.Element

// ---- formatting patches (only the properties a given element actually sets) ----

class RPatch(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val sizeHalfPt: Int? = null,
    val color: String? = null,
    val font: String? = null,
    val highlight: String? = null,
)

class PPatch(
    val align: Align? = null,
    val indentLeft: Int? = null,
    val firstLine: Int? = null,
    val before: Int? = null,
    val after: Int? = null,
    val lineMult: Float? = null,
    val borderBottom: Boolean? = null,
    val borderTop: Boolean? = null,
    val numId: Int? = null,
    val numLvl: Int? = null,
)

private fun Element?.toggle(): Boolean? {
    if (this == null) return null
    val v = attr("val")?.lowercase()
    return !(v == "0" || v == "false" || v == "off" || v == "none")
}

fun parseRPr(rPr: Element?): RPatch {
    if (rPr == null) return RPatch()
    val u = rPr.kid("u")?.attr("val")?.lowercase()
    val color = rPr.kid("color")?.attr("val")
    return RPatch(
        bold = rPr.kid("b").toggle(),
        italic = rPr.kid("i").toggle(),
        underline = if (rPr.kid("u") == null) null else u != "none",
        strike = rPr.kid("strike").toggle(),
        sizeHalfPt = rPr.kid("sz")?.attr("val")?.toIntOrNull(),
        color = color?.let { if (it.equals("auto", true)) "" else it },
        font = rPr.kid("rFonts")?.let { it.attr("ascii") ?: it.attr("hAnsi") },
        highlight = rPr.kid("highlight")?.attr("val")?.takeIf { it != "none" },
    )
}

fun parsePPr(pPr: Element?): PPatch {
    if (pPr == null) return PPatch()
    val jc = pPr.kid("jc")?.attr("val")
    val ind = pPr.kid("ind")
    val sp = pPr.kid("spacing")
    val line = sp?.attr("line")?.toFloatOrNull()
    val rule = sp?.attr("lineRule")
    val bdr = pPr.kid("pBdr")
    val numPr = pPr.kid("numPr")
    val hanging = ind?.attr("hanging")?.toIntOrNull()
    return PPatch(
        align = when (jc) {
            "center" -> Align.CENTER
            "right", "end" -> Align.RIGHT
            "both", "distribute" -> Align.JUSTIFY
            "left", "start" -> Align.LEFT
            else -> null
        },
        indentLeft = (ind?.attr("left") ?: ind?.attr("start"))?.toIntOrNull(),
        firstLine = hanging?.let { -it } ?: ind?.attr("firstLine")?.toIntOrNull(),
        before = sp?.attr("before")?.toIntOrNull(),
        after = sp?.attr("after")?.toIntOrNull(),
        lineMult = if (line != null && (rule == null || rule == "auto")) line / 240f else null,
        borderBottom = bdr?.kid("bottom")?.attr("val")?.let { it != "nil" && it != "none" },
        borderTop = bdr?.kid("top")?.attr("val")?.let { it != "nil" && it != "none" },
        numId = numPr?.kid("numId")?.attr("val")?.toIntOrNull(),
        numLvl = numPr?.kid("ilvl")?.attr("val")?.toIntOrNull(),
    )
}

fun RunStyle.with(p: RPatch): RunStyle = copy(
    bold = p.bold ?: bold,
    italic = p.italic ?: italic,
    underline = p.underline ?: underline,
    strike = p.strike ?: strike,
    sizeHalfPt = p.sizeHalfPt ?: sizeHalfPt,
    color = when (p.color) { null -> color; "" -> null; else -> p.color },
    font = p.font ?: font,
    highlight = p.highlight ?: highlight,
)

fun ParaProps.with(p: PPatch): ParaProps = copy(
    align = p.align ?: align,
    indentLeftTw = p.indentLeft ?: indentLeftTw,
    firstLineTw = p.firstLine ?: firstLineTw,
    spaceBeforeTw = p.before ?: spaceBeforeTw,
    spaceAfterTw = p.after ?: spaceAfterTw,
    lineMult = p.lineMult ?: lineMult,
    borderBottom = p.borderBottom ?: borderBottom,
    borderTop = p.borderTop ?: borderTop,
)

// ---- styles ----

class StyleDef(
    val id: String,
    val basedOn: String?,
    val isDefault: Boolean,
    val type: String?,
    val r: RPatch,
    val p: PPatch,
    val bordered: Boolean,
)

class StyleSheet(pkg: OoxmlPackage) {
    private val styles = HashMap<String, StyleDef>()
    var defaultParagraphStyle: String? = null
        private set
    var defaultRun: RPatch = RPatch()
        private set
    var defaultPara: PPatch = PPatch()
        private set

    init {
        pkg.xml("word/styles.xml")?.documentElement?.let { root ->
            root.kid("docDefaults")?.let { d ->
                defaultRun = parseRPr(d.kid("rPrDefault")?.kid("rPr"))
                defaultPara = parsePPr(d.kid("pPrDefault")?.kid("pPr"))
            }
            for (s in root.kidsNamed("style")) {
                val id = s.attr("styleId") ?: continue
                val def = StyleDef(
                    id = id,
                    basedOn = s.kid("basedOn")?.attr("val"),
                    isDefault = s.attr("default") == "1" || s.attr("default") == "true",
                    type = s.attr("type"),
                    r = parseRPr(s.kid("rPr")),
                    p = parsePPr(s.kid("pPr")),
                    bordered = s.kid("tblPr")?.kid("tblBorders")?.let(::hasVisibleBorder) == true,
                )
                styles[id] = def
                if (def.isDefault && def.type == "paragraph") defaultParagraphStyle = id
            }
        }
    }

    /** Root-first chain of styles for [id] (basedOn ancestors first). */
    fun chain(id: String?): List<StyleDef> {
        val out = ArrayList<StyleDef>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    fun get(id: String?): StyleDef? = id?.let { styles[it] }
}

fun hasVisibleBorder(borders: Element): Boolean =
    borders.kids().any { b -> b.attr("val")?.let { it != "nil" && it != "none" } == true }

// ---- numbering ----

class Numbering(pkg: OoxmlPackage) {
    class Lvl(val fmt: String, val text: String, val start: Int)

    private val abstracts = HashMap<Int, Map<Int, Lvl>>()
    private val nums = HashMap<Int, Int>()

    init {
        pkg.xml("word/numbering.xml")?.documentElement?.let { root ->
            for (a in root.kidsNamed("abstractNum")) {
                val id = a.attr("abstractNumId")?.toIntOrNull() ?: continue
                val levels = HashMap<Int, Lvl>()
                for (l in a.kidsNamed("lvl")) {
                    val ilvl = l.attr("ilvl")?.toIntOrNull() ?: continue
                    levels[ilvl] = Lvl(
                        fmt = l.kid("numFmt")?.attr("val") ?: "decimal",
                        text = l.kid("lvlText")?.attr("val") ?: "",
                        start = l.kid("start")?.attr("val")?.toIntOrNull() ?: 1,
                    )
                }
                abstracts[id] = levels
            }
            for (n in root.kidsNamed("num")) {
                val id = n.attr("numId")?.toIntOrNull() ?: continue
                val abs = n.kid("abstractNumId")?.attr("val")?.toIntOrNull() ?: continue
                nums[id] = abs
            }
        }
    }

    fun level(numId: Int, ilvl: Int): Lvl? = nums[numId]?.let { abstracts[it]?.get(ilvl) }
}

class NumberingCounter(private val n: Numbering) {
    private val counters = HashMap<Int, IntArray>()

    fun next(numId: Int, ilvl: Int): String? {
        if (numId == 0 || ilvl !in 0..8) return null
        val lvl = n.level(numId, ilvl) ?: return null
        val arr = counters.getOrPut(numId) { IntArray(9) { -1 } }
        arr[ilvl] = if (arr[ilvl] < 0) lvl.start else arr[ilvl] + 1
        for (k in ilvl + 1 until 9) arr[k] = -1
        if (lvl.fmt == "bullet") return bullet(lvl.text, ilvl)
        if (lvl.fmt == "none") return ""
        var s = lvl.text
        for (k in 1..9) {
            if (!s.contains("%$k")) continue
            val l = n.level(numId, k - 1)
            val c = arr[k - 1].let { if (it < 0) (l?.start ?: 1) else it }
            s = s.replace("%$k", formatNumber(c, l?.fmt ?: "decimal"))
        }
        return s
    }

    private fun bullet(text: String, ilvl: Int): String {
        val ch = text.firstOrNull()?.code ?: 0
        return when {
            text.isEmpty() || ch in 0xF000..0xF0FF -> if (ilvl == 0) "•" else if (ilvl == 1) "◦" else "▪"
            text == "o" -> "◦"
            else -> text
        }
    }
}

fun formatNumber(n: Int, fmt: String): String = when (fmt) {
    "lowerLetter" -> letters(n).lowercase()
    "upperLetter" -> letters(n)
    "lowerRoman" -> roman(n).lowercase()
    "upperRoman" -> roman(n)
    else -> n.toString()
}

private fun letters(n: Int): String {
    var x = n
    val sb = StringBuilder()
    while (x > 0) {
        x--
        sb.insert(0, 'A' + (x % 26))
        x /= 26
    }
    return sb.toString()
}

private fun roman(n: Int): String {
    val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var x = n
    val sb = StringBuilder()
    for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
    return sb.toString()
}

// ---- document ----

object DocxParser {
    private const val LINK_COLOR = "0563C1"

    // Anything outside these inside a paragraph/run means a plain-text rewrite could lose content.
    private val simpleParaChildren = setOf(
        "pPr", "r", "hyperlink", "ins", "bookmarkStart", "bookmarkEnd", "proofErr",
        "commentRangeStart", "commentRangeEnd", "permStart", "permEnd", "del", "moveFromRangeStart", "moveFromRangeEnd",
    )
    private val simpleRunChildren = setOf(
        "rPr", "t", "tab", "br", "cr", "noBreakHyphen", "softHyphen", "lastRenderedPageBreak", "delText",
    )

    fun parse(pkg: OoxmlPackage): DocxDocument {
        val dom = pkg.xml("word/document.xml") ?: error("Not a Word document (word/document.xml is missing)")
        val rels = parseRelationships(pkg, "word/_rels/document.xml.rels", "word/")
        val ctx = Ctx(pkg, StyleSheet(pkg), NumberingCounter(Numbering(pkg)), rels)
        val body = dom.documentElement.kid("body") ?: error("Empty document")
        val blocks = mutableStateListOf<Block>()
        parseBlocks(body.kids(), blocks, ctx)
        return DocxDocument(pkg, dom, blocks, pageSetup(body.kid("sectPr")), rels.toMutableMap())
    }

    private class Ctx(val pkg: OoxmlPackage, val styles: StyleSheet, val numbering: NumberingCounter, val rels: Map<String, Rel>) {
        val defaultRun: RunStyle = RunStyle().with(styles.defaultRun)
        val defaultPara: ParaProps = ParaProps().with(styles.defaultPara)
    }

    private fun pageSetup(sect: Element?): PageSetup {
        if (sect == null) return PageSetup()
        val sz = sect.kid("pgSz")
        val mar = sect.kid("pgMar")
        val d = PageSetup()
        return PageSetup(
            widthTw = sz?.attr("w")?.toIntOrNull() ?: d.widthTw,
            heightTw = sz?.attr("h")?.toIntOrNull() ?: d.heightTw,
            marginLeftTw = mar?.attr("left")?.toIntOrNull() ?: d.marginLeftTw,
            marginRightTw = mar?.attr("right")?.toIntOrNull() ?: d.marginRightTw,
            marginTopTw = mar?.attr("top")?.toIntOrNull() ?: d.marginTopTw,
            marginBottomTw = mar?.attr("bottom")?.toIntOrNull() ?: d.marginBottomTw,
        )
    }

    private fun parseBlocks(elements: List<Element>, into: SnapshotStateList<Block>, ctx: Ctx) {
        for (e in elements) when (e.localName) {
            "p" -> into += parseParagraph(e, ctx).also { it.container = into }
            "tbl" -> into += parseTable(e, ctx)
            "sdt" -> e.kid("sdtContent")?.let { parseBlocks(it.kids(), into, ctx) }
        }
    }

    private fun parseTable(tbl: Element, ctx: Ctx): DocTable {
        val pr = tbl.kid("tblPr")
        val styleBorders = ctx.styles.chain(pr?.kid("tblStyle")?.attr("val")).any { it.bordered }
        var bordered = styleBorders || pr?.kid("tblBorders")?.let(::hasVisibleBorder) == true
        val rows = ArrayList<List<DocCell>>()
        for (tr in tbl.kidsNamed("tr")) {
            val cells = ArrayList<DocCell>()
            for (tc in tr.kidsNamed("tc")) {
                val tcPr = tc.kid("tcPr")
                if (tcPr?.kid("tcBorders")?.let(::hasVisibleBorder) == true) bordered = true
                val blocks = mutableStateListOf<Block>()
                parseBlocks(tc.kids(), blocks, ctx)
                if (blocks.isEmpty()) blocks += emptyParagraph(ctx)
                cells += DocCell(
                    blocks = blocks,
                    widthTw = tcPr?.kid("tcW")?.attr("w")?.toIntOrNull() ?: 0,
                    gridSpan = tcPr?.kid("gridSpan")?.attr("val")?.toIntOrNull() ?: 1,
                    shade = tcPr?.kid("shd")?.attr("fill")?.takeIf { it.length == 6 && !it.equals("auto", true) },
                )
            }
            rows += cells
        }
        return DocTable(rows, bordered)
    }

    private fun emptyParagraph(ctx: Ctx) = DocParagraph(null, "", emptyList(), ctx.defaultPara, ctx.defaultRun, false, emptyList())

    private fun parseParagraph(p: Element, ctx: Ctx): DocParagraph {
        val pPr = p.kid("pPr")
        val styleId = pPr?.kid("pStyle")?.attr("val") ?: ctx.styles.defaultParagraphStyle
        val chain = ctx.styles.chain(styleId)

        var base = ctx.defaultRun
        var props = ctx.defaultPara
        var numId: Int? = null
        var numLvl: Int? = null
        for (s in chain) {
            base = base.with(s.r)
            props = props.with(s.p)
            s.p.numId?.let { numId = it }
            s.p.numLvl?.let { numLvl = it }
        }
        val direct = parsePPr(pPr)
        props = props.with(direct)
        direct.numId?.let { numId = it }
        direct.numLvl?.let { numLvl = it }
        val marker = numId?.let { ctx.numbering.next(it, numLvl ?: 0) }
        props = props.copy(marker = marker)

        val text = StringBuilder()
        val styles = ArrayList<RunStyle>()
        val images = ArrayList<DocImage>()
        var simple = p.kids().all { it.localName in simpleParaChildren }

        fun addRun(r: Element, link: String?) {
            if (r.kids().any { it.localName !in simpleRunChildren }) simple = false
            // Precedence: paragraph style (base) < character style (rStyle) < direct run formatting.
            val rPr = r.kid("rPr")
            var style = base
            for (s in ctx.styles.chain(rPr?.kid("rStyle")?.attr("val"))) style = style.with(s.r)
            style = style.with(parseRPr(rPr))
            if (link != null) {
                style = style.copy(link = link)
                if (style.color == null) style = style.copy(color = LINK_COLOR, underline = true)
            }
            fun emit(s: String) {
                if (s.isEmpty()) return
                text.append(s)
                repeat(s.length) { styles += style }
            }
            for (c in r.kids()) when (c.localName) {
                "t" -> emit(c.textContent)
                "tab" -> emit("\t")
                "br" -> if (c.attr("type").let { it == null || it == "textWrapping" }) emit("\n")
                "cr" -> emit("\n")
                "noBreakHyphen" -> emit("‑")
                "drawing", "pict" -> collectImage(c, ctx, images)
            }
        }

        fun walk(parent: Element, link: String?) {
            for (c in parent.kids()) when (c.localName) {
                "r" -> addRun(c, link)
                "hyperlink" -> {
                    val rid = c.attrNs(NS_R, "id")
                    val target = rid?.let { ctx.rels[it] }?.takeIf { it.external }?.target
                    walk(c, target ?: c.attr("anchor")?.let { "#$it" })
                }
                "ins", "smartTag" -> walk(c, link)
            }
        }
        walk(p, null)

        return DocParagraph(p, text.toString(), styles, props, base, simple, images)
    }

    private fun collectImage(drawing: Element, ctx: Ctx, out: MutableList<DocImage>) {
        val blip = drawing.firstDescendant("blip") ?: return
        val rid = blip.attr("embed") ?: return
        val path = ctx.rels[rid]?.target ?: return
        val extent = drawing.firstDescendant("extent")
        val cx = extent?.attr("cx")?.toLongOrNull() ?: 0L
        val cy = extent?.attr("cy")?.toLongOrNull() ?: 0L
        out += DocImage(path, cx, cy)
    }
}
