package com.leastview.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.docx.Align
import com.leastview.app.office.docx.DocParagraph
import com.leastview.app.office.docx.DocTable
import com.leastview.app.office.docx.DocxDocument
import com.leastview.app.office.docx.DocxParser
import com.leastview.app.office.docx.RunStyle
import com.leastview.app.office.pptx.PPara
import com.leastview.app.office.pptx.PicShape
import com.leastview.app.office.pptx.PlaceholderShape
import com.leastview.app.office.pptx.PptxDeck
import com.leastview.app.office.pptx.PptxParser
import com.leastview.app.office.pptx.Shape
import com.leastview.app.office.pptx.TableShape
import com.leastview.app.office.pptx.TextShape
import com.leastview.app.office.xlsx.Sheet
import com.leastview.app.office.xlsx.Workbook
import com.leastview.app.office.xlsx.XlsxParser
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Converts text and Office documents into PDF so they can be merged with other files.
 * Layout is simplified (no fields, headers/footers, charts or SmartArt), so the output is a faithful
 * reading copy rather than a pixel-exact one.
 */
object ToPdf {
    class Unsupported(message: String) : Exception(message)

    fun txt(src: File, out: File) {
        if (src.length() > 2 * 1024 * 1024) throw Unsupported("${src.name} is too large to convert (limit 2 MB for text files).")
        val text = TextFiles.read(src).text
        val pg = Pager(595, 842, 54f, 54f, 54f, 54f)
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (595 - 108)).setLineSpacing(0f, 1.15f).build()
            pg.drawLayout(layout, 54f)
            pg.write(out)
        } finally {
            pg.close()
        }
    }

    fun docx(src: File, out: File) {
        OoxmlPackage(src).use { pkg ->
            val doc = DocxParser.parse(pkg)
            DocxPdf(doc).render(out)
        }
    }

    fun xlsx(src: File, out: File) {
        OoxmlPackage(src).use { pkg -> XlsxPdf(XlsxParser.parse(pkg)).render(out) }
    }

    fun pptx(src: File, out: File) {
        OoxmlPackage(src).use { pkg -> PptxPdf(PptxParser.parse(pkg)).render(out) }
    }
}

/** Fills pages top to bottom and starts a new one when content no longer fits. */
internal class Pager(
    val pageW: Int, val pageH: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
        private set
    var y = top
    private var number = 0

    val contentBottom: Float get() = pageH - bottom
    val contentWidth: Float get() = pageW - left - right

    fun ensurePage(): Canvas {
        canvas?.let { return it }
        number++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, number).create())
        page = p
        canvas = p.canvas
        y = top
        return p.canvas
    }

    fun newPage(): Canvas {
        finishPage()
        return ensurePage()
    }

    private fun finishPage() {
        page?.let { doc.finishPage(it) }
        page = null
        canvas = null
    }

    /** Draws a (possibly multi-page) text layout at horizontal offset [x], advancing [y]. */
    fun drawLayout(layout: StaticLayout, x: Float) {
        var line = 0
        while (line < layout.lineCount) {
            val c = ensurePage()
            val firstTop = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - firstTop <= contentBottom - y) last++
            if (last == line) {
                if (y > top) { newPage(); continue }
                last = line + 1 // a single line taller than the page: draw it anyway
            }
            val h = (layout.getLineBottom(last - 1) - firstTop).toFloat()
            c.save()
            c.translate(x, y)
            c.clipRect(0f, 0f, layout.width.toFloat(), h)
            c.translate(0f, -firstTop.toFloat())
            layout.draw(c)
            c.restore()
            y += h
            line = last
            if (line < layout.lineCount) newPage()
        }
    }

    fun write(out: File) {
        ensurePage() // a PDF needs at least one page
        finishPage()
        out.outputStream().use { doc.writeTo(it) }
    }

    fun close() {
        page?.let { runCatching { doc.finishPage(it) } }
        doc.close()
    }
}

// ------------------------------------------------------------------ DOCX

