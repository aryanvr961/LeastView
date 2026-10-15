package com.leastview.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.max
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** PDF operations on top of PdfBox. All of them are blocking and must run off the main thread. */
object PdfOps {
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                PDFBoxResourceLoader.init(context.applicationContext)
                ready = true
            }
        }
    }

    /** A problem with a specific PDF, worded for the user. */
    class PdfProblem(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Opens [file], keeping most data on disk rather than in memory. Caller closes it. */
    fun load(file: File, label: String = file.name): PDDocument = try {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    } catch (e: InvalidPasswordException) {
        throw PdfProblem("$label is password protected. Unlock it first, then try again.", e)
    } catch (e: IOException) {
        throw PdfProblem("$label is damaged or isn't a valid PDF.", e)
    } catch (e: RuntimeException) {
        throw PdfProblem("$label couldn't be read as a PDF.", e)
    }

    fun pageCount(file: File, label: String = file.name): Int = load(file, label).use { it.numberOfPages }

    /** Joins PDFs in the given order. */
    fun merge(inputs: List<Pair<File, String>>, out: File, onProgress: (Float) -> Unit = {}) {
        require(inputs.isNotEmpty()) { "Nothing to merge" }
        // fail early, naming the offending file, instead of deep inside the merge
        inputs.forEachIndexed { i, (f, label) ->
            load(f, label).close()
            onProgress(0.3f * (i + 1) / inputs.size)
        }
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it.first) }
        merger.destinationFileName = out.absolutePath
        try {
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        } catch (e: IOException) {
            throw PdfProblem("The files couldn't be merged (${e.message ?: "unknown error"}).", e)
        }
        onProgress(1f)
    }

    /** Copies the given 0-based pages, in order, into a new PDF. */
    fun extract(src: File, pages: List<Int>, out: File, label: String = src.name) {
        load(src, label).use { source ->
            PDDocument().use { target ->
                for (p in pages) target.importPage(source.getPage(p))
                target.save(out)
            }
        }
    }

    /** Writes several outputs from one open source document, so a big PDF is only parsed once. */
    fun extractMany(src: File, jobs: List<Pair<List<Int>, File>>, label: String = src.name, onProgress: (Float) -> Unit = {}) {
        load(src, label).use { source ->
            jobs.forEachIndexed { i, (pages, out) ->
                PDDocument().use { target ->
                    for (p in pages) target.importPage(source.getPage(p))
                    target.save(out)
                }
                onProgress((i + 1f) / jobs.size)
            }
        }
    }

    // ---------------------------------------------------------------- editing text

    /**
     * Applies line edits: each edited line is first covered with the page colour, then the new text is drawn in the
     * same spot. The original text stays in the file underneath (it is hidden, not removed), and everything else
     * on the page is untouched.
     *
     * [standardFont] gives one of the 14 built-in PDF fonts; [embeddedFont] is the fallback for characters those
     * can't encode (such as the rupee sign), and is only loaded when needed.
     */
    fun applyLineEdits(
        src: File,
        out: File,
        edits: List<LineEditSpec>,
        standardFont: (LineFamily, Boolean, Boolean) -> PDFont,
        embeddedFont: (PDDocument) -> PDType0Font,
    ) {
        load(src).use { doc ->
            var embedded: PDType0Font? = null
            fun fallback(): PDType0Font = embedded ?: embeddedFont(doc).also { embedded = it }

            for ((pageIndex, list) in edits.groupBy { it.page }) {
                val page = doc.getPage(pageIndex)
                val geo = PageGeometry(page.cropBox, ((page.rotation % 360) + 360) % 360)

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (e in list) {
                        val vw = geo.viewWidth
                        val vh = geo.viewHeight
                        // 1. hide the original line
                        val pad = 0.12f * e.origSizePt
                        geo.fillBox(
                            cs, e.cover,
                            e.x - pad / vw, e.baseline - (0.95f * e.origSizePt + pad) / vh,
                            e.x + e.origWidth + pad / vw, e.baseline + (0.28f * e.origSizePt + pad) / vh,
                        )
                        if (e.text.isEmpty()) continue

                        // 2. the new text
                        var font: PDFont = standardFont(e.family, e.bold, e.italic)
                        var fakeBold = false
                        var fakeItalic = false
                        try {
                            font.encode(e.text)
                        } catch (ex: IllegalArgumentException) {
                            font = fallback() // the embedded font has no bold or italic face, so those are imitated
                            fakeBold = e.bold
                            fakeItalic = e.italic
                        }
                        val width = try {
                            font.getStringWidth(e.text) / 1000f * e.sizePt
                        } catch (ex: IllegalArgumentException) {
                            throw PdfProblem("The text \"${e.text.take(30)}\" has a character that can't be written into this PDF.", ex)
                        }

                        e.highlight?.let { geo.fillBox(cs, it, e.x, e.baseline - 0.95f * e.sizePt / vh, e.x + width / vw, e.baseline + 0.28f * e.sizePt / vh) }

                        val (r, g, b) = rgb(e.color)
                        val (x, y) = geo.toUser(e.x, e.baseline)
                        var matrix = Matrix.getRotateInstance(Math.toRadians(geo.rotation.toDouble()), x, y)
                        if (fakeItalic) matrix = Matrix(1f, 0f, 0.2f, 1f, 0f, 0f).multiply(matrix)
                        cs.beginText()
                        cs.setFont(font, e.sizePt)
                        cs.setNonStrokingColor(r, g, b)
                        if (fakeBold) {
                            cs.setStrokingColor(r, g, b)
                            cs.setLineWidth(e.sizePt * 0.03f)
                            cs.setRenderingMode(RenderingMode.FILL_STROKE)
                        }
                        cs.setTextMatrix(matrix)
                        try {
                            cs.showText(e.text)
                        } catch (ex: IllegalArgumentException) {
                            throw PdfProblem("The text \"${e.text.take(30)}\" has a character that can't be written into this PDF.", ex)
                        }
                        cs.endText()
                        if (fakeBold) cs.setRenderingMode(RenderingMode.FILL)

                        // 3. underline / strike-through
                        fun rule(yFrac: Float) {
                            val (x1, y1) = geo.toUser(e.x, yFrac)
                            val (x2, y2) = geo.toUser(e.x + width / vw, yFrac)
                            cs.setStrokingColor(r, g, b)
                            cs.setLineWidth(max(0.5f, e.sizePt * 0.06f))
                            cs.moveTo(x1, y1)
                            cs.lineTo(x2, y2)
                            cs.stroke()
                        }
                        if (e.underline) rule(e.baseline + 0.12f * e.sizePt / vh)
                        if (e.strike) rule(e.baseline - 0.30f * e.sizePt / vh)
                    }
                }
            }
            doc.save(out)
        }
    }

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** Maps positions on the page as displayed (fractions, y down, rotation applied) to PDF user space. */
    private class PageGeometry(private val box: PDRectangle, val rotation: Int) {
        val viewWidth: Float = if (rotation % 180 == 0) box.width else box.height
        val viewHeight: Float = if (rotation % 180 == 0) box.height else box.width

        fun toUser(x: Float, y: Float): Pair<Float, Float> {
            val (u, v) = when (rotation) {
                90 -> y to x
                180 -> (1 - x) to (1 - y)
                270 -> (1 - y) to (1 - x)
                else -> x to (1 - y)
            }
            return (box.lowerLeftX + u * box.width) to (box.lowerLeftY + v * box.height)
        }

        /** Fills the rectangle given by two displayed corners; for rotated pages it becomes a rotated polygon. */
        fun fillBox(cs: PDPageContentStream, argb: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
            val (r, g, b) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
            cs.setNonStrokingColor(r, g, b)
            val corners = listOf(x1 to y1, x2 to y1, x2 to y2, x1 to y2).map { toUser(it.first, it.second) }
            cs.moveTo(corners[0].first, corners[0].second)
            for (c in corners.drop(1)) cs.lineTo(c.first, c.second)
            cs.closePath()
            cs.fill()
        }
    }

    // ---------------------------------------------------------------- shrinking

    class ImageStats(val found: Int, val replaced: Int)

    /**
     * Re-compresses the images inside a PDF (downscaling anything larger than [maxDim] px) while leaving
     * text, vectors and layout exactly as they were. Images that can't be handled safely are kept as they are.
     */
    fun optimizeImages(src: File, out: File, maxDim: Int, quality: Int, onProgress: (Float) -> Unit = {}): ImageStats {
        var found = 0
        var replaced = 0
        load(src).use { doc ->
            val seen = HashSet<Any>()
            val total = doc.numberOfPages.coerceAtLeast(1)
            for (i in 0 until doc.numberOfPages) {
                val stats = optimizeResources(doc, doc.getPage(i).resources, maxDim, quality, seen)
                found += stats.first
                replaced += stats.second
                onProgress((i + 1f) / total)
            }
            doc.save(out)
        }
        return ImageStats(found, replaced)
    }

    private fun optimizeResources(doc: PDDocument, res: PDResources?, maxDim: Int, quality: Int, seen: MutableSet<Any>): Pair<Int, Int> {
        res ?: return 0 to 0
        var found = 0
        var replaced = 0
        for (name in res.xObjectNames.toList()) {
            val x = try { res.getXObject(name) } catch (e: Exception) { continue }
            // an image shared by several pages is only processed once
            if (!seen.add(x.cosObject)) continue
            when (x) {
                is PDFormXObject -> optimizeResources(doc, x.resources, maxDim, quality, seen).let { found += it.first; replaced += it.second }
                is PDImageXObject -> {
                    found++
                    val smaller = recompress(doc, x, maxDim, quality)
                    if (smaller != null) {
                        res.put(name, smaller)
                        replaced++
                    }
                }
            }
        }
        return found to replaced
    }

    private fun recompress(doc: PDDocument, img: PDImageXObject, maxDim: Int, quality: Int): PDImageXObject? {
        // Anything that JPEG would damage or enlarge stays untouched: masks, transparency, 1-bit scans.
        if (img.isStencil || img.mask != null || img.softMask != null || img.bitsPerComponent == 1) return null
        val originalSize = img.stream.length
        var bmp: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            bmp = img.image ?: return null
            val longest = maxOf(bmp.width, bmp.height)
            scaled = if (longest > maxDim) {
                val f = maxDim.toFloat() / longest
                Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
            } else bmp
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size >= originalSize) return null
            return JPEGFactory.createFromByteArray(doc, bytes)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            if (scaled != null && scaled !== bmp) scaled.recycle()
            bmp?.recycle()
        }
    }

    /**
     * Strongest compression: renders every page to a JPEG and rebuilds the PDF from those pictures.
     * Text stops being selectable/searchable, so this is offered as an explicit choice only.
     */
    fun flatten(src: File, out: File, dpi: Int, quality: Int, onProgress: (Float) -> Unit = {}) {
        ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val renderer = try { PdfRenderer(fd) } catch (e: SecurityException) {
                throw PdfProblem("${src.name} is password protected. Unlock it first, then try again.", e)
            } catch (e: IOException) {
                throw PdfProblem("${src.name} is damaged or isn't a valid PDF.", e)
            }
            renderer.use { r ->
                PDDocument().use { doc ->
                    val scale = dpi / 72f
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { page ->
                            val wPt = page.width.toFloat()
                            val hPt = page.height.toFloat()
                            val bmp = Bitmap.createBitmap((wPt * scale).toInt().coerceAtLeast(1), (hPt * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                                val pdPage = PDPage(PDRectangle(wPt, hPt))
                                doc.addPage(pdPage)
                                PDPageContentStream(doc, pdPage).use { cs ->
                                    cs.drawImage(JPEGFactory.createFromByteArray(doc, bytes), 0f, 0f, wPt, hPt)
                                }
                            } finally {
                                bmp.recycle()
                            }
                        }
                        onProgress((i + 1f) / r.pageCount)
                    }
                    doc.save(out)
                }
            }
        }
    }

    /** One page per image, sized to the image's proportions. Lossless for PNG, JPEG data passed through for JPEG. */
    fun imagesToPdf(images: List<File>, out: File, onProgress: (Float) -> Unit = {}) {
        PDDocument().use { doc ->
            images.forEachIndexed { i, f ->
                val bmp = ImageOps.loadOriented(f, maxPixels = 16_000_000) ?: throw PdfProblem("${f.name} isn't a readable image.")
                try {
                    val w = 595f
                    val h = w * bmp.height / bmp.width
                    val page = PDPage(PDRectangle(w, h))
                    doc.addPage(page)
                    val image = if (f.extension.equals("png", true)) LosslessFactory.createFromImage(doc, bmp) else {
                        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        JPEGFactory.createFromByteArray(doc, bytes)
                    }
                    PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
                } finally {
                    bmp.recycle()
                }
                onProgress((i + 1f) / images.size)
            }
            doc.save(out)
        }
    }
}
