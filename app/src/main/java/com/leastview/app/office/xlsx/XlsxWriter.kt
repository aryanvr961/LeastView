package com.leastview.app.office.xlsx

import com.leastview.app.office.NS_XML
import com.leastview.app.office.attr
import com.leastview.app.office.kid
import com.leastview.app.office.kids
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.OutputStream

/** Applies cell edits to the original sheet XML and copies everything else through untouched. */
object XlsxWriter {

    fun write(wb: Workbook, out: OutputStream) {
        var changed = false
        for (sheet in wb.sheets) {
            if (sheet.edits.isEmpty()) continue
            val dom = wb.pkg.xml(sheet.path) ?: continue
            val sheetData = dom.documentElement.kid("sheetData") ?: continue
            for ((pos, edit) in sheet.edits) applyEdit(dom, sheetData, pos.first, pos.second, edit.text)
            wb.pkg.putXml(sheet.path, dom)
            changed = true
        }
        if (changed) requestRecalc(wb)
        wb.pkg.writeTo(out)
    }

    /** Other cells' cached formula results are stale after an edit, so ask Excel to recalculate on open. */
    private fun requestRecalc(wb: Workbook) {
        val dom = wb.pkg.xml("xl/workbook.xml") ?: return
        val root = dom.documentElement
        var calc = root.kid("calcPr")
        if (calc == null) {
            calc = dom.createElementNS(root.namespaceURI, "calcPr")
            // calcPr must precede these elements in the workbook schema
            val before = setOf("oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst")
            root.insertBefore(calc, root.kids().firstOrNull { it.localName in before })
        }
        calc!!.setAttribute("fullCalcOnLoad", "1")
        wb.pkg.putXml("xl/workbook.xml", dom)
    }

    private fun applyEdit(dom: Document, sheetData: Element, row: Int, col: Int, text: String) {
        val ns = dom.documentElement.namespaceURI
        val rowEl = findOrCreate(sheetData, "row", "r", row + 1, { it.toIntOrNull() }, { v ->
            dom.createElementNS(ns, "row").apply { setAttribute("r", v.toString()) }
        })
        val ref = columnName(col) + (row + 1)
        val cellEl = findOrCreate(rowEl, "c", "r", col, { r -> parseRef(r)?.second }, { _ ->
            dom.createElementNS(ns, "c").apply { setAttribute("r", ref) }
        })

        // Drop the old value; a formula cell is never edited here (the UI blocks it).
        cellEl.kids().filter { it.localName in setOf("v", "is") }.forEach { cellEl.removeChild(it) }
        cellEl.removeAttribute("t")

        val number = parseCellNumber(text)
        when {
            text.isEmpty() -> Unit
            number != null -> cellEl.appendChild(dom.createElementNS(ns, "v").apply { textContent = text.trim() })
            else -> {
                cellEl.setAttribute("t", "inlineStr")
                val t = dom.createElementNS(ns, "t").apply {
                    setAttributeNS(NS_XML, "xml:space", "preserve")
                    textContent = text
                }
                cellEl.appendChild(dom.createElementNS(ns, "is").apply { appendChild(t) })
            }
        }
    }

    /**
     * Finds the child [name] whose [attrName] maps to [key], keeping children sorted by that key;
     * creates it in the right position if missing. [keyOf] converts the attribute text to a sortable key.
     */
    private fun <K : Comparable<K>> findOrCreate(
        parent: Element,
        name: String,
        attrName: String,
        key: K,
        keyOf: (String) -> K?,
        create: (K) -> Element,
    ): Element {
        var insertBefore: Element? = null
        for (child in parent.kids()) {
            if (child.localName != name) continue
            val k = child.attr(attrName)?.let(keyOf) ?: continue
            if (k == key) return child
            if (k > key) { insertBefore = child; break }
        }
        val created = create(key)
        parent.insertBefore(created, insertBefore)
        return created
    }
}
