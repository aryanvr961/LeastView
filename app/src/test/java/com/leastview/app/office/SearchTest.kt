package com.leastview.app.office

import com.leastview.app.office.docx.DocParagraph
import com.leastview.app.office.docx.DocxParser
import com.leastview.app.office.pptx.PptxParser
import com.leastview.app.office.xlsx.XlsxParser
import com.leastview.app.ui.docx.DocSearch
import com.leastview.app.ui.pptx.PptxSearch
import com.leastview.app.ui.xlsx.XlsxSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    // ---- DOCX ----

    private val body = """
        <w:p><w:r><w:t>Alpha beta ALPHA</w:t></w:r></w:p>
        <w:p><w:r><w:t>nothing here</w:t></w:r></w:p>
        <w:tbl><w:tblGrid><w:gridCol w="4000"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:t>in a cell: alpha</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
    """.trimIndent()

    @Test fun docxSearchFindsEveryOccurrenceIgnoringCaseAndReportsPositions() {
        val doc = DocxParser.parse(OoxmlPackage(TestFiles.docx(body)))
        val s = DocSearch(doc)
        s.run("alpha")
        assertEquals(3, s.hits.size)                       // two in the first paragraph, one inside the table
        assertEquals(0, s.current)
        assertEquals(listOf(0, 11, 11), s.hits.map { it.start })   // "Alpha", "ALPHA", and "alpha" after "in a cell: "
        assertEquals(0, s.hits[0].topIndex)
        assertEquals(2, s.hits[2].topIndex)                // the table is the third top-level block

        val first = doc.blocks[0] as DocParagraph
        val marks = s.highlightsFor(first)
        assertEquals(2, marks.size)
        assertTrue(marks[0].isCurrent)
        assertFalse(marks[1].isCurrent)

        s.next(); s.next(); s.next()                       // wraps around
        assertEquals(0, s.current)
        s.previous()
        assertEquals(2, s.current)

        s.run("zzz")
        assertTrue(s.hits.isEmpty())
        assertEquals(-1, s.current)
        s.run("")
        assertEquals("", s.query)
    }

    @Test fun docxSearchKeepsItsPositionWhenRefreshedAfterAnEdit() {
        val doc = DocxParser.parse(OoxmlPackage(TestFiles.docx(body)))
        val s = DocSearch(doc)
        s.run("alpha")
        s.next()
        val selected = s.currentHit!!
        (doc.blocks[1] as DocParagraph).applyEdit("nothing here at all", null)
        s.run("alpha", keepPosition = true)
        assertEquals(selected.start, s.currentHit!!.start)
        assertTrue(s.currentHit!!.paragraph === selected.paragraph)
    }

    // ---- XLSX ----

    private fun book() = TestFiles.zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "xl/workbook.xml" to "<workbook $ns><sheets><sheet name=\"A\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"B\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="x/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
        "xl/sharedStrings.xml" to "<sst $ns><si><t>Total cost</t></si><si><t>other</t></si></sst>",
        "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="3"><c r="C3" t="inlineStr"><is><t>COST centre</t></is></c></row></sheetData></worksheet>""",
        "xl/worksheets/sheet2.xml" to """<worksheet $ns><sheetData><row r="2"><c r="B2"><v>1234</v></c></row></sheetData></worksheet>""",
    )

    @Test fun xlsxSearchSpansSheetsInReadingOrder() {
        val s = XlsxSearch(XlsxParser.parse(OoxmlPackage(book())))
        s.run("cost")
        assertEquals(2, s.hits.size)
        assertEquals(listOf(0 to 0, 2 to 2), s.hits.map { it.row to it.col })
        assertTrue(XlsxSearch.key(0, 0) in s.matchesIn(0))
        assertTrue(s.matchesIn(1).isEmpty())

        s.run("123")                                       // numbers are matched on what is displayed
        assertEquals(1, s.hits.size)
        assertEquals(1, s.hits[0].sheet)
        assertEquals(1 to 1, s.hits[0].row to s.hits[0].col)
    }

    // ---- PPTX ----

    private val a = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private val r = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun deck() = TestFiles.zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "ppt/presentation.xml" to """<p:presentation $a $p $r><p:sldIdLst><p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="x/slide" Target="slides/slide1.xml"/><Relationship Id="rId2" Type="x/slide" Target="slides/slide2.xml"/></Relationships>""",
        "ppt/slides/slide1.xml" to slide("Quarterly numbers", "Numbers up, costs down"),
        "ppt/slides/slide2.xml" to slide("Plan", "Hire NUMBERS people"),
    )

    private fun slide(title: String, body: String) = """<p:sld $a $p><p:cSld><p:spTree>
        <p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="100" cy="100"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>
        <p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="200"/><a:ext cx="100" cy="100"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:t>$body</a:t></a:r></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld></p:sld>"""

    @Test fun pptxSearchFindsTextAcrossSlidesWithOffsets() {
        val s = PptxSearch(PptxParser.parse(OoxmlPackage(deck())))
        s.run("numbers")
        assertEquals(3, s.hits.size)
        assertEquals(listOf(0, 0, 1), s.hits.map { it.slide })
        assertEquals(listOf(10, 0, 5), s.hits.map { it.start })    // "Quarterly numbers", "Numbers up…", "Hire NUMBERS…"

        // the highlight for the selected result is flagged, the others are not
        val firstShape = s.hits[0].shape
        val marks = s.highlights(firstShape, 0)
        assertEquals(1, marks.size)
        assertEquals(10, marks[0].start)
        assertEquals(17, marks[0].end)
        assertTrue(marks[0].isCurrent)
        assertFalse(s.highlights(s.hits[1].shape, 0).single().isCurrent)

        s.run("nothing like this")
        assertTrue(s.hits.isEmpty())
    }
}
