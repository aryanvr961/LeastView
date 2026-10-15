package com.leastview.app.ui.xlsx

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.platform.LocalDensity
import com.leastview.app.ui.FastScroller
import com.leastview.app.ui.SearchTopBar
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.data.FileKind
import com.leastview.app.office.OoxmlPackage
import com.leastview.app.office.xlsx.Sheet
import com.leastview.app.office.xlsx.Workbook
import com.leastview.app.office.xlsx.XCell
import com.leastview.app.office.xlsx.XlsxParser
import com.leastview.app.office.xlsx.XlsxWriter
import com.leastview.app.office.xlsx.columnName
import com.leastview.app.ui.CenteredLoading
import com.leastview.app.ui.CenteredMessage
import com.leastview.app.ui.EditFab
import com.leastview.app.ui.ViewerTopBar
import com.leastview.app.ui.adaptForPage
import com.leastview.app.ui.contrastOn
import com.leastview.app.ui.hexColor
import com.leastview.app.ui.rememberDocSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private sealed interface BookState {
    data object Loading : BookState
    class Ready(val book: Workbook) : BookState
    class Failed(val message: String) : BookState
}

private const val MAX_ROWS = 100_000
private const val MAX_COLS = 200
private val ROW_HEADER_WIDTH = 44.dp
private val DEFAULT_ROW_HEIGHT = 28.dp

@Composable
fun XlsxScreen(file: File, uri: Uri, name: String, onBack: () -> Unit, onReload: () -> Unit) {
    val state by produceState<BookState>(BookState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                BookState.Ready(XlsxParser.parse(OoxmlPackage(file)))
            } catch (e: Exception) {
                BookState.Failed("Couldn't open this spreadsheet (${e.message ?: "unsupported file"}).")
            }
        }
    }
    val book = (state as? BookState.Ready)?.book
    DisposableEffect(book) { onDispose { book?.pkg?.close() } }

    when (val s = state) {
        BookState.Loading -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredLoading(Modifier.padding(it))
        }
        is BookState.Failed -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredMessage(s.message, Modifier.padding(it))
        }
        is BookState.Ready -> XlsxContent(s.book, uri, name, onBack, onReload)
    }
}

