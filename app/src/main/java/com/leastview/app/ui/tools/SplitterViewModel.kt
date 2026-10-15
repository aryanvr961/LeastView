package com.leastview.app.ui.tools

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leastview.app.data.FileKind
import com.leastview.app.tools.OutputFile
import com.leastview.app.tools.PageRanges
import com.leastview.app.tools.PdfOps
import com.leastview.app.tools.ToolFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SplitOutput(val file: OutputFile, val pages: Int, val size: Long)

class SplitterViewModel(app: Application) : AndroidViewModel(app) {
    enum class Mode(val label: String, val help: String) {
        RANGES("Split by ranges", "Each range becomes its own PDF. Example: 1-3, 5, 8-10 makes three files."),
        EXTRACT("Extract pages", "The chosen pages are collected, in the order you type them, into a single new PDF."),
        EVERY_PAGE("Every page", "Every page becomes its own PDF."),
        EVERY_N("Every N pages", "The document is cut into parts of N pages each."),
    }

    var item by mutableStateOf<ToolItem?>(null)
        private set
    var pageCount by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var mode by mutableStateOf(Mode.RANGES)
    var rangeText by mutableStateOf("")
    var everyN by mutableStateOf("2")

    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var outputs by mutableStateOf<List<SplitOutput>>(emptyList())
        private set

    private var source: File? = null

    /** Live validation of what is typed, so mistakes show before the user presses Split. */
    val inputError: String?
        get() {
            if (item == null) return null
            return when (mode) {
                Mode.RANGES, Mode.EXTRACT ->
                    if (rangeText.isBlank()) null
                    else try { PageRanges.parse(rangeText, pageCount); null } catch (e: PageRanges.Invalid) { e.message }
                Mode.EVERY_N -> {
                    val n = everyN.toIntOrNull()
                    when {
                        n == null || n < 1 -> "Enter a number of pages, 1 or more."
                        n >= pageCount -> "N must be smaller than the page count ($pageCount) to make more than one file."
                        else -> null
                    }
                }
                Mode.EVERY_PAGE -> if (pageCount < 2) "This PDF has only one page." else null
            }
        }

    val canRun: Boolean
        get() = item != null && !busy && !loading && inputError == null &&
            (mode == Mode.EVERY_PAGE || mode == Mode.EVERY_N || rangeText.isNotBlank())

    fun pick(uri: Uri) {
        viewModelScope.launch {
            loading = true
            error = null
            outputs = emptyList()
            item = null
            try {
                val (picked, file, pages) = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    PdfOps.init(app)
                    val it = ToolItem.from(app, uri)
                    if (it.kind != FileKind.PDF) throw PdfOps.PdfProblem("${it.name} isn't a PDF. The splitter works on PDF files.")
                    val f = ToolFiles.copyIn(app, uri, it.name)
                    Triple(it, f, PdfOps.pageCount(f, it.name))
                }
                source = file
                item = picked
                pageCount = pages
                rangeText = ""
            } catch (e: PdfOps.PdfProblem) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't open that file."
            } finally {
                loading = false
            }
        }
    }

    fun run() {
        val picked = item ?: return
        val src = source ?: return
        if (!canRun) return
        viewModelScope.launch {
            busy = true
            error = null
            outputs = emptyList()
            progress = 0f
            try {
                val app = getApplication<Application>()
                val base = picked.name.substringBeforeLast('.')
                // (file name suffix, 0-based pages) per output
                val plan: List<Pair<String, List<Int>>> = when (mode) {
                    Mode.RANGES -> PageRanges.parse(rangeText, pageCount).map { "pages_${PageRanges.label(it)}" to it.toList() }
                    Mode.EXTRACT -> PageRanges.parse(rangeText, pageCount).let { r ->
                        listOf("extract_${r.joinToString("_") { PageRanges.label(it) }}".take(60) to r.flatMap { it.toList() })
                    }
                    Mode.EVERY_PAGE -> (0 until pageCount).map { "page_${it + 1}" to listOf(it) }
                    Mode.EVERY_N -> {
                        val n = everyN.toInt()
                        (0 until pageCount step n).mapIndexed { i, start -> "part_${i + 1}" to (start until minOf(start + n, pageCount)).toList() }
                    }
                }
                val jobs = plan.map { (suffix, pages) -> pages to ToolFiles.newOutput(app, "${base}_$suffix.pdf") }
                withContext(Dispatchers.IO) {
                    PdfOps.extractMany(src, jobs, picked.name) { progress = it }
                }
                outputs = jobs.zip(plan).map { (job, p) ->
                    SplitOutput(OutputFile(job.second, job.second.name, "application/pdf"), p.second.size, job.second.length())
                }
            } catch (e: PageRanges.Invalid) {
                error = e.message
            } catch (e: PdfOps.PdfProblem) {
                error = e.message
            } catch (e: Exception) {
                error = "Splitting failed: ${e.message ?: "unknown error"}"
            } catch (e: OutOfMemoryError) {
                error = "This PDF is too large to split on this device."
            } finally {
                busy = false
            }
        }
    }
}
