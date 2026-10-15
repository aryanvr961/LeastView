package com.leastview.app.ui.docx

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Search
import com.leastview.app.ui.FastScroller
import com.leastview.app.ui.SearchTopBar
import com.leastview.app.ui.estimatedPageLabel
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import com.leastview.app.data.FileKind
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.docx.Align
import com.leastview.app.office.docx.Block
import com.leastview.app.office.docx.DocImage
import com.leastview.app.office.docx.DocParagraph
import com.leastview.app.office.docx.DocTable
import com.leastview.app.office.docx.DocxDocument
import com.leastview.app.office.docx.DocxParser
import com.leastview.app.office.docx.DocxWriter
import com.leastview.app.office.docx.RunStyle
import com.leastview.app.ui.CenteredLoading
import com.leastview.app.ui.CenteredMessage
import com.leastview.app.ui.EditFab
import com.leastview.app.ui.ViewerTopBar
import com.leastview.app.ui.ZoomBox
import com.leastview.app.ui.adaptForPage
import com.leastview.app.ui.highlightColors
import com.leastview.app.ui.rememberDocSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private sealed interface DocState {
    data object Loading : DocState
    class Ready(val doc: DocxDocument) : DocState
    class Failed(val message: String) : DocState
}

@Composable
fun DocxScreen(file: File, uri: Uri, name: String, onBack: () -> Unit, onReload: () -> Unit) {
    val state by produceState<DocState>(DocState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                DocState.Ready(DocxParser.parse(OoxmlPackage(file)))
            } catch (e: Exception) {
                DocState.Failed("Couldn't open this document (${e.message ?: "unsupported file"}).")
            }
        }
    }
    val doc = (state as? DocState.Ready)?.doc
    DisposableEffect(doc) { onDispose { doc?.pkg?.close() } }

    when (val s = state) {
        DocState.Loading -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredLoading(Modifier.padding(it))
        }
        is DocState.Failed -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredMessage(s.message, Modifier.padding(it))
        }
        is DocState.Ready -> DocxContent(s.doc, uri, name, onBack, onReload)
    }
}

/** Layout context shared by every block: how many dp one point of the document is, and the page colours. */
private class PageCtx(
    val doc: DocxDocument,
    val editor: DocEditor?,
    /** dp per document point. */
    val scale: Float,
    val dark: Boolean,
    val density: Density,
    val pageColor: Color,
    val textColor: Color,
    val accent: Color,
    val search: DocSearch? = null,
) {
    fun sp(pt: Float): TextUnit = with(density) { (pt * scale).dp.toSp() }
    fun dp(pt: Float): Dp = (pt * scale).dp
    fun twDp(tw: Int): Dp = dp(tw / 20f)
}

private const val EDIT_SCALE = 1.25f

