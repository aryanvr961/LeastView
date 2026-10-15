package com.leastview.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.leastview.app.data.FileKind
import com.leastview.app.pdf.PdfLineSource
import com.leastview.app.pdf.PdfSearchState
import com.leastview.app.pdf.PdfTextEdit
import com.leastview.app.pdf.PdfViewer
import com.leastview.app.tools.LineFamily
import com.leastview.app.tools.PdfOps
import com.leastview.app.ui.docx.ColorDialog
import com.leastview.app.ui.docx.ToolButton
import com.leastview.app.ui.docx.highlightPalette
import com.leastview.app.ui.docx.textPalette
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * PDF viewer with in-document search and a text-only Edit mode: tap a line of text, retype it where it
 * stands and format it (bold, italic, underline, strike, size, colour, highlight). Saving writes the changes
 * into the PDF itself and then shows the saved file.
 */
@Composable
fun PdfScreen(file: File, uri: Uri, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // after a save the viewer switches to the newly written file, so what is on screen is what is in the PDF
    var current by remember(file) { mutableStateOf(file) }
    var lastOutput by remember(file) { mutableStateOf<File?>(null) }
    val edit = remember(file) { PdfTextEdit { name -> highlightColors[name]?.toArgb() } }
    var editing by remember(file) { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    val search = remember(current) { PdfSearchState(context, current, scope) }
    DisposableEffect(search) { onDispose { search.close() } }
    val lineSource = remember(current) { PdfLineSource(context, current) }
    DisposableEffect(lineSource) { onDispose { lineSource.close() } }

    var searching by remember { mutableStateOf(false) }
    fun closeSearch() {
        searching = false
        search.updateQuery("")
    }

    fun adopt(saved: File) {
        current = saved
        edit.clear()
        editing = false
    }
    val saver = rememberDocSaver(uri, name, FileKind.PDF.mimeType, snackbar, onCopySaved = { lastOutput?.let(::adopt) })

    fun saveAndFinish() {
        if (!edit.dirty) {
            edit.clear()
            editing = false
            return
        }
        val out = File(file.parentFile, "edited-${System.nanoTime()}.pdf")
        lastOutput = out
        val specs = edit.specs()
        val source = current
        saver.save(
            writer = { stream ->
                PdfOps.init(context)
                PdfOps.applyLineEdits(
                    source, out, specs,
                    standardFont = { family, bold, italic ->
                        when (family) {
                            LineFamily.SERIF -> if (bold && italic) PDType1Font.TIMES_BOLD_ITALIC else if (bold) PDType1Font.TIMES_BOLD else if (italic) PDType1Font.TIMES_ITALIC else PDType1Font.TIMES_ROMAN
                            LineFamily.MONO -> if (bold && italic) PDType1Font.COURIER_BOLD_OBLIQUE else if (bold) PDType1Font.COURIER_BOLD else if (italic) PDType1Font.COURIER_OBLIQUE else PDType1Font.COURIER
                            LineFamily.SANS -> if (bold && italic) PDType1Font.HELVETICA_BOLD_OBLIQUE else if (bold) PDType1Font.HELVETICA_BOLD else if (italic) PDType1Font.HELVETICA_OBLIQUE else PDType1Font.HELVETICA
                        }
                    },
                    embeddedFont = { doc ->
                        context.assets.open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf").use { PDType0Font.load(doc, it) }
                    },
                )
                out.inputStream().use { it.copyTo(stream) }
            },
            // the saved file has to open and still have every page before the original is replaced
            verify = { f -> check(PdfOps.pageCount(f) == PdfOps.pageCount(source)) { "page count changed" } },
            onSaved = { adopt(out) },
        )
    }

    BackHandler(enabled = editing) { if (edit.dirty) confirmExit = true else { edit.clear(); editing = false } }
    BackHandler(enabled = searching) { closeSearch() }

    val message = when {
        search.problem != null -> search.problem
        search.scanning && search.matches.isEmpty() -> "Searching… page ${search.scanned} of ${search.total}"
        search.query.isNotBlank() && !search.scanning && search.matches.isEmpty() -> "No results for \"${search.query.trim()}\""
        else -> null
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val searchAction: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search in document") }
            }
            when {
                searching -> SearchTopBar(
                    query = search.query,
                    onQueryChange = search::updateQuery,
                    position = search.current + 1,
                    total = search.matches.size,
                    busy = search.scanning,
                    message = message,
                    onPrevious = search::previous,
                    onNext = search::next,
                    onClose = ::closeSearch,
                )
                editing -> ViewerTopBar(
                    name,
                    onBack = ::saveAndFinish,
                    navIcon = { Icon(Icons.Filled.Check, "Done") },
                    actions = {
                        searchAction()
                        IconButton(onClick = edit::undo, enabled = edit.canUndo) { Icon(Icons.Filled.Undo, "Undo") }
                        IconButton(onClick = edit::redo, enabled = edit.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
                    },
                )
                else -> ViewerTopBar(name, onBack, actions = searchAction)
            }
        },
        bottomBar = { if (editing) PdfEditToolbar(edit) },
        floatingActionButton = { if (!editing && !searching) EditFab { editing = true } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PdfViewer(
            current, Modifier.padding(padding), search,
            textEdit = if (editing) edit else null,
            lineSource = if (editing) lineSource else null,
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Save changes?") },
            text = { Text("Your text edits aren't in the PDF yet.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; saveAndFinish() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmExit = false; edit.clear(); editing = false }) { Text("Discard") }
                }
            },
        )
    }
}

/** The same row of formatting buttons the Word editor has. They apply to the line being edited. */
@Composable
private fun PdfEditToolbar(edit: PdfTextEdit) {
    var picker by remember { mutableStateOf<Int?>(null) } // 1 = text colour, 2 = highlight
    val line = edit.activeEdit
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (line == null) {
                Text(
                    "Tap a line of text to edit it.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Filled.FormatBold, "Bold", line?.bold == true) { edit.toggleBold() }
                ToolButton(Icons.Filled.FormatItalic, "Italic", line?.italic == true) { edit.toggleItalic() }
                ToolButton(Icons.Filled.FormatUnderlined, "Underline", line?.underline == true) { edit.toggleUnderline() }
                ToolButton(Icons.Filled.FormatStrikethrough, "Strikethrough", line?.strike == true) { edit.toggleStrike() }
                ToolButton(Icons.Filled.TextDecrease, "Smaller text", false) { edit.changeSize(-1f) }
                ToolButton(Icons.Filled.TextIncrease, "Larger text", false) { edit.changeSize(1f) }
                ToolButton(Icons.Filled.FormatColorText, "Text colour", false) { picker = 1 }
                ToolButton(Icons.Filled.FormatColorFill, "Highlight", false) { picker = 2 }
            }
        }
    }
    when (picker) {
        1 -> ColorDialog("Text colour", textPalette, onPick = { hex -> edit.setColor(hex?.let { (0xFF000000L or it.toLong(16)).toInt() }); picker = null }, onDismiss = { picker = null })
        2 -> ColorDialog("Highlight", highlightPalette, onPick = { edit.setHighlight(it); picker = null }, onDismiss = { picker = null })
    }
}
