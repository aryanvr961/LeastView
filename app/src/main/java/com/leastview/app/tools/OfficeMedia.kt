package com.leastview.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.leastview.app.office.OoxmlPackage
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Shrinks a docx/pptx/xlsx by downscaling and re-compressing the pictures embedded in it.
 * Everything else (text, layout, styles) is copied byte for byte, so the document stays editable and intact.
 */
object OfficeMedia {
    class Stats(val images: Int, val recompressed: Int)

    fun shrink(src: File, out: File, maxDim: Int, jpegQuality: Int, onProgress: (Float) -> Unit = {}): Stats {
        var total = 0
        var changed = 0
        OoxmlPackage(src).use { pkg ->
            val media = pkg.entryNames().filter { n ->
                val lower = n.lowercase()
                "/media/" in lower && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))
            }
            media.forEachIndexed { i, name ->
                total++
                val original = pkg.bytes(name)
                if (original != null) recompress(name, original, maxDim, jpegQuality)?.let {
                    pkg.put(name, it)
                    changed++
                }
                onProgress((i + 1f) / media.size)
            }
            out.outputStream().use { pkg.writeTo(it) }
        }
        return Stats(total, changed)
    }

    /** The smaller encoding of the picture in the same format, or null to leave the original in place. */
    private fun recompress(name: String, bytes: ByteArray, maxDim: Int, quality: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val isPng = name.lowercase().endsWith(".png")
        // already small enough in both pixels and bytes: nothing to gain
        if (longest <= maxDim && bytes.size < 150_000) return null

        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val dl = maxOf(decoded.width, decoded.height)
            scaled = if (dl > maxDim) {
                val f = maxDim.toFloat() / dl
                Bitmap.createScaledBitmap(decoded, (decoded.width * f).toInt().coerceAtLeast(1), (decoded.height * f).toInt().coerceAtLeast(1), true)
            } else decoded
            val encoded = ByteArrayOutputStream().also {
                if (isPng) scaled.compress(Bitmap.CompressFormat.PNG, 100, it) else scaled.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }.toByteArray()
            return if (encoded.size < bytes.size) encoded else null
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            if (scaled != null && scaled !== decoded) scaled.recycle()
            decoded?.recycle()
        }
    }
}