@Composable
private fun DocxContent(doc: DocxDocument, uri: Uri, name: String, onBack: () -> Unit, onReload: () -> Unit) {
    val editor = remember(doc) { DocEditor(doc) }
    var editing by remember(doc) { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val saver = rememberDocSaver(uri, name, FileKind.DOC.mimeType, snackbar)

    fun saveAndFinish() {
        if (!editor.dirty) { editing = false; return }
        saver.save(
            writer = { DocxWriter.write(doc, it) },
            // the saved file must read back with exactly the text on screen, or the original stays untouched
            verify = { f ->
                OoxmlPackage(f).use { pkg ->
                    val reread = DocxParser.parse(pkg).allParagraphs().map { it.text }
                    check(reread == doc.allParagraphs().map { it.text }) { "saved text differs" }
                }
            },
            onSaved = {
                editor.markSaved()
                editing = false
            },
        )
    }

    BackHandler(enabled = editing) { if (editor.dirty) confirmExit = true else editing = false }

    // search inside the document
    val search = remember(doc) { DocSearch(doc) }
    var searching by remember(doc) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    fun closeSearch() {
        searching = false
        search.run("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    // jump to the selected result; keyed on position, not the hit object, so edits don't make the page jump
    LaunchedEffect(search.current, search.query) {
        search.currentHit?.let { listState.animateScrollToItem(it.topIndex + 1) }
    }
    LaunchedEffect(editor.changeCount) {
        if (searching && search.query.isNotBlank()) search.run(search.query, keepPosition = true)
    }
    val hasText = remember(doc, editor.changeCount) { doc.allParagraphs().any { it.text.isNotBlank() } }
    val searchMessage = when {
        search.query.isBlank() -> null
        !hasText -> "This document has no readable text."
        search.hits.isEmpty() -> "No results for \"${search.query.trim()}\""
        else -> null
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (searching) {
                SearchTopBar(
                    query = search.query,
                    onQueryChange = { search.run(it) },
                    position = search.current + 1,
                    total = search.hits.size,
                    busy = false,
                    message = searchMessage,
                    onPrevious = search::previous,
                    onNext = search::next,
                    onClose = ::closeSearch,
                )
            } else if (editing) {
                ViewerTopBar(
                    title = name,
                    onBack = ::saveAndFinish,
                    navIcon = { Icon(Icons.Filled.Check, "Done") },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search in document") }
                        IconButton(onClick = editor::undo, enabled = editor.canUndo) { Icon(Icons.Filled.Undo, "Undo") }
                        IconButton(onClick = editor::redo, enabled = editor.canRedo) { Icon(Icons.Filled.Redo, "Redo") }
                    },
                )
            } else ViewerTopBar(name, onBack, actions = {
                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search in document") }
            })
        },
        bottomBar = { if (editing) EditToolbar(editor) },
        floatingActionButton = { if (!editing) EditFab { editing = true } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val dark = isSystemInDarkTheme()
            val density = LocalDensity.current
            val pageColor = if (dark) Color.Black else Color.White
            val textColor = if (dark) Color(0xFFE8EAED) else Color(0xFF202124)
            val accent = MaterialTheme.colorScheme.primary
            if (editing) {
                val ctx = remember(doc, dark, density, editor) { PageCtx(doc, editor, EDIT_SCALE, dark, density, pageColor, textColor, accent, search) }
                PageList(ctx, listState, marginStart = 16.dp, marginEnd = 16.dp, marginTop = 16.dp, marginBottom = 120.dp, gutter = 0.dp, extraVertical = 0.dp)
            } else {
                ZoomBox { zoom ->
                    if (zoom.viewWidthPx > 0) {
                        val gutter = 10.dp
                        val viewDp = with(density) { zoom.viewWidthPx.toDp() }
                        val pageWidthPt = doc.page.widthTw / 20f
                        val scale = (viewDp - gutter * 2).value / pageWidthPt
                        val ctx = remember(doc, dark, density, scale) { PageCtx(doc, null, scale, dark, density, pageColor, textColor, accent, search) }
                        PageList(
                            ctx, listState,
                            marginStart = ctx.twDp(doc.page.marginLeftTw), marginEnd = ctx.twDp(doc.page.marginRightTw),
                            marginTop = ctx.twDp(doc.page.marginTopTw), marginBottom = ctx.twDp(doc.page.marginBottomTw),
                            gutter = gutter, extraVertical = zoom.verticalPadding,
                        )
                    }
                }
            }
            // Word files have no fixed pages, so page numbers are estimated from the height of one page
            val screenWidthDp = LocalConfiguration.current.screenWidthDp
            val pagePx = if (editing) doc.page.heightTw / 20f * EDIT_SCALE * density.density
            else (screenWidthDp - 20f) / (doc.page.widthTw / 20f) * (doc.page.heightTw / 20f) * density.density
            FastScroller(listState, label = estimatedPageLabel(pagePx))
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Save changes?") },
            text = { Text("You have unsaved edits.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; saveAndFinish() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
                    TextButton(onClick = { confirmExit = false; onReload() }) { Text("Discard") }
                }
            },
        )
    }
}

@Composable
private fun PageList(
    ctx: PageCtx,
    listState: LazyListState,
    marginStart: Dp, marginEnd: Dp, marginTop: Dp, marginBottom: Dp,
    gutter: Dp, extraVertical: Dp,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = gutter, vertical = gutter + extraVertical),
    ) {
        item { Spacer(Modifier.fillMaxWidth().height(marginTop).background(ctx.pageColor)) }
        items(ctx.doc.blocks, key = { it.id }) { block ->
            Box(Modifier.fillMaxWidth().background(ctx.pageColor).padding(start = marginStart, end = marginEnd)) {
                BlockView(block, ctx)
            }
        }
        item { Spacer(Modifier.fillMaxWidth().height(marginBottom).background(ctx.pageColor)) }
    }
}

