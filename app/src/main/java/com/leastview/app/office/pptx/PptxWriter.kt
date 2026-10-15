package com.leastview.app.office.pptx

import com.leastview.app.office.NS_A
import com.leastview.app.office.kid
import com.leastview.app.office.kids
import com.leastview.app.office.kidsNamed
import org.w3c.dom.Element
import java.io.OutputStream

object PptxWriter {

    /**
     * Replaces the text of [shape] with [text] (one paragraph per line). The first run's formatting is kept
     * for every paragraph, so a heading stays a heading and a bullet stays a bullet.
     */
    fun setText(slide: Slide, shape: TextShape, text: String) {
        val tx = shape.element.kid("txBody") ?: return
        val dom = shape.element.ownerDocument
        val lines = text.split("\n")

        val paras = tx.kidsNamed("p").toMutableList()
        if (paras.isEmpty()) return
        while (paras.size < lines.size) {
            val last = paras.last()
            val clone = last.cloneNode(true) as Element
            tx.insertBefore(clone, last.nextSibling)
            paras += clone
        }
        while (paras.size > lines.size) tx.removeChild(paras.removeAt(paras.size - 1))

        for ((i, p) in paras.withIndex()) {
            val rPr = p.kidsNamed("r").firstOrNull()?.kid("rPr")?.cloneNode(true) as? Element
            p.kids().filter { it.localName in setOf("r", "br", "fld") }.forEach { p.removeChild(it) }
            if (lines[i].isEmpty()) continue
            val run = dom.createElementNS(NS_A, "a:r")
            rPr?.let { run.appendChild(it) }
            run.appendChild(dom.createElementNS(NS_A, "a:t").apply { textContent = lines[i] })
            // runs go before endParaRPr
            p.insertBefore(run, p.kid("endParaRPr"))
        }
        slide.dirty = true
        shape.refresh()
    }

    fun write(deck: PptxDeck, out: OutputStream) {
        for (s in deck.slides) if (s.dirty) deck.pkg.putXml(s.path, s.dom)
        deck.pkg.writeTo(out)
    }
}
