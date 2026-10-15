package com.leastview.app.office

import com.leastview.app.office.docx.Align
import com.leastview.app.office.docx.DocParagraph
import com.leastview.app.office.docx.DocTable
import com.leastview.app.office.docx.DocxParser
import com.leastview.app.office.docx.DocxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DocxTest {
    private val body = """
        <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Hello World</w:t></w:r></w:p>
        <w:p><w:r><w:t xml:space="preserve">Plain </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r>
             <w:hyperlink r:id="rId5"><w:r><w:t> link</w:t></w:r></w:hyperlink></w:p>
        <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>first</w:t></w:r></w:p>
        <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>second</w:t></w:r></w:p>
        <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>bullet</w:t></w:r></w:p>
        <w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid>
          <w:tr><w:tc><w:tcPr><w:tcW w:w="4000"/></w:tcPr><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc>
                <w:tc><w:tcPr><w:tcW w:w="4000"/><w:shd w:fill="FFFF00"/></w:tcPr><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
        <w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>PAGE</w:instrText></w:r><w:r><w:t>1</w:t></w:r></w:p>
    """.trimIndent()

    private fun parse(f: java.io.File = TestFiles.docx(body)) = DocxParser.parse(OoxmlPackage(f))

    @Test fun parsesParagraphsStylesAndTables() {
        val doc = parse()
        val paras = doc.blocks.filterIsInstance<DocParagraph>()
        val title = paras[0]
        assertEquals("Hello World", title.text)
        assertEquals(Align.CENTER, title.props.align)            // from the Heading1 style
        assertTrue(title.styles.all { it.bold && it.sizeHalfPt == 32 })

        val mixed = paras[1]
        assertEquals("Plain bold link", mixed.text)
        assertFalse(mixed.styles[0].bold)
        assertTrue(mixed.styles[6].bold)                           // inside "bold"
        assertEquals("https://example.com/", mixed.styles.last().link)
        assertTrue(mixed.styles.last().underline)                 // links are underlined by default

        assertEquals("1.", paras[2].props.marker)
        assertEquals("2.", paras[3].props.marker)
        assertEquals("•", paras[4].props.marker)

        val table = doc.blocks.filterIsInstance<DocTable>().single()
        assertTrue(table.bordered)
        assertEquals("A1", (table.rows[0][0].blocks[0] as DocParagraph).text)
        assertEquals("FFFF00", table.rows[0][1].shade)
    }

    @Test fun paragraphsWithFieldsAreReadOnly() {
        val doc = parse()
        val field = doc.blocks.filterIsInstance<DocParagraph>().last()
        assertFalse(field.editable)
        assertTrue(doc.blocks.filterIsInstance<DocParagraph>().first().editable)
    }

    @Test fun editsRoundTripAndLeaveOtherPartsUntouched() {
        val src = TestFiles.docx(body)
        val originalStyles = OoxmlPackage(src).bytes("word/styles.xml")!!.toList()

        val doc = parse(src)
        val p = doc.blocks.filterIsInstance<DocParagraph>()[1]
        p.applyEdit("Plain bold link!", null)                      // type "!" at the end
        p.restyle(0, 5) { it.copy(italic = true) }                 // italicise "Plain"
        val untouched = doc.blocks.filterIsInstance<DocParagraph>()[0]
        assertFalse(untouched.dirty)

        val out = ByteArrayOutputStream()
        DocxWriter.write(doc, out)
        val saved = java.io.File.createTempFile("saved", ".docx").apply { writeBytes(out.toByteArray()); deleteOnExit() }

        val again = parse(saved)
        val q = again.blocks.filterIsInstance<DocParagraph>()[1]
        assertEquals("Plain bold link!", q.text)
        assertTrue(q.styles[0].italic)
        assertFalse(q.styles[6].italic)
        assertTrue(q.styles[6].bold)
        assertEquals("https://example.com/", q.styles[12].link)
        assertEquals("Hello World", again.blocks.filterIsInstance<DocParagraph>()[0].text)
        assertEquals(originalStyles, OoxmlPackage(saved).bytes("word/styles.xml")!!.toList())
    }

    @Test fun newHyperlinkGetsARelationship() {
        val doc = parse()
        val p = doc.blocks.filterIsInstance<DocParagraph>()[0]
        p.applyEdit("Hello World!", null)
        p.restyle(0, 5) { it.copy(link = "https://leastview.app/") }
        val out = ByteArrayOutputStream()
        DocxWriter.write(doc, out)
        val saved = java.io.File.createTempFile("saved", ".docx").apply { writeBytes(out.toByteArray()); deleteOnExit() }
        val rels = String(OoxmlPackage(saved).bytes("word/_rels/document.xml.rels")!!)
        assertTrue(rels.contains("https://leastview.app/"))
        assertEquals("https://leastview.app/", parse(saved).blocks.filterIsInstance<DocParagraph>()[0].styles[0].link)
        // saving twice must not duplicate the relationship
        val out2 = ByteArrayOutputStream()
        DocxWriter.write(doc, out2)
        val saved2 = java.io.File.createTempFile("saved2", ".docx").apply { writeBytes(out2.toByteArray()); deleteOnExit() }
        assertEquals(1, Regex("leastview.app").findAll(String(OoxmlPackage(saved2).bytes("word/_rels/document.xml.rels")!!)).count())
    }

    @Test fun splitAndMergeParagraphs() {
        val doc = parse()
        val before = doc.blocks.size
        val p = doc.blocks.filterIsInstance<DocParagraph>()[1]
        val tail = doc.split(p, 5)
        assertNotNull(tail)
        assertEquals("Plain", p.text)
        assertEquals(" bold link", tail!!.text)
        assertEquals(before + 1, doc.blocks.size)
        assertTrue(tail.styles[1].bold)

        val (merged, caret) = doc.mergeIntoPrevious(tail)!!
        assertEquals("Plain bold link", merged.text)
        assertEquals(5, caret)
        assertEquals(before, doc.blocks.size)

        val out = ByteArrayOutputStream()
        DocxWriter.write(doc, out)
        val saved = java.io.File.createTempFile("saved", ".docx").apply { writeBytes(out.toByteArray()); deleteOnExit() }
        assertEquals(before, parse(saved).blocks.size)
        assertNull(doc.mergeIntoPrevious(doc.blocks[0] as DocParagraph)) // nothing before the first paragraph
    }

    @Test fun typingInheritsTheStyleBeforeTheCaret() {
        val doc = parse()
        val p = doc.blocks.filterIsInstance<DocParagraph>()[1]
        p.applyEdit("Plain boldX link", null)                      // "X" typed right after the bold word
        assertTrue(p.styles[10].bold)
        assertEquals("Plain boldX link".length, p.styles.size)
    }
}