@Composable
private fun BlockView(block: Block, ctx: PageCtx) {
    when (block) {
        is DocParagraph -> ParagraphView(block, ctx)
        is DocTable -> TableView(block, ctx)
    }
}

// ---------------------------------------------------------------- paragraphs

private fun fontFamilyFor(name: String?): FontFamily? = when {
    name == null -> null
    name.contains("times", true) || name.contains("georgia", true) || name.contains("cambria", true) ||
        name.contains("garamond", true) || name.contains("serif", true) && !name.contains("sans", true) -> FontFamily.Serif
    name.contains("courier", true) || name.contains("consolas", true) || name.contains("mono", true) -> FontFamily.Monospace
    else -> null
}

private fun spanFor(s: RunStyle, ctx: PageCtx): SpanStyle {
    val deco = buildList {
        if (s.underline) add(TextDecoration.Underline)
        if (s.strike) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontSize = ctx.sp(s.sizeHalfPt / 2f),
        fontWeight = if (s.bold) FontWeight.Bold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        color = adaptForPage(s.color, ctx.dark) ?: Color.Unspecified,
        background = s.highlight?.let { highlightColors[it] } ?: Color.Unspecified,
        textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
        fontFamily = fontFamilyFor(s.font),
    )
}

/** Text with run formatting. Same length as the paragraph text so it can drive a text field's visual transformation. */
private fun annotate(
    text: String, styles: List<RunStyle>, ctx: PageCtx, withLinks: Boolean,
    highlights: List<Highlight> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val s = styles.getOrNull(i)
        if (s == null) { append(text.substring(i).replace('\t', ' ')); break }
        var j = i + 1
        while (j < text.length && styles.getOrNull(j) == s) j++
        val chunk = text.substring(i, j).replace('\t', ' ') // one char, keeps offsets identical
        val link = s.link
        if (withLinks && link != null && (link.startsWith("http") || link.startsWith("mailto:"))) {
            withLink(LinkAnnotation.Url(link)) { withStyle(spanFor(s, ctx)) { append(chunk) } }
        } else withStyle(spanFor(s, ctx)) { append(chunk) }
        i = j
    }
    for (h in highlights) {
        val a = h.start.coerceIn(0, length)
        val b = h.end.coerceIn(a, length)
        addStyle(SpanStyle(background = if (h.isCurrent) Color(0xFFFF9800) else Color(0xFFFFEB3B), color = Color.Black), a, b)
    }
}

private fun textAlign(a: Align) = when (a) {
    Align.LEFT -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.RIGHT -> TextAlign.End
    Align.JUSTIFY -> TextAlign.Justify
}

