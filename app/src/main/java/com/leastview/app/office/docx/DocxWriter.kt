package com.leastview.app.office.docx

import com.leastview.app.office.NS_R
import com.leastview.app.office.NS_W
import com.leastview.app.office.NS_XML
import com.leastview.app.office.el
import com.leastview.app.office.kids
import com.leastview.app.office.kidsNamed
import com.leastview.app.office.setNs
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.OutputStream

/**
 * Writes edits back into the original package. Only paragraphs the user changed are rewritten;
 * every other part (styles, images, headers, footers, settings) is copied byte for byte.
 */
object DocxWriter {
    private const val RELS_PATH = "word/_rels/document.xml.rels"
    private const val HYPERLINK_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"

    fun write(doc: DocxDocument, out: OutputStream) {
        val links = LinkRegistry(doc)
        for (p in doc.allParagraphs()) {
            if (p.dirty && p.element != null && p.editable) rewrite(doc.dom, p, links)
        }
        doc.pkg.putXml("word/document.xml", doc.dom)
        links.flush()
        doc.pkg.writeTo(out)
    }

    private fun rewrite(dom: Document, p: DocParagraph, links: LinkRegistry) {
        val el = p.element ?: return
        // keep pPr (paragraph formatting) but drop all runs and re-generate them from the edited text
        var n = el.firstChild
        while (n != null) {
            val next = n.nextSibling
            if (!(n is Element && n.localName == "pPr")) el.removeChild(n)
            n = next
        }
        writeParagraphProps(dom, el, p)

        val text = p.text
        var i = 0
        while (i < text.length) {
            var j = i + 1
            while (j < text.length && p.styles[j] == p.styles[i]) j++
            appendRuns(dom, el, text.substring(i, j), p.styles[i], p.base, links)
            i = j
        }
    }

    /** Alignment is the one paragraph property the editor changes. */
    private fun writeParagraphProps(dom: Document, el: Element, p: DocParagraph) {
        var pPr = el.kidsNamed("pPr").firstOrNull()
        if (pPr == null) {
            pPr = dom.el(NS_W, "w", "pPr")
            el.insertBefore(pPr, el.firstChild)
        }
        pPr.kidsNamed("jc").forEach { pPr.removeChild(it) }
        val v = when (p.props.align) {
            Align.CENTER -> "center"
            Align.RIGHT -> "right"
            Align.JUSTIFY -> "both"
            Align.LEFT -> "left"
        }
        val jc = dom.el(NS_W, "w", "jc").apply { setNs(NS_W, "w", "val", v) }
        // schema order: jc comes after spacing/ind/contextualSpacing/mirrorIndents/... and before textDirection/rPr/sectPr
        val after = setOf("rPr", "sectPr", "pPrChange", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle")
        val anchor = pPr.kids().firstOrNull { it.localName in after }
        pPr.insertBefore(jc, anchor) // a null anchor appends
    }

    private fun appendRuns(dom: Document, parent: Element, text: String, s: RunStyle, base: RunStyle, links: LinkRegistry) {
        val run = dom.el(NS_W, "w", "r")
        runProps(dom, s, base)?.let { run.appendChild(it) }
        val sb = StringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            val t = dom.el(NS_W, "w", "t")
            t.setAttributeNS(NS_XML, "xml:space", "preserve")
            t.textContent = sb.toString()
            run.appendChild(t)
            sb.clear()
        }
        for (ch in text) when (ch) {
            '\t' -> { flush(); run.appendChild(dom.el(NS_W, "w", "tab")) }
            '\n' -> { flush(); run.appendChild(dom.el(NS_W, "w", "br")) }
            else -> sb.append(ch)
        }
        flush()

        val link = s.link
        if (link == null) {
            parent.appendChild(run)
        } else if (link.startsWith("#")) {
            val h = dom.el(NS_W, "w", "hyperlink").apply { setNs(NS_W, "w", "anchor", link.removePrefix("#")) }
            h.appendChild(run)
            parent.appendChild(h)
        } else {
            val h = dom.el(NS_W, "w", "hyperlink").apply {
                setNs(NS_R, "r", "id", links.idFor(link))
                setNs(NS_W, "w", "history", "1")
            }
            h.appendChild(run)
            parent.appendChild(h)
        }
    }

    /** Emits only what differs from the paragraph's base style, in schema order. */
    private fun runProps(dom: Document, s: RunStyle, base: RunStyle): Element? {
        val rPr = dom.el(NS_W, "w", "rPr")
        fun add(name: String, vararg attrs: Pair<String, String>) {
            val e = dom.el(NS_W, "w", name)
            attrs.forEach { (k, v) -> e.setNs(NS_W, "w", k, v) }
            rPr.appendChild(e)
        }
        if (s.font != base.font && s.font != null) add("rFonts", "ascii" to s.font, "hAnsi" to s.font, "cs" to s.font)
        if (s.bold != base.bold) if (s.bold) add("b") else add("b", "val" to "0")
        if (s.italic != base.italic) if (s.italic) add("i") else add("i", "val" to "0")
        if (s.strike != base.strike) if (s.strike) add("strike") else add("strike", "val" to "0")
        if (s.color != base.color) add("color", "val" to (s.color ?: "auto"))
        if (s.sizeHalfPt != base.sizeHalfPt) {
            add("sz", "val" to s.sizeHalfPt.toString())
            add("szCs", "val" to s.sizeHalfPt.toString())
        }
        if (s.highlight != base.highlight) add("highlight", "val" to (s.highlight ?: "none"))
        if (s.underline != base.underline) add("u", "val" to if (s.underline) "single" else "none")
        return if (rPr.hasChildNodes()) rPr else null
    }

    /** Hyperlink targets live in the relationships part; new ones are appended there. */
    private class LinkRegistry(private val doc: DocxDocument) {
        private val byUrl = HashMap<String, String>()
        private val added = ArrayList<Pair<String, String>>()
        private var counter = 0

        init {
            doc.rels.forEach { (id, rel) -> if (rel.external) byUrl.putIfAbsent(rel.target, id) }
        }

        fun idFor(url: String): String = byUrl.getOrPut(url) {
            var id: String
            do { id = "rIdLv${++counter}" } while (doc.rels.containsKey(id))
            added += id to url
            id
        }

        fun flush() {
            if (added.isEmpty()) return
            val rels = doc.pkg.xml(RELS_PATH) ?: return
            val root = rels.documentElement
            for ((id, url) in added) {
                val r = rels.createElementNS(root.namespaceURI, "Relationship")
                r.setAttribute("Id", id)
                r.setAttribute("Type", HYPERLINK_TYPE)
                r.setAttribute("Target", url)
                r.setAttribute("TargetMode", "External")
                root.appendChild(r)
                doc.rels[id] = com.leastview.app.office.Rel(url, true, HYPERLINK_TYPE)
            }
            added.clear()
            doc.pkg.putXml(RELS_PATH, rels)
        }
    }
}
