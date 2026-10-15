package com.leastview.app.ui.pptx

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import com.leastview.app.ui.FastScroller
import com.leastview.app.ui.SearchTopBar
import com.leastview.app.ui.docx.Highlight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.data.FileKind
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.pptx.PPara
import com.leastview.app.office.pptx.PicShape
import com.leastview.app.office.pptx.PlaceholderShape
import com.leastview.app.office.pptx.PptxDeck
import com.leastview.app.office.pptx.PptxParser
import com.leastview.app.office.pptx.PptxWriter
import com.leastview.app.office.pptx.Shape
import com.leastview.app.office.pptx.Slide
import com.leastview.app.office.pptx.TableShape
import com.leastview.app.office.pptx.TextShape
import com.leastview.app.ui.CenteredLoading
import com.leastview.app.ui.CenteredMessage
import com.leastview.app.ui.EditFab
import com.leastview.app.ui.ViewerTopBar
import com.leastview.app.ui.ZoomBox
import com.leastview.app.ui.hexColor
import com.leastview.app.ui.rememberDocSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

private sealed interface DeckState {
    data object Loading : DeckState
    class Ready(val deck: PptxDeck) : DeckState
    class Failed(val message: String) : DeckState
}

@Composable
fun PptxScreen(file: File, uri: Uri, name: String, onBack: () -> Unit, onReload: () -> Unit) {
    val state by produceState<DeckState>(DeckState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                DeckState.Ready(PptxParser.parse(OoxmlPackage(file)))
            } catch (e: Exception) {
                DeckState.Failed("Couldn't open this presentation (${e.message ?: "unsupported file"}).")
            }
        }
    }
    val deck = (state as? DeckState.Ready)?.deck
    DisposableEffect(deck) { onDispose { deck?.pkg?.close() } }

    when (val s = state) {
        DeckState.Loading -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredLoading(Modifier.padding(it))
        }
        is DeckState.Failed -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredMessage(s.message, Modifier.padding(it))
        }
        is DeckState.Ready -> PptxContent(s.deck, uri, name, onBack, onReload)
    }
}