@Composable
private fun ParagraphView(p: DocParagraph, ctx: PageCtx) {
    p.version // read so this recomposes whenever the paragraph changes
    val props = p.props
    val highlights = ctx.search?.highlightsFor(p).orEmpty()
    val leftPt = props.indentLeftTw / 20f
    val firstPt = props.firstLineTw / 20f
    val hangingPt = if (firstPt < 0) -firstPt else 0f
    val markerWidthPt = if (props.marker != null) (if (hangingPt > 0) hangingPt else 18f) else 0f
    val textStartPt = if (props.marker != null) max(leftPt, markerWidthPt) else leftPt
    val markerStartPt = max(textStartPt - markerWidthPt, 0f)
    val maxSizePt = max(p.styles.maxOfOrNull { it.sizeHalfPt } ?: p.base.sizeHalfPt, p.base.sizeHalfPt) / 2f

    val baseStyle = TextStyle(
        color = ctx.textColor,
        fontSize = ctx.sp(p.base.sizeHalfPt / 2f),
        fontFamily = fontFamilyFor(p.base.font),
        textAlign = textAlign(props.align),
        textIndent = if (props.marker == null && firstPt > 0) TextIndent(firstLine = ctx.sp(firstPt)) else null,
        lineHeight = if (props.lineMult > 1.05f) ctx.sp(maxSizePt * 1.2f * props.lineMult) else TextUnit.Unspecified,
    )
    val lineColor = ctx.textColor.copy(alpha = 0.6f)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = ctx.twDp(props.spaceBeforeTw), bottom = ctx.twDp(props.spaceAfterTw))
            .drawBehind {
                val stroke = 1.dp.toPx()
                if (props.borderBottom) drawLine(lineColor, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                if (props.borderTop) drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), stroke)
            },
    ) {
        Row(Modifier.fillMaxWidth()) {
            if (props.marker != null) {
                Spacer(Modifier.width(ctx.dp(markerStartPt)))
                Text(props.marker, Modifier.width(ctx.dp(markerWidthPt)), style = baseStyle.copy(textIndent = null, textAlign = TextAlign.Start))
            } else Spacer(Modifier.width(ctx.dp(textStartPt)))

            Box(Modifier.weight(1f)) {
                if (ctx.editor != null && p.editable) {
                    ParaEditor(p, ctx.editor, baseStyle, ctx, highlights)
                } else if (p.text.isEmpty()) {
                    Text(" ", style = baseStyle)
                } else {
                    Text(annotate(p.text, p.styles, ctx, withLinks = true, highlights = highlights), style = baseStyle)
                }
            }
        }
        p.images.forEach { DocImageView(ctx, it) }
    }
}

@Composable
private fun ParaEditor(p: DocParagraph, editor: DocEditor, baseStyle: TextStyle, ctx: PageCtx, highlights: List<Highlight>) {
    p.version
    var tfv by remember(p) { mutableStateOf(TextFieldValue(p.text, TextRange(p.text.length))) }
    // The model changed underneath the field (undo/redo, merge): follow it.
    val shown = if (tfv.text == p.text) tfv else TextFieldValue(p.text, TextRange(min(tfv.selection.end, p.text.length)))
    val requester = remember { FocusRequester() }

    val request = editor.focusRequest
    LaunchedEffect(request) {
        if (request != null && request.paragraph === p) {
            requester.requestFocus()
            tfv = TextFieldValue(p.text, TextRange(request.caret.coerceIn(0, p.text.length)))
            editor.onFocus(p, tfv.selection)
            editor.focusRequest = null
        }
    }

    val transformation = remember(p.version, ctx.scale, ctx.dark, highlights) {
        VisualTransformation { text ->
            TransformedText(annotate(text.text, p.styles, ctx, withLinks = false, highlights = highlights), OffsetMapping.Identity)
        }
    }

    BasicTextField(
        value = shown,
        onValueChange = { new -> tfv = editor.onEdit(p, new, shown) },
        textStyle = baseStyle,
        cursorBrush = SolidColor(ctx.accent),
        visualTransformation = transformation,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) editor.onFocus(p, shown.selection) }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace &&
                    shown.selection.collapsed && shown.selection.start == 0
                ) editor.mergeBack(p) else false
            },
    )
}

// ---------------------------------------------------------------- tables & images

