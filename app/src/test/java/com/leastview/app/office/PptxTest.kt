package com.leastview.app.office

import com.leastview.app.office.pptx.PicShape
import com.leastview.app.office.pptx.PptxParser
import com.leastview.app.office.pptx.PptxWriter
import com.leastview.app.office.pptx.TableShape
import com.leastview.app.office.pptx.TextShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PptxTest {
    private val a = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private val r = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val rels = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""

    private fun deck(): File = TestFiles.zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "ppt/presentation.xml" to """<p:presentation $a $p $r><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
            <p:sldSz cx="9144000" cy="6858000"/><p:defaultTextStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:defaultTextStyle></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """<Relationships $rels><Relationship Id="rId1" Type="x/slide" Target="slides/slide1.xml"/></Relationships>""",
        "ppt/theme/theme1.xml" to """<a:theme $a><a:themeElements><a:clrScheme name="t">
            <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
            <a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2>
            <a:accent1><a:srgbClr val="4F81BD"/></a:accent1></a:clrScheme></a:themeElements></a:theme>""",
        "ppt/slideMasters/slideMaster1.xml" to """<p:sldMaster $a $p><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="F0F0F0"/></a:solidFill></p:bgPr></p:bg><p:spTree>
              <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="500000" y="300000"/><a:ext cx="8000000" cy="1000000"/></a:xfrm></p:spPr>
                <p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/></p:txBody></p:sp>
            </p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2"/>
            <p:txStyles><p:titleStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="4000" b="1"/></a:lvl1pPr></p:titleStyle>
              <p:bodyStyle><a:lvl1pPr marL="342900" indent="-342900"><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr></p:bodyStyle></p:txStyles></p:sldMaster>""",
        "ppt/slideMasters/_rels/slideMaster1.xml.rels" to """<Relationships $rels/>""",
        "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout $a $p><p:cSld><p:spTree>
              <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>
              <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="500000" y="1500000"/><a:ext cx="8000000" cy="4000000"/></a:xfrm></p:spPr></p:sp>
            </p:spTree></p:cSld></p:sldLayout>""",
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to """<Relationships $rels><Relationship Id="rId1" Type="http://x/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""",
        "ppt/slides/slide1.xml" to """<p:sld $a $p $r><p:cSld><p:spTree>
              <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/>
                <p:txBody><a:bodyPr/><a:p><a:r><a:t>Quarterly Review</a:t></a:r></a:p></p:txBody></p:sp>
              <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/>
                <p:txBody><a:bodyPr/><a:p><a:r><a:t>First point</a:t></a:r></a:p><a:p><a:r><a:rPr sz="2000" i="1"/><a:t>Second point</a:t></a:r></a:p></p:txBody></p:sp>
              <p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="100" y="200"/><a:ext cx="300" cy="400"/></a:xfrm><a:prstGeom prst="ellipse"/>
                <a:solidFill><a:schemeClr val="accent1"/></a:solidFill></p:spPr></p:sp>
              <p:pic><p:blipFill><a:blip r:embed="rId9"/><a:srcRect l="10000" r="20000"/></p:blipFill><p:spPr><a:xfrm><a:off x="10" y="20"/><a:ext cx="30" cy="40"/></a:xfrm></p:spPr></p:pic>
              <p:graphicFrame><p:xfrm><a:off x="0" y="0"/><a:ext cx="4000" cy="1000"/></p:xfrm><a:graphic><a:graphicData uri="x"><a:tbl><a:tblGrid><a:gridCol w="2000"/><a:gridCol w="2000"/></a:tblGrid>
                <a:tr h="500"><a:tc><a:txBody><a:p><a:r><a:t>h1</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:p><a:r><a:t>h2</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>
            </p:spTree></p:cSld></p:sld>""",
        "ppt/slides/_rels/slide1.xml.rels" to """<Relationships $rels>
            <Relationship Id="rId1" Type="http://x/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
            <Relationship Id="rId9" Type="http://x/image" Target="../media/image1.png"/></Relationships>""",
    )

    @Test fun inheritsPositionSizeBulletsAndColoursFromLayoutAndMaster() {
        val d = PptxParser.parse(OoxmlPackage(deck()))
        assertEquals(9144000L, d.widthEmu)
        val slide = d.slides.single()
        assertEquals("F0F0F0", slide.background)                    // from the master

        val title = slide.shapes[0] as TextShape
        assertEquals(500000L, title.x)                              // xfrm from the master placeholder
        assertEquals("ctr", title.anchor)
        val tp = title.paragraphs.single()
        assertEquals("ctr", tp.align)
        assertEquals(40f, tp.runs.single().sizePt, 0f)
        assertTrue(tp.runs.single().bold)
        assertEquals(null, tp.bullet)                               // titles never get bullets
        assertEquals("000000", tp.runs.single().color)              // tx1 -> dk1

        val body = slide.shapes[1] as TextShape
        assertEquals(1500000L, body.y)                              // from the layout placeholder (matched by idx)
        assertEquals("•", body.paragraphs[0].bullet)
        assertEquals(28f, body.paragraphs[0].runs[0].sizePt, 0f)    // master bodyStyle
        assertEquals(20f, body.paragraphs[1].runs[0].sizePt, 0f)    // run override
        assertTrue(body.paragraphs[1].runs[0].italic)

        val oval = slide.shapes[2] as TextShape
        assertEquals("ellipse", oval.geom)
        assertEquals("4F81BD", oval.fill)

        val pic = slide.shapes[3] as PicShape
        assertEquals("ppt/media/image1.png", pic.path)
        assertEquals(0.1f, pic.crop[0], 0.0001f)
        assertEquals(0.2f, pic.crop[2], 0.0001f)

        val table = slide.shapes[4] as TableShape
        assertEquals(listOf("h1", "h2"), table.rows[0].cells.map { it.text })
    }

    @Test fun textEditRoundTrip() {
        val d = PptxParser.parse(OoxmlPackage(deck()))
        val slide = d.slides.single()
        val body = slide.shapes[1] as TextShape
        PptxWriter.setText(slide, body, "Only one\nAnd a new third")      // 2 paragraphs -> still 2, texts replaced
        assertEquals("Only one\nAnd a new third", body.plainText)
        assertTrue(slide.dirty)
        assertEquals(20f, body.paragraphs[1].runs[0].sizePt, 0f)         // formatting of the original paragraph kept

        PptxWriter.setText(slide, body, "a\nb\nc")                        // grows to 3 paragraphs
        PptxWriter.setText(slide, body, "solo")                           // shrinks to 1

        val out = ByteArrayOutputStream()
        PptxWriter.write(d, out)
        val saved = File.createTempFile("saved", ".pptx").apply { writeBytes(out.toByteArray()); deleteOnExit() }
        val again = PptxParser.parse(OoxmlPackage(saved)).slides.single()
        assertEquals("solo", (again.shapes[1] as TextShape).plainText)
        assertEquals("Quarterly Review", (again.shapes[0] as TextShape).plainText)
        assertNotNull(again.shapes[4])
    }
}