@Composable
private fun XlsxContent(book: Workbook, uri: Uri, name: String, onBack: () -> Unit, onReload: () -> Unit) {
    var sheetIndex by remember(book) { mutableIntStateOf(0) }
    var editing by remember(book) { mutableStateOf(false) }
    var selected by remember(book) { mutableStateOf<Pair<Int, Int>?>(null) }
    var unsaved by remember(book) { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val saver = rememberDocSaver(uri, name, FileKind.XLS.mimeType, snackbar)
    val sheet = book.sheets[sheetIndex]

    fun saveAndFinish() {
        if (!unsaved) { editing = false; return }
        saver.save(
            writer = { XlsxWriter.write(book, it) },
            verify = { f ->
                OoxmlPackage(f).use { pkg ->
                    val reread = XlsxParser.parse(pkg)
                    book.sheets.forEachIndexed { i, s ->
                        for (pos in s.edits.keys) {
                            check(reread.sheets[i].cell(pos.first, pos.second)?.display.orEmpty() == s.cell(pos.first, pos.second)?.display.orEmpty()) {
                                "saved cell differs"
                            }
                        }
                    }
                }
            },
            onSaved = { unsaved = false; editing = false },
        )
    }
    BackHandler(enabled = editing) { if (unsaved) confirmExit = true else editing = false }

    // search inside the workbook
    val search = remember(book) { XlsxSearch(book) }
    var searching by remember(book) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    var zoom by remember(book) { mutableFloatStateOf(1f) }
    fun closeSearch() {
        searching = false
        search.run("")
    }
    BackHandler(enabled = searching) { closeSearch() }
    // select the current result: switch to its sheet and scroll both ways so it is on screen
    LaunchedEffect(search.current, search.query) {
        val hit = search.currentHit ?: return@LaunchedEffect
        sheetIndex = hit.sheet
        selected = hit.row to hit.col
        listState.scrollToItem(hit.row)
        val target = book.sheets[hit.sheet]
        val offsetPx = with(density) { (0 until hit.col).sumOf { columnWidth(target, it, zoom).toPx().toDouble() } }.toInt()
        hScroll.scrollTo(offsetPx)
    }

    // Pinch zoom resizes the cells themselves (so text stays crisp and you see more or fewer cells).
    // The scroll position is nudged afterwards so the cell under the fingers stays under the fingers.
    val shift = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(zoom) {
        withFrameNanos { }
        withFrameNanos { } // let the grid re-measure at the new size first
        val dx = shift[0]
        val dy = shift[1]
        shift[0] = 0f
        shift[1] = 0f
        if (dx != 0f) hScroll.scrollTo((hScroll.value + dx).roundToInt().coerceAtLeast(0))
        if (dy != 0f) listState.scrollBy(dy)
    }
    LaunchedEffect(sheet.version) {
        if (searching && search.query.isNotBlank()) search.run(search.query, keepPosition = true)
    }
    val anyText = remember(book) { book.sheets.any { s -> s.cells.values.any { row -> row.values.any { it.display.isNotBlank() } } } }
    val searchMessage = when {
        search.query.isBlank() -> null
        !anyText -> "This workbook has no readable text."
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
            } else {
                val searchAction: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search in workbook") }
                }
                if (editing) ViewerTopBar(name, onBack = ::saveAndFinish, navIcon = { Icon(Icons.Filled.Check, "Done") }, actions = searchAction)
                else ViewerTopBar(name, onBack, actions = searchAction)
            }
        },
        bottomBar = { SheetTabs(book, sheetIndex) { sheetIndex = it; selected = null } },
        floatingActionButton = { if (!editing) EditFab { editing = true } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FormulaBar(sheet, selected, editing) { r, c, text ->
                sheet.edit(r, c, text)
                unsaved = true
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    val rowHeaderPx = ROW_HEADER_WIDTH.toPx()
                    val columnHeaderPx = 28.dp.toPx()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val change = event.calculateZoom()
                                if (change != 1f) {
                                    val focus = event.calculateCentroid(useCurrent = true)
                                    val newZoom = (zoom * change).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    val k = newZoom / zoom
                                    if (k != 1f) {
                                        // where the fingers are, measured from the top-left of the cell area, in px at the old size
                                        val x = hScroll.value + (focus.x - rowHeaderPx).coerceAtLeast(0f)
                                        val hidden = -(listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset ?: 0)
                                        val y = hidden + (focus.y - columnHeaderPx).coerceAtLeast(0f)
                                        shift[0] += x * (k - 1f)
                                        shift[1] += y * (k - 1f)
                                        zoom = newZoom
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            ) {
                SheetGrid(sheet, selected, listState, hScroll, search.matchesIn(sheetIndex), zoom) { r, c -> selected = r to c }
                FastScroller(listState, label = { "Row ${it.first + 1}–${it.last + 1}" })
            }
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
private fun SheetTabs(book: Workbook, current: Int, onSelect: (Int) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState())) {
            book.sheets.forEachIndexed { i, s ->
                val active = i == current
                Box(
                    Modifier
                        .clickable { onSelect(i) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        s.name,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Shows the selected cell's content; becomes an editor in edit mode (Sheets-style). */
@Composable
private fun FormulaBar(sheet: Sheet, selected: Pair<Int, Int>?, editing: Boolean, onCommit: (Int, Int, String) -> Unit) {
    sheet.version // follow edits
    val cell = selected?.let { sheet.cell(it.first, it.second) }
    val shown = when {
        cell == null -> ""
        cell.hasFormula -> "=" + (cell.formula ?: "")
        cell.number != null -> cell.number.let { if (it == Math.rint(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
        else -> cell.display
    }
    var draft by remember(selected, shown) { mutableStateOf(shown) }
    val locked = cell?.hasFormula == true
    val canEdit = editing && selected != null && !locked

    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                selected?.let { columnName(it.second) + (it.first + 1) } ?: "–",
                Modifier.width(52.dp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.CenterStart) {
                if (canEdit) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { selected?.let { onCommit(it.first, it.second, draft) } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (locked && editing) "$shown   (formulas can't be edited yet)" else shown,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    )
                }
            }
            if (canEdit && draft != shown) {
                IconButton(onClick = { selected?.let { onCommit(it.first, it.second, draft) } }) { Icon(Icons.Filled.Check, "Apply") }
            }
        }
    }
}

private fun columnWidth(sheet: Sheet, c: Int, zoom: Float = 1f): Dp {
    if (c in sheet.hiddenCols) return 0.dp
    val chars = sheet.colWidthChars[c] ?: sheet.defaultColWidthChars
    // Excel width is in characters; 7px per character + padding at 96 dpi, scaled up a little for touch
    return ((chars * 7 + 5) * 1.25).dp.coerceIn(24.dp, 420.dp) * zoom
}

private fun rowHeight(sheet: Sheet, r: Int, zoom: Float = 1f): Dp {
    if (r in sheet.hiddenRows) return 0.dp
    val pt = sheet.rowHeightPt[r] ?: return DEFAULT_ROW_HEIGHT * zoom
    return max((pt * 1.6).dp.value, DEFAULT_ROW_HEIGHT.value).dp * zoom
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 3f

@Composable
private fun SheetGrid(
    sheet: Sheet, selected: Pair<Int, Int>?,
    listState: LazyListState, hScroll: ScrollState, matches: Set<Long>, zoom: Float,
    onSelect: (Int, Int) -> Unit,
) {
    sheet.version // recompose rows after an edit
    val rows = min(max(sheet.maxRow + 30, 60), MAX_ROWS)
    val cols = min(max(sheet.maxCol + 3, 12), MAX_COLS)
    val dark = isSystemInDarkTheme()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxSize()) {
        // column letters
        Row(Modifier.fillMaxWidth().background(headerBg)) {
            Box(Modifier.width(ROW_HEADER_WIDTH).height(28.dp).border(0.5.dp, gridColor))
            Row(Modifier.horizontalScroll(hScroll)) {
                for (c in 0 until cols) {
                    val w = columnWidth(sheet, c, zoom)
                    if (w == 0.dp) continue
                    Box(Modifier.width(w).height(28.dp).border(0.5.dp, gridColor), contentAlignment = Alignment.Center) {
                        Text(columnName(c), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(rows, key = { it }) { r ->
                val h = rowHeight(sheet, r, zoom)
                if (h == 0.dp) return@items
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(ROW_HEADER_WIDTH).height(h).background(headerBg).border(0.5.dp, gridColor), contentAlignment = Alignment.Center) {
                        Text((r + 1).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Row(Modifier.horizontalScroll(hScroll)) {
                        var c = 0
                        while (c < cols) {
                            val merge = sheet.mergeCovering(r, c)
                            if (merge != null && !(merge.r1 == r && merge.c1 == c)) {
                                // inside a merged block: horizontal continuation is covered by the origin cell
                                if (merge.r1 == r) { c++; continue }
                                val w = columnWidth(sheet, c, zoom)
                                if (w > 0.dp) Box(Modifier.width(w).height(h))
                                c++
                                continue
                            }
                            val lastCol = if (merge != null) min(merge.c2, cols - 1) else c
                            var w = 0.dp
                            for (k in c..lastCol) w += columnWidth(sheet, k, zoom)
                            if (w > 0.dp) {
                                CellView(
                                    cell = sheet.cell(r, c), width = w, height = h, zoom = zoom, gridColor = gridColor, dark = dark,
                                    isSelected = selected == (r to c), defaultText = onSurface,
                                    isMatch = XlsxSearch.key(r, c) in matches,
                                    onClick = { onSelect(r, c) },
                                )
                            }
                            c = lastCol + 1
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellView(
    cell: XCell?, width: Dp, height: Dp, zoom: Float, gridColor: Color, dark: Boolean,
    isSelected: Boolean, defaultText: Color, isMatch: Boolean, onClick: () -> Unit,
) {
    val fill = if (isMatch) (if (isSelected) Color(0xFFFF9800) else Color(0xFFFFEB3B)) else cell?.style?.fill?.let { hexColor(it) }
    val textColor = when {
        fill != null -> contrastOn(fill)
        else -> adaptForPage(cell?.style?.color, dark) ?: defaultText
    }
    val align = when (cell?.style?.halign) {
        "center", "centerContinuous" -> TextAlign.Center
        "right" -> TextAlign.End
        "left" -> TextAlign.Start
        else -> if (cell != null && !cell.isText) TextAlign.End else TextAlign.Start
    }
    Box(
        Modifier
            .width(width)
            .height(height)
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else gridColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (cell != null && cell.display.isNotEmpty()) {
            Text(
                cell.display,
                modifier = Modifier.fillMaxWidth(),
                color = textColor,
                fontSize = (14f * zoom).sp,
                fontWeight = if (cell.style.bold) FontWeight.Bold else null,
                fontStyle = if (cell.style.italic) FontStyle.Italic else null,
                textAlign = align,
                maxLines = if (cell.style.wrap) 4 else 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