@Composable
private fun TableView(t: DocTable, ctx: PageCtx) {
    val border = ctx.textColor.copy(alpha = 0.45f)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        t.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { cell ->
                    val shade = cell.shade?.let { adaptForPage(it, ctx.dark) ?: Color.Unspecified }
                    Column(
                        Modifier
                            .weight(max(cell.widthTw, 1).toFloat())
                            .fillMaxHeight()
                            .then(if (shade != null && shade != Color.Unspecified) Modifier.background(shade.copy(alpha = if (ctx.dark) 0.35f else 1f)) else Modifier)
                            .then(if (t.bordered) Modifier.border(0.5.dp, border) else Modifier)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        cell.blocks.forEach { BlockView(it, ctx) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocImageView(ctx: PageCtx, img: DocImage) {
    val bitmap by produceState<ImageBitmap?>(null, img.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.doc.pkg.bytes(img.path) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap ?: return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wanted = if (img.widthEmu > 0) ctx.dp(img.widthEmu / 12700f) else maxWidth
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.widthIn(max = maxWidth).width(wanted),
        )
    }
}

// ---------------------------------------------------------------- toolbar

internal val textPalette = listOf(
    null to Color(0xFF9AA0A6), "D93025" to Color(0xFFD93025), "E37400" to Color(0xFFE37400), "F9AB00" to Color(0xFFF9AB00),
    "188038" to Color(0xFF188038), "1A73E8" to Color(0xFF1A73E8), "9334E6" to Color(0xFF9334E6), "FFFFFF" to Color.White,
)
internal val highlightPalette = listOf(null to Color(0xFF9AA0A6)) + listOf("yellow", "green", "cyan", "magenta", "lightGray").map { it to highlightColors.getValue(it) }

@Composable
private fun EditToolbar(editor: DocEditor) {
    var picker by remember { mutableStateOf<Int?>(null) } // 1 = text colour, 2 = highlight
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Filled.FormatBold, "Bold", editor.isActive { it.bold }) { editor.toggleBold() }
            ToolButton(Icons.Filled.FormatItalic, "Italic", editor.isActive { it.italic }) { editor.toggleItalic() }
            ToolButton(Icons.Filled.FormatUnderlined, "Underline", editor.isActive { it.underline }) { editor.toggleUnderline() }
            ToolButton(Icons.Filled.FormatStrikethrough, "Strikethrough", editor.isActive { it.strike }) { editor.toggleStrike() }
            ToolButton(Icons.Filled.TextDecrease, "Smaller text", false) { editor.changeSize(-2) }
            ToolButton(Icons.Filled.TextIncrease, "Larger text", false) { editor.changeSize(2) }
            ToolButton(Icons.Filled.FormatColorText, "Text colour", false) { picker = 1 }
            ToolButton(Icons.Filled.FormatColorFill, "Highlight", false) { picker = 2 }
            val align = editor.alignment()
            ToolButton(Icons.Filled.FormatAlignLeft, "Align left", align == Align.LEFT) { editor.setAlign(Align.LEFT) }
            ToolButton(Icons.Filled.FormatAlignCenter, "Align centre", align == Align.CENTER) { editor.setAlign(Align.CENTER) }
            ToolButton(Icons.Filled.FormatAlignRight, "Align right", align == Align.RIGHT) { editor.setAlign(Align.RIGHT) }
            ToolButton(Icons.Filled.FormatAlignJustify, "Justify", align == Align.JUSTIFY) { editor.setAlign(Align.JUSTIFY) }
        }
    }
    when (picker) {
        1 -> ColorDialog("Text colour", textPalette, onPick = { editor.setColor(it); picker = null }, onDismiss = { picker = null })
        2 -> ColorDialog("Highlight", highlightPalette, onPick = { editor.setHighlight(it); picker = null }, onDismiss = { picker = null })
    }
}

@Composable
internal fun ToolButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) { Icon(icon, description) }
}

@Composable
internal fun ColorDialog(title: String, colors: List<Pair<String?, Color>>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.forEach { (value, color) ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onPick(value) },
                        contentAlignment = Alignment.Center,
                    ) { if (value == null) Text("∅", color = Color.Black) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
