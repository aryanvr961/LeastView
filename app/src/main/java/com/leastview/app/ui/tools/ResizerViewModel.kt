package com.leastview.app.ui.tools

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leastview.app.data.FileKind
import com.leastview.app.tools.ImageOps
import com.leastview.app.tools.OfficeMedia
import com.leastview.app.tools.OutputFile
import com.leastview.app.tools.PdfOps
import com.leastview.app.tools.ToolFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ResizeResult(
    val name: String,
    val kind: FileKind?,
    val before: Long,
    val after: Long,
    val detail: String,
    val output: OutputFile?,
    val beforeThumb: ImageBitmap? = null,
    val afterThumb: ImageBitmap? = null,
    val error: String? = null,
)

class ResizerViewModel(app: Application) : AndroidViewModel(app) {
    enum class ImageMode(val label: String) { PERCENT("Percentage"), CUSTOM("Custom size") }

    enum class Strength(val label: String, val maxDim: Int, val jpegQuality: Int, val dpi: Int, val flattenQuality: Int) {
        LIGHT("Light", 2400, 85, 150, 80),
        BALANCED("Balanced", 1600, 70, 110, 65),
        STRONG("Strong", 1000, 55, 72, 50),
    }

    var items by mutableStateOf<List<ToolItem>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    // images
    var imageMode by mutableStateOf(ImageMode.PERCENT)
    var percent by mutableIntStateOf(50)
    var customWidth by mutableStateOf("")
    var customHeight by mutableStateOf("")
    var keepAspect by mutableStateOf(true)
    var imageQuality by mutableIntStateOf(85)
    /** null keeps each picture's own format. */
    var imageFormat by mutableStateOf<ImageOps.Format?>(null)

    // PDF and Office documents
    var strength by mutableStateOf(Strength.BALANCED)
    var flattenPdf by mutableStateOf(false)

    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf("")
        private set
    var results by mutableStateOf<List<ResizeResult>>(emptyList())
        private set

    val hasImages: Boolean get() = items.any { it.kind == FileKind.IMG }
    val hasPdf: Boolean get() = items.any { it.kind == FileKind.PDF }
    val hasOffice: Boolean get() = items.any { it.kind == FileKind.DOC || it.kind == FileKind.PPT || it.kind == FileKind.XLS }

    /** Why the current settings can't run yet, or null. */
    val settingsProblem: String?
        get() = when {
            items.isEmpty() -> null
            hasImages && imageMode == ImageMode.CUSTOM && customWidth.toIntOrNull().let { it == null || it < 1 } &&
                customHeight.toIntOrNull().let { it == null || it < 1 } -> "Enter a width, a height, or both."
            else -> null
        }

    val canRun: Boolean get() = items.isNotEmpty() && !busy && settingsProblem == null