private class DocxPdf(private val doc: DocxDocument) {
    private val pageW = (doc.page.widthTw / 20f).toInt().coerceIn(200, 2000)
    private val pageH = (doc.page.heightTw / 20f).toInt().coerceIn(200, 3000)
    private val pg = Pager(
        pageW, pageH,
        doc.page.marginLeftTw / 20f, doc.page.marginTopTw / 20f,
        doc.page.marginRightTw / 20f, doc.page.marginBottomTw / 20f,
    )

    fun render(out: File) {
        try {
            for (b in doc.blocks) when (b) {
                is DocParagraph -> paragraph(b, pg.left, pg.contentWidth)
                is DocTable -> table(b)
            }
            pg.write(out)
        } finally {
            pg.close()
        }
    }

    private fun fontFor(name: String?): String? = when {
        name == null -> null
        name.contains("times", true) || name.contains("georgia", true) || name.contains("cambria", true) -> "serif"
        name.contains("courier", true) || name.contains("consolas", true) -> "monospace"
        else -> null
    }

    private fun colorOf(hex: String?, fallback: Int): Int =
        hex?.takeIf { it.length == 6 }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: fallback

    private val highlightColors = mapOf(
        "yellow" to Color.YELLOW, "green" to Color.GREEN, "cyan" to Color.CYAN, "magenta" to Color.MAGENTA,
        "lightGray" to Color.LTGRAY, "red" to Color.RED, "blue" to Color.BLUE,
    )

    private fun spannable(p: DocParagraph, prefix: String = ""): SpannableStringBuilder {
        val sb = SpannableStringBuilder(prefix)
        val shift = prefix.length
        sb.append(p.text)
        var i = 0
        while (i < p.text.length) {
            val s: RunStyle = p.styles.getOrNull(i) ?: p.base
            var j = i + 1
            while (j < p.text.length && p.styles.getOrNull(j) == s) j++
            val a = shift + i
            val b = shift + j
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            sb.setSpan(AbsoluteSizeSpan(s.sizeHalfPt / 2, false), a, b, f)
            if (s.bold || s.italic) sb.setSpan(StyleSpan(if (s.bold && s.italic) Typeface.BOLD_ITALIC else if (s.bold) Typeface.BOLD else Typeface.ITALIC), a, b, f)
            if (s.underline) sb.setSpan(UnderlineSpan(), a, b, f)
            if (s.strike) sb.setSpan(StrikethroughSpan(), a, b, f)
            s.color?.let { sb.setSpan(ForegroundColorSpan(colorOf(it, Color.BLACK)), a, b, f) }
            s.highlight?.let { h -> highlightColors[h]?.let { sb.setSpan(BackgroundColorSpan(it), a, b, f) } }
            fontFor(s.font)?.let { sb.setSpan(TypefaceSpan(it), a, b, f) }
            i = j
        }
        return sb
    }

