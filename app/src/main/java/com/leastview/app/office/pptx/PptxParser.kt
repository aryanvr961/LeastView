package com.leastview.app.office.pptx

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
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.min

/** Maps a child coordinate space (inside a group) to slide space: slide = child * s + t. */
private data class Xf(val tx: Double = 0.0, val ty: Double = 0.0, val sx: Double = 1.0, val sy: Double = 1.0)

private class Frame(val x: Long, val y: Long, val w: Long, val h: Long, val rot: Float, val flipH: Boolean, val flipV: Boolean)

object PptxParser {

    fun parse(pkg: OoxmlPackage): PptxDeck {
        val pres = pkg.xml("ppt/presentation.xml") ?: error("Not a PowerPoint file (ppt/presentation.xml is missing)")
        val root = pres.documentElement
        val rels = parseRelationships(pkg, "ppt/_rels/presentation.xml.rels", "ppt/")
        val sz = root.kid("sldSz")
        val w = sz?.attr("cx")?.toLongOrNull() ?: 9144000L
        val h = sz?.attr("cy")?.toLongOrNull() ?: 6858000L
        val theme = loadTheme(pkg)
        val defaultText = root.kid("defaultTextStyle")

        val cache = HashMap<String, Document?>()
        fun doc(path: String?): Document? = path?.let { cache.getOrPut(it) { pkg.xml(it) } }

        val slides = ArrayList<Slide>()
        for (id in root.kid("sldIdLst")?.kidsNamed("sldId").orEmpty()) {
            val path = id.attrNs(NS_R, "id")?.let { rels[it]?.target } ?: continue
            val dom = pkg.xml(path) ?: continue
            val srels = parseRelationships(pkg, relsPath(path), dirOf(path))
            val layoutPath = srels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
            val layout = doc(layoutPath)
            val lrels = layoutPath?.let { parseRelationships(pkg, relsPath(it), dirOf(it)) }.orEmpty()
            val masterPath = lrels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
            val master = doc(masterPath)
            slides += parseSlide(path, dom, srels, layout, master, theme, defaultText)
        }
        if (slides.isEmpty()) error("This presentation has no slides")
        return PptxDeck(pkg, w, h, slides)
    }

    private fun relsPath(path: String) = dirOf(path) + "_rels/" + path.substringAfterLast('/') + ".rels"
    private fun dirOf(path: String) = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    private fun loadTheme(pkg: OoxmlPackage): Map<String, String> {
        val out = HashMap<String, String>()
        val scheme = pkg.xml("ppt/theme/theme1.xml")?.documentElement?.kid("themeElements")?.kid("clrScheme")
        for (c in scheme?.kids().orEmpty()) {
            val v = c.kid("srgbClr")?.attr("val") ?: c.kid("sysClr")?.attr("lastClr") ?: continue
            out[c.localName] = v
        }
        return out
    }

    private fun parseSlide(
        path: String, dom: Document, srels: Map<String, Rel>,
        layout: Document?, master: Document?, theme: Map<String, String>, defaultText: Element?,
    ): Slide {
        val r = Resolver(srels, layout, master, theme, defaultText, dom)
        val spTree = dom.documentElement.kid("cSld")?.kid("spTree")
        val shapes = ArrayList<Shape>()
        if (spTree != null) r.walk(spTree, Xf(), shapes)
        val bg = r.background(dom) ?: r.background(layout) ?: r.background(master) ?: r.scheme("bg1") ?: "FFFFFF"
        return Slide(path, dom, bg, shapes)
    }

