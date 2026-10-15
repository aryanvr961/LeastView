package com.leastview.app.pdf

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.leastview.app.ui.FastScroller
import com.leastview.app.ui.ZoomBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface PdfState {
    data object Loading : PdfState
    class Ready(val session: PdfSession) : PdfState
    class Failed(val message: String) : PdfState
}

/** Stacked white pages on a dark canvas with pinch-zoom, like the Drive PDF viewer. */
@Composable
fun PdfViewer(
    file: File,
    modifier: Modifier = Modifier,
    search: PdfSearchState? = null,
    /** Set while editing text: tapping a line of a page then lets the user retype it. */
    textEdit: PdfTextEdit? = null,
    lineSource: PdfLineSource? = null,
) {
    val listState = rememberLazyListState()
    val state by produceState<PdfState>(PdfState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                PdfState.Ready(PdfSession(file))
            } catch (e: SecurityException) {
                PdfState.Failed("This PDF is password protected. Password-protected PDFs aren't supported yet.")
            } catch (e: Exception) {
                PdfState.Failed("Couldn't open this PDF (${e.message ?: "unreadable file"}).")
            }
        }
    }

    // Capture the session in a local: onDispose must close the session this effect was started for,
    // not whatever the state holds by the time it runs.
    val ready = (state as? PdfState.Ready)?.session
    DisposableEffect(ready) { onDispose { ready?.close() } }

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            PdfState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is PdfState.Failed -> Text(
                s.message,
                Modifier.align(Alignment.Center).padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is PdfState.Ready -> {
            ZoomBox { zoom ->
                if (zoom.viewWidthPx > 0) {
                    val gutterPx = with(LocalDensity.current) { 12.dp.roundToPx() }
                    val pageWidthPx = zoom.viewWidthPx - gutterPx * 2

                    // jump to the page (and height on it) of the selected search result
                    val target = search?.currentMatch
                    LaunchedEffect(target, pageWidthPx) {
                        if (target != null && target.page < s.session.pageCount) {
                            val pageHeight = pageWidthPx / s.session.aspectRatio(target.page)
                            val y = target.rects.firstOrNull()?.top ?: 0f
                            listState.scrollToItem(target.page, (y * pageHeight - pageHeight * 0.15f).toInt().coerceAtLeast(0))
                        }
                    }
                    // highlights per page, rebuilt only when results or the selection change
                    val byPage = remember(search?.matches?.size, search?.current) {
                        search?.matches?.withIndex()?.groupBy({ it.value.page }, { it }).orEmpty()
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp + zoom.verticalPadding),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.session.pageCount, key = { it }) { index ->
                            PdfPage(s.session, index, pageWidthPx, byPage[index].orEmpty(), search?.current ?: -1, textEdit, lineSource)
                        }
                    }
                }
            }
            FastScroller(listState)
            }
        }
    }
}

@Composable
private fun PdfPage(
    session: PdfSession, index: Int, pageWidthPx: Int, hits: List<IndexedValue<PdfMatch>>, currentIndex: Int,
    textEdit: PdfTextEdit?, lineSource: PdfLineSource?,
) {
    // Rendered once per page at ~1.5x its on-screen width (capped): sharp enough to read, small enough to scroll smoothly.
    val renderWidth = (pageWidthPx * 3 / 2).coerceIn(1, MAX_RENDER_WIDTH)
    var bitmap by remember(index, renderWidth) { mutableStateOf<ImageBitmap?>(session.cached(index, renderWidth)?.asImageBitmap()) }
    LaunchedEffect(index, renderWidth) {
        if (bitmap == null) bitmap = session.render(index, renderWidth)?.asImageBitmap()
    }
    Box(Modifier.fillMaxWidth().aspectRatio(session.aspectRatio(index)).background(Color.White)) {
        bitmap?.let { Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
        if (hits.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for ((i, hit) in hits) {
                    val color = if (i == currentIndex) Color(0x99FF9800) else Color(0x80FFEB3B)
                    for (r in hit.rects) {
                        drawRect(color, Offset(r.left * size.width, r.top * size.height), Size(r.width() * size.width, r.height() * size.height))
                    }
                }
            }
        }
        if (textEdit != null && lineSource != null) {
            TextEditLayer(index, session.widthPt(index), session.heightPt(index), textEdit, lineSource) { bitmap?.asAndroidBitmap() }
        }
    }
}

private const val MAX_RENDER_WIDTH = 1600

/** Owns a PdfRenderer. PdfRenderer allows only one open page at a time, hence the mutex. */
private class PdfSession(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Throwable) {
        runCatching { fd.close() }
        throw e
    }
    private val lock = Mutex()

    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    // Page sizes are read once, up front and single-threaded (the session is built off the main
    // thread), so no page is ever open while render() runs.
    private val sizes: Array<Pair<Float, Float>> = Array(pageCount) { index ->
        runCatching { renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() } }.getOrDefault(595f to 842f)
    }

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun aspectRatio(index: Int): Float = sizes[index].first / sizes[index].second

    /** Page size in PDF points as displayed (rotation applied). */
    fun widthPt(index: Int): Float = sizes[index].first
    fun heightPt(index: Int): Float = sizes[index].second

    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get("$index@$widthPx")

    /** Returns null if the session was closed or the page could not be rendered. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        cache.get("$index@$widthPx")?.let { return@withContext it }
        lock.withLock {
            if (closed) return@withLock null
            try {
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(AndroidColor.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put("$index@$widthPx", bmp)
                    bmp
                }
            } catch (e: OutOfMemoryError) {
                cache.evictAll()
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun close() {
        closed = true
        // Close under the lock so an in-flight render finishes first.
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }
}
