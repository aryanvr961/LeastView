package com.leastview.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

object ImageOps {
    class Size(val width: Int, val height: Int)

    /** Pixel size as displayed (EXIF rotation applied), without decoding the picture. */
    fun displaySize(file: File): Size? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return if (orientation(file) in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)) Size(o.outHeight, o.outWidth)
        else Size(o.outWidth, o.outHeight)
    }

    private fun orientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun matrixFor(orientation: Int): Matrix? = Matrix().let { m ->
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        m
    }

    /** Decodes with a power-of-two sample so the result has at least [minLongSide] px on its long side (or [maxPixels] max). */
    fun decode(file: File, minLongSide: Int = Int.MAX_VALUE, maxPixels: Long = 16_000_000): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        // downsample while the next step still leaves enough detail for the requested size
        if (minLongSide != Int.MAX_VALUE) while (longSide / (sample * 2) >= minLongSide) sample *= 2
        // and always keep the decoded bitmap inside the pixel budget
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Decoded and rotated upright according to EXIF. */
    fun loadOriented(file: File, minLongSide: Int = Int.MAX_VALUE, maxPixels: Long = 16_000_000): Bitmap? {
        val bmp = decode(file, minLongSide, maxPixels) ?: return null
        val m = matrixFor(orientation(file)) ?: return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /** A small upright preview, at most [maxSide] px on its long side. */
    fun thumbnail(file: File, maxSide: Int): Bitmap? {
        val b = loadOriented(file, minLongSide = maxSide, maxPixels = 4_000_000) ?: return null
        val f = maxSide.toFloat() / maxOf(b.width, b.height)
        if (f >= 1f) return b
        val s = Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
        if (s !== b) b.recycle()
        return s
    }

    enum class Format(val label: String, val extension: String) { JPG("JPG", "jpg"), PNG("PNG", "png") }

    class Result(val width: Int, val height: Int)

    /** Writes [src] scaled to exactly [targetW] x [targetH] into [out]. */
    fun resize(src: File, targetW: Int, targetH: Int, quality: Int, format: Format, out: File): Result {
        val longTarget = maxOf(targetW, targetH)
        val bmp = loadOriented(src, minLongSide = longTarget) ?: error("${src.name} isn't a readable image")
        var scaled: Bitmap? = null
        try {
            scaled = if (bmp.width == targetW && bmp.height == targetH) bmp else Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            out.outputStream().use { stream ->
                when (format) {
                    Format.PNG -> scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    Format.JPG -> {
                        // JPEG has no transparency: put transparent pixels on white instead of letting them go black
                        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                        Canvas(flat).also { it.drawColor(Color.WHITE); it.drawBitmap(scaled, 0f, 0f, null) }
                        flat.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                        flat.recycle()
                    }
                }
            }
            return Result(targetW, targetH)
        } finally {
            if (scaled != null && scaled !== bmp) scaled.recycle()
            bmp.recycle()
        }
    }
}