    private fun layoutFor(p: DocParagraph, width: Float, prefix: String = ""): StaticLayout {
        val sb = spannable(p, prefix)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = p.base.sizeHalfPt / 2f
            color = colorOf(p.base.color, Color.BLACK)
            fontFor(p.base.font)?.let { typeface = Typeface.create(it, Typeface.NORMAL) }
        }
        val b = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width.toInt().coerceAtLeast(20))
            .setAlignment(
                when (p.props.align) {
                    Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            .setLineSpacing(0f, p.props.lineMult.coerceIn(1f, 2.5f))
        if (p.props.align == Align.JUSTIFY) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        val first = p.props.firstLineTw / 20f
        if (first > 0 && p.props.marker == null) b.setIndents(intArrayOf(first.toInt(), 0), null)
        return b.build()
    }

    private fun paragraph(p: DocParagraph, originX: Float, availWidth: Float) {
        val props = p.props
        val leftPt = props.indentLeftTw / 20f
        val firstPt = props.firstLineTw / 20f
        val hanging = if (firstPt < 0) -firstPt else 0f
        val markerW = if (props.marker != null) (if (hanging > 0) hanging else 18f) else 0f
        val textLeft = if (props.marker != null) max(leftPt, markerW) else leftPt
        val layout = layoutFor(p, availWidth - textLeft)

        if (pg.y > pg.top) pg.y += props.spaceBeforeTw / 20f
        pg.ensurePage()
        if (pg.contentBottom - pg.y < layout.getLineBottom(0) && pg.y > pg.top) pg.newPage()

        if (props.marker != null) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = p.base.sizeHalfPt / 2f; color = Color.BLACK }
            pg.canvas?.drawText(props.marker, originX + max(textLeft - markerW, 0f), pg.y + layout.getLineBaseline(0), paint)
        }
        val startY = pg.y
        pg.drawLayout(layout, originX + textLeft)
        if (props.borderBottom || props.borderTop) {
            val line = Paint().apply { color = Color.DKGRAY; strokeWidth = 0.7f }
            pg.canvas?.let { c ->
                if (props.borderBottom) c.drawLine(originX, pg.y, originX + availWidth, pg.y, line)
                if (props.borderTop) c.drawLine(originX, startY, originX + availWidth, startY, line)
            }
        }
        pg.y += props.spaceAfterTw / 20f
        for (img in p.images) image(img.path, img.widthEmu, img.heightEmu, originX, availWidth)
    }

    private fun image(path: String, wEmu: Long, hEmu: Long, x: Float, availWidth: Float) {
        val bytes = doc.pkg.bytes(path) ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return // formats Android can't decode (EMF/WMF) are left out
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        try {
            var w = if (wEmu > 0) wEmu / 12700f else bmp.width.toFloat()
            var h = if (hEmu > 0) hEmu / 12700f else bmp.height * w / bmp.width
            if (w > availWidth) { h *= availWidth / w; w = availWidth }
            val maxH = pg.pageH - pg.top - pg.bottom
            if (h > maxH) { w *= maxH / h; h = maxH }
            pg.ensurePage()
            if (pg.y + h > pg.contentBottom) pg.newPage()
            pg.canvas?.drawBitmap(bmp, null, RectF(x, pg.y, x + w, pg.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            pg.y += h + 4f
        } finally {
            bmp.recycle()
        }
    }

    private fun table(t: DocTable) {
        val pad = 3f
        val border = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        for (row in t.rows) {
            if (row.isEmpty()) continue
            val weights = row.map { max(it.widthTw, 1).toFloat() }
            val total = weights.sum()
            val widths = weights.map { it / total * pg.contentWidth }
            val layouts = row.mapIndexed { i, cell ->
                cell.blocks.filterIsInstance<DocParagraph>().map { p ->
                    layoutFor(p, widths[i] - 2 * pad, p.props.marker?.let { "$it " }.orEmpty())
                }
            }
            val heights = layouts.map { ls -> ls.sumOf { it.height.toDouble() }.toFloat() + 2 * pad }
            val rowH = (heights.maxOrNull() ?: 0f).coerceAtLeast(14f).coerceAtMost(pg.pageH - pg.top - pg.bottom)
            pg.ensurePage()
            if (pg.y + rowH > pg.contentBottom && pg.y > pg.top) pg.newPage()
            val c = pg.canvas ?: continue
            var x = pg.left
            row.forEachIndexed { i, cell ->
                cell.shade?.let { hex ->
                    c.drawRect(x, pg.y, x + widths[i], pg.y + rowH, Paint().apply { color = colorOf(hex, Color.WHITE) })
                }
                if (t.bordered) c.drawRect(x, pg.y, x + widths[i], pg.y + rowH, border)
                var cy = pg.y + pad
                for (l in layouts[i]) {
                    c.save()
                    c.clipRect(x, pg.y, x + widths[i], pg.y + rowH)
                    c.translate(x + pad, cy)
                    l.draw(c)
                    c.restore()
                    cy += l.height
                }
                x += widths[i]
            }
            pg.y += rowH
        }
        pg.y += 4f
    }
}

// ------------------------------------------------------------------ XLSX

private class XlsxPdf(private val wb: Workbook) {
    private val pageW = 842
    private val pageH = 595
    private val margin = 28f
    private val pg = Pager(pageW, pageH, margin, margin, margin, margin + 10f)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.BLACK }
    private val grid = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.4f }

    private fun colWidth(s: Sheet, c: Int): Float =
        if (c in s.hiddenCols) 0f else (((s.colWidthChars[c] ?: s.defaultColWidthChars) * 7 + 5) * 0.75).toFloat().coerceIn(18f, 260f)

    private fun rowHeight(s: Sheet, r: Int): Float =
        if (r in s.hiddenRows) 0f else (s.rowHeightPt[r] ?: 15.0).toFloat().coerceAtLeast(12f)

    fun render(out: File) {
        try {
            for (sheet in wb.sheets) renderSheet(sheet)
            pg.write(out)
        } finally {
            pg.close()
        }
    }

    private fun renderSheet(s: Sheet) {
        val rows = min(s.maxRow, 20_000)
        val cols = min(s.maxCol, 120)
        if (rows == 0 || cols == 0) return

        // split columns into bands that fit the page width, then print each band top to bottom
        val avail = pageW - 2 * margin
        val bands = ArrayList<IntRange>()
        var start = 0
        var w = 0f
        for (c in 0 until cols) {
            val cw = colWidth(s, c)
            if (c > start && w + cw > avail) { bands += start until c; start = c; w = 0f }
            w += cw
        }
        bands += start until cols

        for (band in bands) {
            var r = 0
            while (r < rows) {
                val canvas = pg.newPageIfUsed()
                canvas.drawText(s.name, margin, margin - 8f, TextPaint(text).apply { isFakeBoldText = true })
                pg.y = margin
                while (r < rows) {
                    val h = rowHeight(s, r)
                    if (h == 0f) { r++; continue }
                    if (pg.y + h > pg.contentBottom) break
                    drawRow(canvas, s, r, band, h)
                    pg.y += h
                    r++
                }
                if (pg.y == margin) r++ // a row taller than a page can't make progress otherwise
            }
        }
    }

    private fun drawRow(c: Canvas, s: Sheet, r: Int, band: IntRange, h: Float) {
        var x = margin
        var col = band.first
        while (col <= band.last) {
            val merge = s.mergeCovering(r, col)
            if (merge != null && !(merge.r1 == r && merge.c1 == col)) {
                if (merge.r1 == r) { col++; continue }
                x += colWidth(s, col); col++; continue
            }
            val lastCol = if (merge != null) min(merge.c2, band.last) else col
            var w = 0f
            for (k in col..lastCol) w += colWidth(s, k)
            if (w > 0f) {
                val cell = s.cell(r, col)
                cell?.style?.fill?.let { hex -> c.drawRect(x, pg.y, x + w, pg.y + h, Paint().apply { color = runCatching { Color.parseColor("#$hex") }.getOrDefault(Color.WHITE) }) }
                c.drawRect(x, pg.y, x + w, pg.y + h, grid)
                if (cell != null && cell.display.isNotEmpty()) {
                    val p = TextPaint(text).apply {
                        typeface = Typeface.create(Typeface.DEFAULT, if (cell.style.bold && cell.style.italic) Typeface.BOLD_ITALIC else if (cell.style.bold) Typeface.BOLD else if (cell.style.italic) Typeface.ITALIC else Typeface.NORMAL)
                        color = cell.style.color?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: Color.BLACK
                    }
                    val tw = p.measureText(cell.display)
                    val right = cell.style.halign == "right" || (cell.style.halign == null && !cell.isText)
                    val center = cell.style.halign == "center"
                    val tx = when { right -> x + w - 3f - tw; center -> x + (w - tw) / 2; else -> x + 3f }
                    c.save()
                    c.clipRect(x, pg.y, x + w, pg.y + h)
                    c.drawText(cell.display, max(tx, x + 1f), pg.y + h / 2 + p.textSize * 0.35f, p)
                    c.restore()
                }
            }
            x += w
            col = lastCol + 1
        }
    }
}

