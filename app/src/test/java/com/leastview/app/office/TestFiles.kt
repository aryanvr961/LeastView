package com.leastview.app.office

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds tiny but valid OOXML packages for tests. */
object TestFiles {
    fun zip(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("lv-test", ".zip")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return f
    }

    fun rewrite(pkg: OoxmlPackage): File {
        val out = File.createTempFile("lv-out", ".zip")
        out.deleteOnExit()
        out.outputStream().use { pkg.writeTo(it) }
        return out
    }

    const val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    fun docx(body: String, extra: Array<Pair<String, String>> = emptyArray()): File = zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $W><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:left=\"1440\" w:right=\"1440\" w:top=\"1440\" w:bottom=\"1440\"/></w:sectPr></w:body></w:document>",
        "word/styles.xml" to """<w:styles $W>
            <w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259"/></w:pPr></w:pPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
            <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
            <w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single"/></w:tblBorders></w:tblPr></w:style>
            </w:styles>""",
        "word/numbering.xml" to """<w:numbering $W>
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
            </w:numbering>""",
        "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/" TargetMode="External"/>
            </Relationships>""",
        *extra,
    )
}
