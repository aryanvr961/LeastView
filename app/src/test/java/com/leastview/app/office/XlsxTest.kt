package com.leastview.app.office

import com.leastview.app.office.xlsx.NumFmt
import com.leastview.app.office.xlsx.XlsxParser
import com.leastview.app.office.xlsx.XlsxWriter
import com.leastview.app.office.xlsx.columnIndex
import com.leastview.app.office.xlsx.columnName
import com.leastview.app.office.xlsx.parseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class XlsxTest {
    private val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun workbook(): File = TestFiles.zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "xl/workbook.xml" to "<workbook $ns><sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Other\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="x/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="x/worksheet" Target="/xl/worksheets/sheet2.xml"/></Relationships>""",
        "xl/sharedStrings.xml" to "<sst $ns><si><t>Name</t></si><si><r><t>Rich </t></r><r><t>text</t></r></si></sst>",
        "xl/styles.xml" to """<styleSheet $ns>
            <numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>
            <fonts><font><sz val="11"/></font><font><b/><color rgb="FFFF0000"/></font></fonts>
            <fills><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
                   <fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/></patternFill></fill></fills>
            <cellXfs><xf numFmtId="0" fontId="0" fillId="0"/><xf numFmtId="164" fontId="0" fillId="0"/>
                     <xf numFmtId="14" fontId="0" fillId="0"/><xf numFmtId="0" fontId="1" fillId="2"/><xf numFmtId="9" fontId="0" fillId="0"/></cellXfs></styleSheet>""",
        "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetFormatPr defaultColWidth="10"/><cols><col min="1" max="2" width="20"/></cols>
            <sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" s="3"><v>7</v></c></row>
              <row r="2"><c r="A2" s="1"><v>1234.5</v></c><c r="B2" s="2"><v>45000</v></c><c r="C2" s="4"><v>0.256</v></c>
                         <c r="D2"><f>A2*2</f><v>2469</v></c></row>
              <row r="4"><c r="A4" t="inlineStr"><is><t>inline</t></is></c><c r="B4" t="b"><v>1</v></c></row>
            </sheetData><mergeCells><mergeCell ref="A6:B7"/></mergeCells></worksheet>""",
        "xl/worksheets/sheet2.xml" to "<worksheet $ns><sheetData/></worksheet>",
    )

    @Test fun parsesCellsFormatsAndLayout() {
        val wb = XlsxParser.parse(OoxmlPackage(workbook()))
        assertEquals(listOf("Data", "Other"), wb.sheets.map { it.name })
        val s = wb.sheets[0]
        assertEquals("Name", s.cell(0, 0)!!.display)
        assertEquals("Rich text", s.cell(0, 1)!!.display)
        assertEquals("1,234.50", s.cell(1, 0)!!.display)
        assertEquals("15/03/2023", s.cell(1, 1)!!.display)
        assertEquals("26%", s.cell(1, 2)!!.display)
        assertEquals("inline", s.cell(3, 0)!!.display)
        assertEquals("TRUE", s.cell(3, 1)!!.display)

        val formula = s.cell(1, 3)!!
        assertTrue(formula.hasFormula)
        assertEquals("A2*2", formula.formula)
        assertEquals("2469", formula.display)

        val styled = s.cell(0, 2)!!
        assertTrue(styled.style.bold)
        assertEquals("FFFF00", styled.style.fill)
        assertEquals("FF0000", styled.style.color)

        assertEquals(20.0, s.colWidthChars[0]!!, 0.0)
        assertEquals(10.0, s.defaultColWidthChars, 0.0)
        assertNotNull(s.mergeCovering(5, 1))
        assertNull(s.mergeCovering(0, 0))
        assertEquals(4, s.maxRow)
        assertEquals(4, s.maxCol)
    }

    @Test fun editsRoundTrip() {
        val src = workbook()
        val wb = XlsxParser.parse(OoxmlPackage(src))
        val s = wb.sheets[0]
        s.edit(1, 0, "99.5")           // replace a number
        s.edit(0, 0, "Renamed")        // replace shared string with text
        s.edit(2, 1, "new row")         // row 3 does not exist yet; it must be created in order
        s.edit(3, 0, "")                // clear a cell

        val out = ByteArrayOutputStream()
        XlsxWriter.write(wb, out)
        val saved = File.createTempFile("saved", ".xlsx").apply { writeBytes(out.toByteArray()); deleteOnExit() }

        val again = XlsxParser.parse(OoxmlPackage(saved)).sheets[0]
        assertEquals("Renamed", again.cell(0, 0)!!.display)
        assertEquals("Rich text", again.cell(0, 1)!!.display)          // untouched
        assertEquals("99.50", again.cell(1, 0)!!.display)               // keeps its number format
        assertEquals("new row", again.cell(2, 1)!!.display)
        assertNull(again.cell(3, 0))
        assertEquals("2469", again.cell(1, 3)!!.display)                // formula cell untouched
        assertTrue(String(OoxmlPackage(saved).bytes("xl/workbook.xml")!!).contains("fullCalcOnLoad"))

        // rows must stay sorted: 1, 2, 3, 4
        val xml = String(OoxmlPackage(saved).bytes("xl/worksheets/sheet1.xml")!!)
        val order = Regex("<row r=\"(\\d+)\"").findAll(xml).map { it.groupValues[1].toInt() }.toList()
        assertEquals(order.sorted(), order)
        assertTrue(3 in order)
    }

    @Test fun numberFormats() {
        assertEquals("1234", NumFmt.format(1234.0, null))
        assertEquals("0.1", NumFmt.format(0.1, "General"))
        assertEquals("12.346", NumFmt.format(12.3456, "0.000"))
        assertEquals("1,234,567", NumFmt.format(1234567.0, "#,##0"))
        assertEquals("25.5%", NumFmt.format(0.255, "0.0%"))
        assertEquals("₹1,234.50", NumFmt.format(1234.5, "[\$₹-4009]#,##0.00"))
        assertEquals("(5)", NumFmt.format(-5.0, "0;(0)"))
        assertEquals("2023-03-15", NumFmt.format(45000.0, "yyyy-mm-dd"))
        assertEquals("15-Mar-23", NumFmt.format(45000.0, "d-mmm-yy"))
        assertEquals("06:00", NumFmt.format(0.25, "hh:mm"))
        assertEquals("6:30 PM", NumFmt.format(0.75 + 0.5 / 24, "h:mm AM/PM"))
        assertEquals("1.23E+03", NumFmt.format(1234.0, "0.00E+00"))
    }

    @Test fun onlyRealNumbersAreStoredAsNumbers() {
        for (ok in listOf("12", " 12 ", "-3.5", "+.5", "1e3", "1E-2", "0")) assertNotNull(ok, com.leastview.app.office.xlsx.parseCellNumber(ok))
        // each of these parses with String.toDouble() but must stay text: written as <v> they would corrupt the sheet
        for (bad in listOf("NaN", "Infinity", "-Infinity", "12f", "5d", "0x1p3", "1,5", "", "abc", "1e999"))
            assertNull(bad, com.leastview.app.office.xlsx.parseCellNumber(bad))
    }

    @Test fun cellReferences() {
        assertEquals("A", columnName(0))
        assertEquals("Z", columnName(25))
        assertEquals("AA", columnName(26))
        assertEquals("AZ", columnName(51))
        assertEquals(27, columnIndex("AB"))
        assertEquals(11 to 1, parseRef("B12"))
    }
}
