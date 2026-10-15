package com.leastview.app.ui.tools

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leastview.app.data.FileKind
import com.leastview.app.tools.OutputFile
import com.leastview.app.tools.PdfOps
import com.leastview.app.tools.TextFiles
import com.leastview.app.tools.ToPdf
import com.leastview.app.tools.ToolFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MergeResult(val output: OutputFile, val size: Long, val pages: Int?)

class MergerViewModel(app: Application) : AndroidViewModel(app) {
    enum class Output(val label: String) { PDF("PDF"), TXT("TXT") }

    var items by mutableStateOf<List<ToolItem>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<MergeResult?>(null)
        private set

    /** What the merge will produce for the current selection. */
    val output: Output
        get() = if (items.isNotEmpty() && items.all { it.kind == FileKind.TXT }) Output.TXT else Output.PDF

    /** Word, Excel and PowerPoint inputs are converted to PDF, which the user has to agree to. */
    val needsConversionConsent: Boolean get() = items.any { it.kind?.isOffice == true }

    val canMerge: Boolean get() = items.size >= 2 && !busy

    fun add(uris: List<Uri>) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { uris.map { ToolItem.from(getApplication(), it) } }
            val supported = picked.filter { it.kind != null }
            val skipped = picked.filter { it.kind == null }
            items = items + supported
            result = null
            error = null
            notice = if (skipped.isEmpty()) null
            else "Skipped ${skipped.joinToString { it.name }}: only PDF, TXT, DOCX, XLSX, PPTX and image files can be merged."
        }
    }

    fun remove(index: Int) {
        items = items.toMutableList().also { it.removeAt(index) }
        result = null
    }

    fun move(index: Int, delta: Int) {
        val to = index + delta
        if (to !in items.indices) return
        items = items.toMutableList().also { list -> list.add(to, list.removeAt(index)) }
        result = null
    }

    fun clear() {
        items = emptyList()
        result = null
        error = null
        notice = null
    }

    private class StepFailed(val file: String, cause: Throwable) : Exception(cause.message, cause)

    fun merge() {
        if (!canMerge) return
        val todo = items
        val kind = output
        viewModelScope.launch {
            busy = true
            error = null
            result = null
            progress = 0f
            try {
                result = withContext(Dispatchers.IO) { if (kind == Output.TXT) mergeText(todo) else mergePdf(todo) }
            } catch (e: StepFailed) {
                error = "Couldn't merge: ${e.file} — ${e.message ?: "unreadable file"}"
            } catch (e: PdfOps.PdfProblem) {
                error = e.message
            } catch (e: Exception) {
                error = "Merging failed: ${e.message ?: "unknown error"}"
            } catch (e: OutOfMemoryError) {
                error = "These files are too large to merge on this device."
            } finally {
                busy = false
            }
        }
    }

    private fun mergeText(todo: List<ToolItem>): MergeResult {
        val app = getApplication<Application>()
        val out = ToolFiles.newOutput(app, "merged.txt")
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            todo.forEachIndexed { i, item ->
                status = "Adding ${item.name}…"
                val f = ToolFiles.copyIn(app, item.uri, item.name)
                try {
                    val text = try { TextFiles.read(f, maxBytes = Int.MAX_VALUE / 2).text } catch (e: Exception) { throw StepFailed(item.name, e) }
                    if (i > 0) w.write("\n\n")
                    w.write(text)
                } finally {
                    ToolFiles.discard(f)
                }
                progress = (i + 1f) / todo.size
            }
        }
        return MergeResult(OutputFile(out, out.name, "text/plain"), out.length(), null)
    }

    private fun mergePdf(todo: List<ToolItem>): MergeResult {
        val app = getApplication<Application>()
        PdfOps.init(app)
        val parts = ArrayList<Pair<File, String>>()
        val workFiles = ArrayList<File>()
        try {
            todo.forEachIndexed { i, item ->
                status = if (item.kind == FileKind.PDF) "Reading ${item.name}…" else "Converting ${item.name} to PDF…"
                val src = ToolFiles.copyIn(app, item.uri, item.name)
                workFiles += src
                val pdf = try {
                    when (item.kind) {
                        FileKind.PDF -> src
                        else -> File(src.parentFile, src.nameWithoutExtension + "_converted.pdf").also { target ->
                            when (item.kind) {
                                FileKind.IMG -> PdfOps.imagesToPdf(listOf(src), target)
                                FileKind.TXT -> ToPdf.txt(src, target)
                                FileKind.DOC -> ToPdf.docx(src, target)
                                FileKind.XLS -> ToPdf.xlsx(src, target)
                                FileKind.PPT -> ToPdf.pptx(src, target)
                                else -> error("Unsupported file type")
                            }
                        }
                    }
                } catch (e: PdfOps.PdfProblem) {
                    throw e
                } catch (e: Exception) {
                    throw StepFailed(item.name, e)
                }
                parts += pdf to item.name
                progress = 0.8f * (i + 1) / todo.size
            }
            status = "Merging ${parts.size} files…"
            val out = ToolFiles.newOutput(app, "merged.pdf")
            PdfOps.merge(parts, out) { progress = 0.8f + 0.2f * it }
            val pages = runCatching { PdfOps.pageCount(out) }.getOrNull()
            return MergeResult(OutputFile(out, out.name, "application/pdf"), out.length(), pages)
        } finally {
            workFiles.forEach { ToolFiles.discard(it) }
        }
    }
}
