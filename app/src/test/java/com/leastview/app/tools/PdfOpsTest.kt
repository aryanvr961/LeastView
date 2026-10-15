package com.leastview.app.tools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Page i is (100 + i) points wide, so the order of pages in a result can be read back from their widths. */
class PdfOpsTest {
    private fun tmp(): File = File.createTempFile("lv-pdf", ".pdf").also { it.deleteOnExit() }

    private fun pdf(pages: Int, firstWidth: Int = 100): File = tmp().also { f ->
        PDDocument().use { d ->
            repeat(pages) { d.addPage(PDPage(PDRectangle((firstWidth + it).toFloat(), 200f))) }
            d.save(f)
        }
    }

    private fun widths(f: File): List<Int> = PdfOps.load(f).use { d -> (0 until d.numberOfPages).map { d.getPage(it).mediaBox.width.toInt() } }

    @Test fun extractsPagesInTheRequestedOrder() {
        val src = pdf(10)
        val out = tmp()
        PdfOps.extract(src, listOf(4, 0, 9), out)
        assertEquals(listOf(104, 100, 109), widths(out))
    }

    @Test fun extractManyWritesOneFilePerJob() {
        val src = pdf(10)
        val a = tmp(); val b = tmp()
        val progress = ArrayList<Float>()
        PdfOps.extractMany(src, listOf((0..2).toList() to a, listOf(7) to b)) { progress += it }
        assertEquals(listOf(100, 101, 102), widths(a))
        assertEquals(listOf(107), widths(b))
        assertEquals(1f, progress.last(), 0f)
    }

    @Test fun mergeKeepsFileOrderAndEveryPage() {
        val first = pdf(3, firstWidth = 100)
        val second = pdf(2, firstWidth = 300)
        val third = pdf(1, firstWidth = 500)
        val out = tmp()
        PdfOps.merge(listOf(second to "second", first to "first", third to "third"), out)
        assertEquals(listOf(300, 301, 100, 101, 102, 500), widths(out))
        assertEquals(6, PdfOps.pageCount(out))
    }

    @Test fun mergeNamesTheBrokenFile() {
        val good = pdf(1)
        val bad = tmp().also { it.writeText("this is not a pdf at all") }
        try {
            PdfOps.merge(listOf(good to "good.pdf", bad to "broken.pdf"), tmp())
            fail("a damaged input must stop the merge")
        } catch (e: PdfOps.PdfProblem) {
            assertTrue(e.message, e.message!!.contains("broken.pdf"))
        }
    }

    // ---- text edits (deleting a line needs no font, so the geometry can be checked without any) ----

    private fun content(f: File, page: Int = 0): String = PdfOps.load(f).use { d -> String(d.getPage(page).contents.readBytes()) }

    private fun edit(page: Int = 0, x: Float = 0.1f, baseline: Float = 0.5f, width: Float = 0.5f, size: Float = 10f) =
        LineEditSpec(page, x, baseline, width, size, text = "", LineFamily.SANS, false, false, false, false, size, 0xFF000000.toInt(), null, 0xFFFFFFFF.toInt())

    private fun apply(src: File, vararg edits: LineEditSpec): File = tmp().also { out ->
        PdfOps.applyLineEdits(src, out, edits.toList(), { _, _, _ -> error("no text is drawn when a line is deleted") }, { error("no fallback font expected") })
    }

    /** The first corner of the covering rectangle: the numbers before the first "m" operator. */
    private fun firstCorner(content: String): Pair<Double, Double> {
        val m = Regex("""(-?[0-9.]+) (-?[0-9.]+) m""").find(content) ?: error("no path in:\n$content")
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    @Test fun deletingALineCoversItWithThePageColour() {
        val out = apply(pdf(1), edit()) // 100 x 200 pt page, line at x=10%, baseline half way down
        val c = content(out)
        assertTrue(c, c.contains("1 1 1 rg"))        // white fill
        assertTrue(c, c.trimEnd().lines().any { it.trim() == "f" })
        // top-left corner: 1.2pt of padding left of x, and (cap height + padding) above the baseline
        val (x, y) = firstCorner(c)
        assertEquals(8.8, x, 0.05)
        assertEquals(110.7, y, 0.05)
        assertEquals(1, PdfOps.pageCount(out))
    }

    @Test fun editsOnRotatedPagesLandOnTheDisplayedLine() {
        val src = tmp().also { f ->
            PDDocument().use { d ->
                d.addPage(PDPage(PDRectangle(100f, 200f)).also { it.rotation = 90 }) // displayed 200 wide, 100 high
                d.save(f)
            }
        }
        val (x, y) = firstCorner(content(apply(src, edit(x = 0.25f))))
        // displayed (0.244, 0.393) -> user space (y_view * 100, x_view * 200) for a page turned 90 degrees
        assertEquals(39.3, x, 0.05)
        assertEquals(48.8, y, 0.05)
    }

    @Test fun pagesWithoutEditsAreLeftByteForByte() {
        val src = pdf(3)
        val out = apply(src, edit(page = 1))
        assertEquals(content(src, 0), content(out, 0))
        assertEquals(content(src, 2), content(out, 2))
        assertTrue(content(out, 1).isNotEmpty())
    }

    @Test fun splitThenMergeGivesBackTheOriginal() {
        val src = pdf(7)
        val parts = (0 until 7 step 3).mapIndexed { i, start -> (start until minOf(start + 3, 7)).toList() to tmp() }
        PdfOps.extractMany(src, parts)
        val merged = tmp()
        PdfOps.merge(parts.mapIndexed { i, p -> p.second to "part$i" }, merged)
        assertEquals(widths(src), widths(merged))
    }
}