@Composable
private fun PptxContent(deck: PptxDeck, uri: Uri, name: String, onBack: () -> Unit, onReload: () -> Unit) {
    var editing by remember(deck) { mutableStateOf(false) }
    var unsaved by remember(deck) { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Pair<Slide, TextShape>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val saver = rememberDocSaver(uri, name, FileKind.PPT.mimeType, snackbar)
    val images = remember(deck) { androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap?>() }

    fun saveAndFinish() {
        if (!unsaved) { editing = false; return }
        saver.save(
            writer = { PptxWriter.write(deck, it) },
            verify = { f ->
                OoxmlPackage(f).use { pkg ->
                    val reread = PptxParser.parse(pkg)
                    check(reread.slides.size == deck.slides.size) { "slide count differs" }
                    reread.slides.zip(deck.slides).forEach { (a, b) ->
                        check(a.shapes.filterIsInstance<TextShape>().map { it.plainText } == b.shapes.filterIsInstance<TextShape>().map { it.plainText }) {
                            "saved text differs"
                        }
                    }
                }
            },
            onSaved = { unsaved = false; editing = false },
        )
    }
    BackHandler(enabled = editing) { if (unsaved) confirmExit = true else editing = false }

    // search inside the presentation
    val search = remember(deck) { PptxSearch(deck) }
    var searching by remember(deck) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    fun closeSearch() {
        searching = false
        search.run("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    val anyText = remember(deck) {
        deck.slides.any { s -> s.shapes.any { (it is TextShape && it.hasText) || it is TableShape } }
    }
    val searchMessage = when {
        search.query.isBlank() -> null
        !anyText -> "This presentation has no readable text."
        search.hits.isEmpty() -> "No results for \"${search.query.trim()}\""
        else -> null
    }

    Scaffold(
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
            } else {
                val searchAction: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search in presentation") }
                }
                if (editing) ViewerTopBar(name, onBack = ::saveAndFinish, navIcon = { Icon(Icons.Filled.Check, "Done") }, actions = searchAction)
                else ViewerTopBar(name, onBack, actions = searchAction)
            }
        },
        floatingActionButton = { if (!editing) EditFab { editing = true } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ZoomBox { zoom ->
                if (zoom.viewWidthPx > 0) {
                    val density = LocalDensity.current
                    val gutter = 12.dp
                    val widthDp = with(density) { zoom.viewWidthPx.toDp() } - gutter * 2
                    // scroll to the slide (and height on it) of the selected result
                    LaunchedEffect(search.current, search.query) {
                        val hit = search.currentHit ?: return@LaunchedEffect
                        val slidePx = with(density) { widthDp.toPx() } * deck.heightEmu / deck.widthEmu
                        val y = (hit.shape.y.toFloat() / deck.heightEmu * slidePx - slidePx * 0.1f).toInt().coerceAtLeast(0)
                        listState.scrollToItem(hit.slide, y)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = gutter, vertical = gutter + zoom.verticalPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(deck.slides, key = { i, _ -> i }) { i, slide ->
                            Column {
                                SlideView(deck, slide, widthDp, density, images, editing, search) { shape -> editTarget = slide to shape }
                                Text(
                                    "${i + 1} / ${deck.slides.size}",
                                    Modifier.padding(top = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            FastScroller(listState)
        }
    }

    editTarget?.let { (slide, shape) ->
        var text by remember(shape) { mutableStateOf(shape.plainText) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit text") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    supportingText = { Text("Each line is one paragraph. Formatting of the first run is kept.") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text != shape.plainText) {
                        PptxWriter.setText(slide, shape, text)
                        unsaved = true
                        if (searching) search.run(search.query, keepPosition = true)
                    }
                    editTarget = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } },
        )
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

/** Size of one slide in dp plus conversions from slide EMU / points. */
private class SlideScale(val widthDp: Dp, val deck: PptxDeck, val density: Density) {
    private val dpPerEmu: Float = widthDp.value / deck.widthEmu
    fun dp(emu: Long): Dp = (emu * dpPerEmu).dp
    fun sp(pt: Float): TextUnit = with(density) { (pt * 12700f * dpPerEmu).dp.toSp() }
}

@Composable
private fun SlideView(
    deck: PptxDeck, slide: Slide, widthDp: Dp, density: Density,
    images: MutableMap<String, ImageBitmap?>,
    editing: Boolean, search: PptxSearch, onEditText: (TextShape) -> Unit,
) {
    val s = remember(widthDp, deck, density) { SlideScale(widthDp, deck, density) }
    val bg = slide.background?.let { hexColor(it) } ?: Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(deck.widthEmu.toFloat() / deck.heightEmu)
            .background(bg)
            .clipToBounds(),
    ) {
        slide.shapes.forEach { shape ->
            Box(
                Modifier
                    .offset(s.dp(shape.x), s.dp(shape.y))
                    .size(s.dp(shape.w), s.dp(shape.h))
                    .graphicsLayer { rotationZ = shape.rotDeg },
            ) {
                when (shape) {
                    is TextShape -> TextShapeView(shape, s, editing, search, onEditText)
                    is PicShape -> PicShapeView(shape, deck, images)
                    is TableShape -> TableShapeView(shape, s, search)
                    is PlaceholderShape -> Box(
                        Modifier.fillMaxSize().background(Color(0x22888888)).border(1.dp, Color(0x55888888)),
                        contentAlignment = Alignment.Center,
                    ) { Text(shape.label, color = Color(0xFF666666), fontSize = s.sp(14f)) }
                }
            }
        }
    }
}

private fun fontFor(name: String?): FontFamily? = when {
    name == null -> null
    name.contains("times", true) || name.contains("georgia", true) || name.contains("cambria", true) -> FontFamily.Serif
    name.contains("courier", true) || name.contains("consolas", true) -> FontFamily.Monospace
    else -> null
}

@Composable
private fun TextShapeView(shape: TextShape, s: SlideScale, editing: Boolean, search: PptxSearch, onEditText: (TextShape) -> Unit) {
    val fill = shape.fill?.let { hexColor(it) }
    val line = shape.line?.let { hexColor(it) }
    val strokeDp = s.dp(shape.lineWidthEmu).coerceAtLeast(0.5.dp)
    val tappable = editing && !shape.isLine

    Canvas(Modifier.fillMaxSize()) {
        scale(if (shape.flipH) -1f else 1f, if (shape.flipV) -1f else 1f) {
            if (shape.isLine) {
                if (line != null) drawLine(line, Offset.Zero, Offset(size.width, size.height), strokeDp.toPx())
            } else {
                val corner = if (shape.geom == "roundRect") androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.1667f) else androidx.compose.ui.geometry.CornerRadius.Zero
                when (shape.geom) {
                    "ellipse" -> {
                        if (fill != null) drawOval(fill)
                        if (line != null) drawOval(line, style = Stroke(strokeDp.toPx()))
                    }
                    else -> {
                        if (fill != null) drawRoundRect(fill, cornerRadius = corner)
                        if (line != null) drawRoundRect(line, cornerRadius = corner, style = Stroke(strokeDp.toPx()))
                    }
                }
            }
        }
    }

    if (shape.hasText || tappable) {
        val inset = shape.insetsEmu
        Column(
            Modifier
                .fillMaxSize()
                .then(if (tappable) Modifier.border(1.dp, Color(0x66448AFF)).clickable { onEditText(shape) } else Modifier)
                .padding(s.dp(inset[0]), s.dp(inset[1]), s.dp(inset[2]), s.dp(inset[3])),
            verticalArrangement = when (shape.anchor) {
                "ctr" -> Arrangement.Center
                "b" -> Arrangement.Bottom
                else -> Arrangement.Top
            },
        ) {
            shape.paragraphs.forEachIndexed { i, p -> ParagraphText(p, shape.fontScale, s, search.highlights(shape, i)) }
        }
    }
}

@Composable
private fun ParagraphText(p: PPara, fontScale: Float, s: SlideScale, highlights: List<Highlight>) {
    val first = p.runs.firstOrNull()
    val text = buildAnnotatedString {
        if (p.bullet != null && first != null) {
            withStyle(SpanStyle(fontSize = s.sp(first.sizePt * fontScale), color = first.color?.let { hexColor(it) } ?: Color.Unspecified)) { append(p.bullet + " ") }
        }
        for (r in p.runs) {
            withStyle(
                SpanStyle(
                    fontSize = s.sp(r.sizePt * fontScale),
                    fontWeight = if (r.bold) FontWeight.Bold else null,
                    fontStyle = if (r.italic) FontStyle.Italic else null,
                    textDecoration = if (r.underline) TextDecoration.Underline else null,
                    color = r.color?.let { hexColor(it) } ?: Color.Unspecified,
                    fontFamily = fontFor(r.font),
                ),
            ) { append(r.text) }
        }
        // result positions are relative to the paragraph text, which starts after the bullet prefix
        val offset = if (p.bullet != null && first != null) p.bullet.length + 1 else 0
        for (h in highlights) {
            val a = (offset + h.start).coerceIn(0, length)
            val b = (offset + h.end).coerceIn(a, length)
            addStyle(SpanStyle(background = if (h.isCurrent) Color(0xFFFF9800) else Color(0xFFFFEB3B), color = Color.Black), a, b)
        }
    }
    val align = when (p.align) {
        "ctr" -> TextAlign.Center
        "r" -> TextAlign.End
        "just" -> TextAlign.Justify
        else -> TextAlign.Start
    }
    val marL = s.dp(p.marLEmu)
    val hanging = if (p.indentEmu < 0) s.dp(-p.indentEmu) else 0.dp
    val sizePt = (first?.sizePt ?: 18f) * fontScale
    Text(
        text = if (text.isEmpty()) buildAnnotatedString { append(" ") } else text,
        style = TextStyle(
            textAlign = align,
            fontSize = s.sp(sizePt),
            lineHeight = s.sp(sizePt * 1.2f * p.lineMult),
            textIndent = if (p.bullet != null && p.indentEmu < 0) TextIndent(firstLine = with(s.density) { (-hanging).toSp() }) else null,
        ),
        modifier = Modifier.fillMaxWidth().padding(start = marL, top = s.dp((p.spaceBeforePt * 12700).toLong())),
    )
}

@Composable
private fun PicShapeView(shape: PicShape, deck: PptxDeck, images: MutableMap<String, ImageBitmap?>) {
    val bmp by produceState<ImageBitmap?>(images[shape.path], shape.path) {
        if (images.containsKey(shape.path)) { value = images[shape.path]; return@produceState }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = deck.pkg.bytes(shape.path) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        images[shape.path] = loaded
        value = loaded
    }
    val image = bmp ?: return
    Canvas(Modifier.fillMaxSize()) {
        val l = shape.crop[0]; val t = shape.crop[1]; val r = shape.crop[2]; val b = shape.crop[3]
        val srcW = (image.width * (1f - l - r)).toInt().coerceAtLeast(1)
        val srcH = (image.height * (1f - t - b)).toInt().coerceAtLeast(1)
        scale(if (shape.flipH) -1f else 1f, if (shape.flipV) -1f else 1f) {
            drawImage(
                image,
                srcOffset = IntOffset((image.width * l).toInt(), (image.height * t).toInt()),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}

@Composable
private fun TableShapeView(shape: TableShape, s: SlideScale, search: PptxSearch) {
    val total = shape.colWidthsEmu.sum().coerceAtLeast(1L)
    Column(Modifier.fillMaxSize()) {
        shape.rows.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth().height(s.dp(row.heightEmu))) {
                row.cells.forEachIndexed { i, cell ->
                    val w = shape.colWidthsEmu.getOrNull(i) ?: (total / row.cells.size.coerceAtLeast(1))
                    val hit = search.highlights(shape, ri * 1000 + i).let { h -> if (h.isEmpty()) null else h.any { it.isCurrent } }
                    Box(
                        Modifier
                            .width(s.dp(w * shape.w / total))
                            .height(s.dp(row.heightEmu))
                            .then(
                                when (hit) {
                                    null -> cell.fill?.let { hexColor(it) }?.let { Modifier.background(it) } ?: Modifier
                                    true -> Modifier.background(Color(0xFFFF9800))
                                    false -> Modifier.background(Color(0xFFFFEB3B))
                                },
                            )
                            .border(0.5.dp, Color(0x66888888))
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            cell.text,
                            color = cell.color?.let { hexColor(it) } ?: Color.Black,
                            fontSize = s.sp(cell.sizePt.coerceAtMost(14f)),
                            fontWeight = if (cell.bold) FontWeight.Bold else null,
                            maxLines = 3,
                        )
                    }
                }
            }
        }
    }
}
