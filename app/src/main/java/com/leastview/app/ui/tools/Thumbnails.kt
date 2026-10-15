package com.leastview.app.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import com.leastview.app.data.DocFile
import com.leastview.app.data.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Small previews for the recent-files list: the picture itself, the first page of a PDF, or the preview image
 * Word/PowerPoint/Excel store inside their own files. Files without a preview (plain text, or Office files saved
 * without one) get null and the list shows the type icon instead.
 */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val none = ConcurrentHashMap.newKeySet<String>()

    // a few at a time: decoding a long list at once would stall scrolling and spike memory
    private val gate = Semaphore(3)

    suspend fun load(context: Context, file: DocFile, px: Int): Bitmap? {
        val key = "${file.uri}@$px"
        cache.get(key)?.let { return it }
        if (key in none) return null
        val bmp = gate.withPermit {
            withContext(Dispatchers.IO) { runCatching { render(context, file, px) }.getOrNull() }
        }
        if (bmp == null) none += key else cache.put(key, bmp)
        return bmp
    }

    private fun render(context: Context, f: DocFile, px: Int): Bitmap? = when (f.kind) {
        FileKind.IMG -> image(context, f, px)
        FileKind.PDF -> pdfFirstPage(context, f, px)
        FileKind.DOC, FileKind.XLS, FileKind.PPT -> embedded(context, f, px)
        FileKind.TXT -> null
    }

    private fun image(context: Context, f: DocFile, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return context.contentResolver.openInputStream(f.uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun pdfFirstPage(context: Context, f: DocFile, px: Int): Bitmap? =
        context.contentResolver.openFileDescriptor(f.uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return@use null
                renderer.openPage(0).use { page ->
                    val h = (px * page.height / page.width.toFloat()).toInt().coerceIn(1, px * 2)
                    Bitmap.createBitmap(px, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun embedded(context: Context, f: DocFile, px: Int): Bitmap? {
        if (f.sizeBytes > 40L * 1024 * 1024) return null // scanning a huge file just for a preview isn't worth it
        context.contentResolver.openInputStream(f.uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase()
                    if (name == "docprops/thumbnail.jpeg" || name == "docprops/thumbnail.jpg" || name == "docprops/thumbnail.png") {
                        val bytes = zip.readBytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth <= 0) return null
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
            }
        }
        return null
    }
}