    private class Resolver(
        val srels: Map<String, Rel>,
        val layout: Document?,
        val master: Document?,
        val theme: Map<String, String>,
        val defaultText: Element?,
        val slide: Document,
    ) {
        private val clrMap: Map<String, String> = run {
            val m = HashMap<String, String>()
            fun load(e: Element?) { e?.let { for (i in 0 until it.attributes.length) it.attributes.item(i).let { a -> m[a.localName ?: a.nodeName] = a.nodeValue } } }
            load(master?.documentElement?.kid("clrMap"))
            // overrides: slide first, then layout
            val ovr = slide.documentElement.kid("clrMapOvr")?.kid("overrideClrMapping")
                ?: layout?.documentElement?.kid("clrMapOvr")?.kid("overrideClrMapping")
            load(ovr)
            if (m.isEmpty()) m.putAll(mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2"))
            m
        }

        // ---------- colours ----------

        fun scheme(name: String): String? = theme[clrMap[name] ?: name]

        fun color(clr: Element): String? {
            val start: String = when (clr.localName) {
                "srgbClr" -> clr.attr("val")
                "schemeClr" -> scheme(clr.attr("val") ?: return null)
                "sysClr" -> clr.attr("lastClr")
                "prstClr" -> when (clr.attr("val")) { "black" -> "000000"; "white" -> "FFFFFF"; else -> null }
                else -> null
            } ?: return null
            if (start.length != 6) return null
            var hex: String = start
            for (m in clr.kids()) {
                val v = (m.attr("val")?.toDoubleOrNull() ?: continue) / 100000.0
                hex = when (m.localName) {
                    "lumMod" -> adjustLum(hex, mod = v, off = 0.0)
                    "lumOff" -> adjustLum(hex, mod = 1.0, off = v)
                    "tint" -> mix(hex, "FFFFFF", v)
                    "shade" -> mix(hex, "000000", v)
                    else -> hex
                }
            }
            return hex
        }

        /** Colour of the first colour child of [parent], e.g. inside <a:solidFill>. */
        fun colorIn(parent: Element?): String? = parent?.kids()?.firstNotNullOfOrNull { color(it) }

        fun fill(spPr: Element?): String? {
            spPr ?: return null
            for (c in spPr.kids()) when (c.localName) {
                "noFill" -> return null
                "solidFill" -> return colorIn(c)
                "gradFill" -> return colorIn(c.kid("gsLst")?.kids()?.firstOrNull())
            }
            return null
        }

        fun background(dom: Document?): String? {
            val bg = dom?.documentElement?.kid("cSld")?.kid("bg") ?: return null
            bg.kid("bgPr")?.let { fill(it)?.let { c -> return c } }
            return colorIn(bg.kid("bgRef"))
        }

        // ---------- placeholders ----------

        private fun phOf(sp: Element): Element? = sp.kid("nvSpPr")?.kid("nvPr")?.kid("ph")

        private fun normType(t: String?) = when (t) { null, "body", "subTitle", "obj" -> "body"; "ctrTitle" -> "title"; else -> t }

        fun findPh(dom: Document?, ph: Element?): Element? {
            ph ?: return null
            val idx = ph.attr("idx")
            val type = normType(ph.attr("type"))
            val all = dom?.documentElement?.kid("cSld")?.kid("spTree")?.descendants("sp").orEmpty()
            if (idx != null) all.firstOrNull { phOf(it)?.attr("idx") == idx && (phOf(it)?.attr("type")?.let(::normType) ?: type) == type }?.let { return it }
            return all.firstOrNull { phOf(it)?.let { p -> normType(p.attr("type")) == type && (idx == null || p.attr("idx") == null || type != "body") } == true }
                ?: all.firstOrNull { phOf(it)?.let { p -> normType(p.attr("type")) == type } == true }
        }

        // ---------- shape tree ----------

        fun walk(parent: Element, xf: Xf, out: MutableList<Shape>) {
            for (e in parent.kids()) when (e.localName) {
                "sp", "cxnSp" -> textShape(e, xf)?.let { out += it }
                "pic" -> picShape(e, xf)?.let { out += it }
                "graphicFrame" -> frameShape(e, xf)?.let { out += it }
                "grpSp" -> {
                    val x = e.kid("grpSpPr")?.kid("xfrm")
                    val off = x?.kid("off"); val ext = x?.kid("ext")
                    val chOff = x?.kid("chOff"); val chExt = x?.kid("chExt")
                    if (off == null || ext == null || chOff == null || chExt == null) { walk(e, xf, out); continue }
                    val ox = off.attr("x")!!.toDouble(); val oy = off.attr("y")!!.toDouble()
                    val cx = ext.attr("cx")!!.toDouble(); val cy = ext.attr("cy")!!.toDouble()
                    val chx = chOff.attr("x")!!.toDouble(); val chy = chOff.attr("y")!!.toDouble()
                    val chw = chExt.attr("cx")!!.toDouble().takeIf { it != 0.0 } ?: 1.0
                    val chh = chExt.attr("cy")!!.toDouble().takeIf { it != 0.0 } ?: 1.0
                    val gx = cx / chw; val gy = cy / chh
                    walk(e, Xf(
                        tx = (ox - chx * gx) * xf.sx + xf.tx,
                        ty = (oy - chy * gy) * xf.sy + xf.ty,
                        sx = gx * xf.sx, sy = gy * xf.sy,
                    ), out)
                }
            }
        }

        private fun frameOf(xfrm: Element?, xf: Xf): Frame? {
            val off = xfrm?.kid("off") ?: return null
            val ext = xfrm.kid("ext") ?: return null
            val x = off.attr("x")?.toDoubleOrNull() ?: return null
            val y = off.attr("y")?.toDoubleOrNull() ?: return null
            val w = ext.attr("cx")?.toDoubleOrNull() ?: return null
            val h = ext.attr("cy")?.toDoubleOrNull() ?: return null
            return Frame(
                x = (x * xf.sx + xf.tx).toLong(), y = (y * xf.sy + xf.ty).toLong(),
                w = (w * xf.sx).toLong(), h = (h * xf.sy).toLong(),
                rot = (xfrm.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f,
                flipH = xfrm.attr("flipH") == "1" || xfrm.attr("flipH") == "true",
                flipV = xfrm.attr("flipV") == "1" || xfrm.attr("flipV") == "true",
            )
        }

        private fun textShape(e: Element, xf: Xf): Shape? {
            val spPr = e.kid("spPr")
            val ph = phOf(e)
            val layoutSp = findPh(layout, ph)
            val masterSp = findPh(master, ph)
            val frame = frameOf(spPr?.kid("xfrm"), xf)
                ?: frameOf(layoutSp?.kid("spPr")?.kid("xfrm"), Xf())
                ?: frameOf(masterSp?.kid("spPr")?.kid("xfrm"), Xf())
                ?: return null

            val geom = spPr?.kid("prstGeom")?.attr("prst") ?: "rect"
            val isLine = e.localName == "cxnSp" || geom == "line" || geom.contains("Connector")
            val style = e.kid("style")
            val fill = if (spPr != null && spPr.kids().any { it.localName in setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill") })
                fill(spPr)
            else style?.kid("fillRef")?.takeIf { it.attr("idx") != "0" }?.let { colorIn(it) }

            val ln = spPr?.kid("ln")
            val line: String? = when {
                ln?.kid("noFill") != null -> null
                ln?.kid("solidFill") != null -> colorIn(ln.kid("solidFill"))
                else -> style?.kid("lnRef")?.takeIf { it.attr("idx") != "0" }?.let { colorIn(it) }
            }
            val lineW = ln?.attr("w")?.toLongOrNull() ?: 12700L

            val kind = when (normType(ph?.attr("type"))) { "title" -> "titleStyle"; "body" -> if (ph != null) "bodyStyle" else "otherStyle"; else -> "otherStyle" }
            val tx = e.kid("txBody")
            val bodyPr = tx?.kid("bodyPr") ?: layoutSp?.kid("txBody")?.kid("bodyPr") ?: masterSp?.kid("txBody")?.kid("bodyPr")
            fun inset(name: String, d: Long) = (tx?.kid("bodyPr")?.attr(name) ?: layoutSp?.kid("txBody")?.kid("bodyPr")?.attr(name)
                ?: masterSp?.kid("txBody")?.kid("bodyPr")?.attr(name))?.toLongOrNull() ?: d
            val anchor = tx?.kid("bodyPr")?.attr("anchor") ?: layoutSp?.kid("txBody")?.kid("bodyPr")?.attr("anchor")
                ?: masterSp?.kid("txBody")?.kid("bodyPr")?.attr("anchor") ?: "t"
            val fontScale = (bodyPr?.kid("normAutofit")?.attr("fontScale")?.toFloatOrNull() ?: 100000f) / 100000f

            val reparse = { parseParagraphs(tx, layoutSp, masterSp, kind, ph != null) }
            return TextShape(
                frame.x, frame.y, frame.w, frame.h, frame.rot, frame.flipH, frame.flipV,
                geom, fill, line, lineW,
                longArrayOf(inset("lIns", 91440), inset("tIns", 45720), inset("rIns", 91440), inset("bIns", 45720)),
                anchor, fontScale, isLine, e, reparse, reparse(),
            )
        }

        private fun picShape(e: Element, xf: Xf): Shape? {
            val frame = frameOf(e.kid("spPr")?.kid("xfrm"), xf) ?: return null
            val blipFill = e.kid("blipFill")
            val rid = blipFill?.kid("blip")?.attrNs(NS_R, "embed") ?: return null
            val path = srels[rid]?.target ?: return null
            val src = blipFill.kid("srcRect")
            fun f(n: String) = (src?.attr(n)?.toFloatOrNull() ?: 0f) / 100000f
            return PicShape(frame.x, frame.y, frame.w, frame.h, frame.rot, frame.flipH, frame.flipV, path, floatArrayOf(f("l"), f("t"), f("r"), f("b")))
        }

        private fun frameShape(e: Element, xf: Xf): Shape? {
            val frame = frameOf(e.kid("xfrm"), xf) ?: return null
            val data = e.kid("graphic")?.kid("graphicData")
            val tbl = data?.kid("tbl")
            if (tbl != null) return tableShape(frame, tbl)
            val uri = data?.attr("uri").orEmpty()
            val label = when {
                uri.endsWith("/chart") -> "Chart"
                uri.contains("diagram") -> "Diagram"
                else -> "Object"
            }
            return PlaceholderShape(frame.x, frame.y, frame.w, frame.h, label)
        }

        private fun tableShape(frame: Frame, tbl: Element): Shape {
            val cols = tbl.kid("tblGrid")?.kidsNamed("gridCol").orEmpty().map { it.attr("w")?.toLongOrNull() ?: 0L }
            val firstRow = tbl.kid("tblPr")?.attr("firstRow").let { it == "1" || it == "true" }
            val rows = tbl.kidsNamed("tr").mapIndexed { ri, tr ->
                TRow(tr.attr("h")?.toLongOrNull() ?: 370840L, tr.kidsNamed("tc").map { tc ->
                    val paras = tc.kid("txBody")?.kidsNamed("p").orEmpty()
                    val text = paras.joinToString("\n") { p -> p.descendants("t").joinToString("") { it.textContent } }
                    val rPr = tc.kid("txBody")?.firstDescendant("rPr")
                    val explicit = fill(tc.kid("tcPr"))
                    val header = firstRow && ri == 0
                    TCell(
                        text = text,
                        sizePt = (rPr?.attr("sz")?.toFloatOrNull() ?: 1800f) / 100f,
                        bold = rPr?.attr("b") == "1" || header,
                        color = colorIn(rPr?.kid("solidFill")) ?: if (header && explicit == null) "FFFFFF" else null,
                        fill = explicit ?: if (header) scheme("accent1") else null,
                    )
                })
            }
            return TableShape(frame.x, frame.y, frame.w, frame.h, cols, rows)
        }

        // ---------- text ----------

        private fun levelChain(own: Element?, layoutSp: Element?, masterSp: Element?, kind: String, lvl: Int): List<Element> {
            val tag = "lvl${lvl + 1}pPr"
            return listOfNotNull(
                own?.kid("lstStyle")?.kid(tag),
                layoutSp?.kid("txBody")?.kid("lstStyle")?.kid(tag),
                masterSp?.kid("txBody")?.kid("lstStyle")?.kid(tag),
                master?.documentElement?.kid("txStyles")?.kid(kind)?.kid(tag),
                defaultText?.kid(tag),
            )
        }

        private fun parseParagraphs(tx: Element?, layoutSp: Element?, masterSp: Element?, kind: String, isPlaceholder: Boolean): List<PPara> {
            tx ?: return emptyList()
            val defaultColor = scheme("tx1")
            val autoNums = HashMap<Int, Int>()
            val out = ArrayList<PPara>()
            for (p in tx.kidsNamed("p")) {
                val pPr = p.kid("pPr")
                val lvl = pPr?.attr("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                val chain = levelChain(tx, layoutSp, masterSp, kind, lvl)
                fun pAttr(n: String): String? = pPr?.attr(n) ?: chain.firstNotNullOfOrNull { it.attr(n) }
                fun defRPr(n: String): String? = chain.firstNotNullOfOrNull { it.kid("defRPr")?.attr(n) }

                // bullet: the first element (paragraph, then inherited levels) that says anything decides
                var bullet: String? = null
                val deciders = listOfNotNull(pPr) + chain
                for (d in deciders) {
                    if (d.kid("buNone") != null) { bullet = null; break }
                    val ch = d.kid("buChar")?.attr("char")
                    if (ch != null) { bullet = ch; break }
                    if (d.kid("buAutoNum") != null) { bullet = "#"; break }
                }
                val hasText = p.descendants("t").any { it.textContent.isNotEmpty() }
                if (bullet == "#") {
                    val n = (autoNums[lvl] ?: 0) + 1
                    autoNums[lvl] = n
                    bullet = "$n."
                } else autoNums.clear()
                if (!hasText) bullet = null

                val defColorFromChain = chain.firstNotNullOfOrNull { c -> colorIn(c.kid("defRPr")?.kid("solidFill")) }
                val runs = ArrayList<PRun>()
                fun addRun(text: String, rPr: Element?) {
                    val sz = rPr?.attr("sz") ?: defRPr("sz") ?: "1800"
                    fun flag(n: String): Boolean = (rPr?.attr(n) ?: defRPr(n)).let { it == "1" || it == "true" }
                    runs += PRun(
                        text = text,
                        sizePt = sz.toFloatOrNull()?.div(100f) ?: 18f,
                        bold = flag("b"),
                        italic = flag("i"),
                        underline = (rPr?.attr("u") ?: defRPr("u")).let { it != null && it != "none" },
                        color = colorIn(rPr?.kid("solidFill")) ?: defColorFromChain ?: defaultColor,
                        font = (rPr?.kid("latin")?.attr("typeface") ?: chain.firstNotNullOfOrNull { it.kid("defRPr")?.kid("latin")?.attr("typeface") })
                            ?.takeIf { !it.startsWith("+") },
                    )
                }
                for (c in p.kids()) when (c.localName) {
                    "r" -> addRun(c.kid("t")?.textContent.orEmpty(), c.kid("rPr"))
                    "fld" -> addRun(c.kid("t")?.textContent.orEmpty(), c.kid("rPr"))
                    "br" -> addRun("\n", c.kid("rPr"))
                }
                if (runs.isEmpty()) addRun("", p.kid("endParaRPr"))

                val lnSpc = pPr?.kid("lnSpc")?.kid("spcPct")?.attr("val") ?: chain.firstNotNullOfOrNull { it.kid("lnSpc")?.kid("spcPct")?.attr("val") }
                val spcBef = pPr?.kid("spcBef")?.kid("spcPts")?.attr("val") ?: chain.firstNotNullOfOrNull { it.kid("spcBef")?.kid("spcPts")?.attr("val") }
                out += PPara(
                    runs = runs,
                    align = pAttr("algn"),
                    bullet = if (kind == "titleStyle") null else bullet,
                    marLEmu = pAttr("marL")?.toLongOrNull() ?: 0L,
                    indentEmu = pAttr("indent")?.toLongOrNull() ?: 0L,
                    spaceBeforePt = (spcBef?.toFloatOrNull() ?: 0f) / 100f,
                    lineMult = (lnSpc?.toFloatOrNull() ?: 100000f) / 100000f,
                )
            }
            return out
        }
    }

    // ---------- colour maths ----------

    private fun rgb(hex: String) = intArrayOf(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
    private fun toHex(c: IntArray) = "%02X%02X%02X".format(c[0].coerceIn(0, 255), c[1].coerceIn(0, 255), c[2].coerceIn(0, 255))

    private fun mix(hex: String, other: String, amount: Double): String {
        val a = rgb(hex); val b = rgb(other)
        return toHex(IntArray(3) { (a[it] * amount + b[it] * (1 - amount)).toInt() })
    }

    private fun adjustLum(hex: String, mod: Double, off: Double): String {
        val (r, g, b) = rgb(hex).map { it / 255.0 }
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        var l = (mx + mn) / 2
        var h = 0.0; var s = 0.0
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
        }
        l = (l * mod + off).coerceIn(0.0, 1.0)
        fun hue(p: Double, q: Double, t0: Double): Double {
            var t = t0
            if (t < 0) t += 1; if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        if (s == 0.0) { val v = (l * 255).toInt(); return toHex(intArrayOf(v, v, v)) }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return toHex(intArrayOf((hue(p, q, h + 1.0 / 3) * 255).toInt(), (hue(p, q, h) * 255).toInt(), (hue(p, q, h - 1.0 / 3) * 255).toInt()))
    }
}