private fun Pager.newPageIfUsed(): Canvas = if (canvas == null) ensurePage() else newPage()

// ------------------------------------------------------------------ PPTX

private class PptxPdf(private val deck: PptxDeck) {
    private val emu = 12700f

    fun render(out: File) {
        val w = (deck.widthEmu / emu).toInt().coerceIn(100, 3000)
        val h = (deck.heightEmu / emu).toInt().coerceIn(100, 3000)
        val doc = PdfDocument()
        try {
            deck.slides.forEachIndexed { i, slide ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val c = page.canvas
                c.drawColor(slide.background?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: Color.WHITE)
                for (shape in slide.shapes) runCatching { draw(c, shape) }
                doc.finishPage(page)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun hex(s: String?): Int? = s?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }

    private fun draw(c: Canvas, shape: Shape) {
        val x = shape.x / emu; val y = shape.y / emu
        val w = shape.w / emu; val h = shape.h / emu
        c.save()
        try {
            if (shape.rotDeg != 0f) c.rotate(shape.rotDeg, x + w / 2, y + h / 2)
            when (shape) {
                is TextShape -> text(c, shape, x, y, w, h)
                is PicShape -> picture(c, shape, x, y, w, h)
                is TableShape -> table(c, shape, x, y, w)
                is PlaceholderShape -> {
                    c.drawRect(x, y, x + w, y + h, Paint().apply { color = 0x22888888 })
                    c.drawText(shape.label, x + 6f, y + 18f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.GRAY })
                }
            }
        } finally {
            c.restore()
        }
    }

    private fun text(c: Canvas, s: TextShape, x: Float, y: Float, w: Float, h: Float) {
        c.save()
        if (s.flipH || s.flipV) c.scale(if (s.flipH) -1f else 1f, if (s.flipV) -1f else 1f, x + w / 2, y + h / 2)
        val fill = hex(s.fill)
        val line = hex(s.line)
        val stroke = max(s.lineWidthEmu / emu, 0.5f)
        if (s.isLine) {
            if (line != null) c.drawLine(x, y, x + w, y + h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = stroke })
        } else {
            val rect = RectF(x, y, x + w, y + h)
            fun paintOf(color: Int, st: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; if (st) { style = Paint.Style.STROKE; strokeWidth = stroke } }
            when (s.geom) {
                "ellipse" -> { if (fill != null) c.drawOval(rect, paintOf(fill, false)); if (line != null) c.drawOval(rect, paintOf(line, true)) }
                "roundRect" -> {
                    val r = min(w, h) * 0.1667f
                    if (fill != null) c.drawRoundRect(rect, r, r, paintOf(fill, false))
                    if (line != null) c.drawRoundRect(rect, r, r, paintOf(line, true))
                }
                else -> { if (fill != null) c.drawRect(rect, paintOf(fill, false)); if (line != null) c.drawRect(rect, paintOf(line, true)) }
            }
        }
        c.restore()

        if (!s.hasText) return
        val left = x + s.insetsEmu[0] / emu
        val top = y + s.insetsEmu[1] / emu
        val width = (w - (s.insetsEmu[0] + s.insetsEmu[2]) / emu).coerceAtLeast(10f)
        val height = h - (s.insetsEmu[1] + s.insetsEmu[3]) / emu
        val layouts = s.paragraphs.map { paragraph(it, s.fontScale, width) }
        val total = layouts.sumOf { (it.first.height + it.second).toDouble() }.toFloat()
        var cy = top + when (s.anchor) { "ctr" -> (height - total) / 2; "b" -> height - total; else -> 0f }.coerceAtLeast(0f)
        c.save()
        c.clipRect(x, y, x + w, y + h)
        for ((layout, before, indent) in layouts.map { Triple(it.first, it.second, it.third) }) {
            cy += before
            c.save()
            c.translate(left + indent, cy)
            layout.draw(c)
            c.restore()
            cy += layout.height
        }
        c.restore()
    }