    fun add(uris: List<Uri>) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { ToolItem.from(getApplication(), it) } }
            val ok = picked.filter { it.kind != null && it.kind != FileKind.TXT }
            val skipped = picked - ok.toSet()
            items = items + ok
            results = emptyList()
            notice = if (skipped.isEmpty()) null
            else "Skipped ${skipped.joinToString { it.name }}: the resizer works on images, PDF, DOCX, PPTX and XLSX files."
        }
    }

    fun remove(index: Int) {
        items = items.toMutableList().also { it.removeAt(index) }
        results = emptyList()
    }

    fun clear() {
        items = emptyList()
        results = emptyList()
        notice = null
    }

    fun run() {
        if (!canRun) return
        val todo = items
        viewModelScope.launch {
            busy = true
            results = emptyList()
            progress = 0f
            val done = ArrayList<ResizeResult>()
            try {
                todo.forEachIndexed { i, item ->
                    status = "Processing ${item.name} (${i + 1} of ${todo.size})…"
                    val result = withContext(Dispatchers.IO) { process(item) { p -> progress = (i + p) / todo.size } }
                    done += result
                    results = done.toList()
                    progress = (i + 1f) / todo.size
                }
            } finally {
                busy = false
            }
        }
    }

    private fun process(item: ToolItem, onProgress: (Float) -> Unit): ResizeResult {
        val app = getApplication<Application>()
        var work: File? = null
        try {
            val src = ToolFiles.copyIn(app, item.uri, item.name).also { work = it }
            val before = src.length()
            return when (item.kind) {
                FileKind.IMG -> resizeImage(item, src, before)
                FileKind.PDF -> shrinkPdf(item, src, before, onProgress)
                FileKind.DOC, FileKind.XLS, FileKind.PPT -> shrinkOffice(item, src, before, onProgress)
                else -> ResizeResult(item.name, item.kind, before, before, "", null, error = "This file type can't be resized.")
            }
        } catch (e: PdfOps.PdfProblem) {
            return ResizeResult(item.name, item.kind, item.size, item.size, "", null, error = e.message)
        } catch (e: Exception) {
            return ResizeResult(item.name, item.kind, item.size, item.size, "", null, error = "Couldn't process this file: ${e.message ?: "unknown error"}")
        } catch (e: OutOfMemoryError) {
            return ResizeResult(item.name, item.kind, item.size, item.size, "", null, error = "This file is too large to process on this device.")
        } finally {
            work?.let { ToolFiles.discard(it) }
        }
    }

    // ---------------------------------------------------------------- images

    private fun resizeImage(item: ToolItem, src: File, before: Long): ResizeResult {
        val app = getApplication<Application>()
        val original = ImageOps.displaySize(src) ?: error("This isn't a readable image.")
        val (tw, th) = targetSize(original.width, original.height)
        if (tw.toLong() * th > 80_000_000L || max(tw, th) > 16_000) error("The target size ${tw}x$th is too large.")

        val ext = src.extension.lowercase()
        val format = imageFormat ?: if (ext == "png") ImageOps.Format.PNG else ImageOps.Format.JPG
        val base = item.name.substringBeforeLast('.')
        val out = ToolFiles.newOutput(app, "${base}_${tw}x$th.${format.extension}")
        ImageOps.resize(src, tw, th, imageQuality, format, out)

        val beforeThumb = ImageOps.thumbnail(src, 360)?.asImageBitmap()
        val afterThumb = ImageOps.thumbnail(out, 360)?.asImageBitmap()
        return ResizeResult(
            name = item.name, kind = FileKind.IMG, before = before, after = out.length(),
            detail = "${original.width}x${original.height} → ${tw}x$th px" + if (format == ImageOps.Format.JPG) ", quality $imageQuality%" else "",
            output = OutputFile(out, out.name, ToolFiles.mimeFor(out.name)),
            beforeThumb = beforeThumb, afterThumb = afterThumb,
        )
    }

    private fun targetSize(w: Int, h: Int): Pair<Int, Int> = when (imageMode) {
        ImageMode.PERCENT -> max(1, (w * percent / 100f).roundToInt()) to max(1, (h * percent / 100f).roundToInt())
        ImageMode.CUSTOM -> {
            val cw = customWidth.toIntOrNull()?.takeIf { it > 0 }
            val ch = customHeight.toIntOrNull()?.takeIf { it > 0 }
            if (keepAspect) {
                // fit inside the requested box without distorting; one missing side leaves that side free
                val scale = when {
                    cw != null && ch != null -> min(cw.toFloat() / w, ch.toFloat() / h)
                    cw != null -> cw.toFloat() / w
                    else -> ch!!.toFloat() / h
                }
                max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
            } else (cw ?: w) to (ch ?: h)
        }
    }

    // ---------------------------------------------------------------- documents

    private fun shrinkPdf(item: ToolItem, src: File, before: Long, onProgress: (Float) -> Unit): ResizeResult {
        val app = getApplication<Application>()
        PdfOps.init(app)
        val base = item.name.substringBeforeLast('.')
        val out = ToolFiles.newOutput(app, "${base}_smaller.pdf")
        val detail: String
        if (flattenPdf) {
            PdfOps.flatten(src, out, strength.dpi, strength.flattenQuality, onProgress)
            detail = "Pages rendered as pictures at ${strength.dpi} dpi. Text is no longer selectable."
        } else {
            val stats = PdfOps.optimizeImages(src, out, strength.maxDim, strength.jpegQuality, onProgress)
            if (stats.found == 0) return unchanged(item, before, out, "This PDF has no pictures to compress. Text and vector pages are already compact; use \"Flatten pages\" to shrink further (text becomes non-selectable).")
            if (stats.replaced == 0) return unchanged(item, before, out, "Its ${stats.found} picture${if (stats.found == 1) " is" else "s are"} already small or can't be recompressed safely.")
            detail = "Recompressed ${stats.replaced} of ${stats.found} picture${if (stats.found == 1) "" else "s"}; text and layout untouched."
        }
        return finish(item, before, out, detail, FileKind.PDF)
    }

    private fun shrinkOffice(item: ToolItem, src: File, before: Long, onProgress: (Float) -> Unit): ResizeResult {
        val app = getApplication<Application>()
        val ext = src.extension.lowercase()
        val base = item.name.substringBeforeLast('.')
        val out = ToolFiles.newOutput(app, "${base}_smaller.$ext")
        val stats = OfficeMedia.shrink(src, out, strength.maxDim, strength.jpegQuality, onProgress)
        if (stats.images == 0) return unchanged(item, before, out, "This document has no embedded pictures, so there is nothing to compress.")
        if (stats.recompressed == 0) return unchanged(item, before, out, "Its ${stats.images} picture${if (stats.images == 1) " is" else "s are"} already small.")
        return finish(item, before, out, "Recompressed ${stats.recompressed} of ${stats.images} picture${if (stats.images == 1) "" else "s"}; text and formatting untouched.", item.kind)
    }

    /** Keeps the result only when it really is smaller than the original. */
    private fun finish(item: ToolItem, before: Long, out: File, detail: String, kind: FileKind?): ResizeResult {
        val after = out.length()
        if (after >= before) return unchanged(item, before, out, "The result wasn't smaller than the original ($detail). Try a stronger setting.")
        return ResizeResult(item.name, kind, before, after, detail, OutputFile(out, out.name, ToolFiles.mimeFor(out.name)))
    }

    private fun unchanged(item: ToolItem, before: Long, out: File, why: String): ResizeResult {
        out.parentFile?.deleteRecursively()
        return ResizeResult(item.name, item.kind, before, before, why, null)
    }
}