    /** Layout, space-before and left indent for one paragraph. */
    private fun paragraph(p: PPara, fontScale: Float, width: Float): Triple<StaticLayout, Float, Float> {
        val sb = SpannableStringBuilder()
        p.bullet?.let { b -> sb.append(b).append(' ') }
        val bulletLen = sb.length
        for (r in p.runs) {
            val a = sb.length
            sb.append(r.text)
            val b = sb.length
            if (b == a) continue
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            sb.setSpan(AbsoluteSizeSpan(max(r.sizePt * fontScale, 1f).toInt(), false), a, b, f)
            if (r.bold || r.italic) sb.setSpan(StyleSpan(if (r.bold && r.italic) Typeface.BOLD_ITALIC else if (r.bold) Typeface.BOLD else Typeface.ITALIC), a, b, f)
            if (r.underline) sb.setSpan(UnderlineSpan(), a, b, f)
            hex(r.color)?.let { sb.setSpan(ForegroundColorSpan(it), a, b, f) }
        }
        if (bulletLen > 0 && p.runs.isNotEmpty()) hex(p.runs[0].color)?.let { sb.setSpan(ForegroundColorSpan(it), 0, bulletLen, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val size = max((p.runs.firstOrNull()?.sizePt ?: 18f) * fontScale, 1f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = hex(p.runs.firstOrNull()?.color) ?: Color.BLACK }
        val indent = p.marLEmu / emu
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, (width - indent).toInt().coerceAtLeast(10))
            .setAlignment(when (p.align) { "ctr" -> Layout.Alignment.ALIGN_CENTER; "r" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL })
            .setLineSpacing(0f, p.lineMult.coerceIn(0.8f, 2f))
            .build()
        return Triple(layout, p.spaceBeforePt, indent)
    }

    private fun picture(c: Canvas, s: PicShape, x: Float, y: Float, w: Float, h: Float) {
        val bytes = deck.pkg.bytes(s.path) ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        try {
            val l = s.crop[0]; val t = s.crop[1]; val r = s.crop[2]; val b = s.crop[3]
            val src = android.graphics.Rect((bmp.width * l).toInt(), (bmp.height * t).toInt(), (bmp.width * (1 - r)).toInt().coerceAtLeast(1), (bmp.height * (1 - b)).toInt().coerceAtLeast(1))
            c.save()
            if (s.flipH || s.flipV) c.scale(if (s.flipH) -1f else 1f, if (s.flipV) -1f else 1f, x + w / 2, y + h / 2)
            c.drawBitmap(bmp, src, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } finally {
            bmp.recycle()
        }
    }

    private fun table(c: Canvas, t: TableShape, x: Float, y: Float, w: Float) {
        val total = t.colWidthsEmu.sum().coerceAtLeast(1L).toFloat()
        val border = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        var cy = y
        for (row in t.rows) {
            val rh = row.heightEmu / emu
            var cx = x
            row.cells.forEachIndexed { i, cell ->
                val cw = (t.colWidthsEmu.getOrNull(i) ?: (total / row.cells.size).toLong()) / total * w
                hex(cell.fill)?.let { c.drawRect(cx, cy, cx + cw, cy + rh, Paint().apply { color = it }) }
                c.drawRect(cx, cy, cx + cw, cy + rh, border)
                val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = min(cell.sizePt, 14f); color = hex(cell.color) ?: Color.BLACK
                    if (cell.bold) typeface = Typeface.DEFAULT_BOLD
                }
                c.save()
                c.clipRect(cx, cy, cx + cw, cy + rh)
                c.drawText(cell.text.lineSequence().firstOrNull().orEmpty(), cx + 4f, cy + rh / 2 + p.textSize * 0.35f, p)
                c.restore()
                cx += cw
            }
            cy += rh
        }
    }
}
