package com.leastview.app.tools

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.core.content.FileProvider
import com.leastview.app.ViewerActivity
import com.leastview.app.data.DocRepository
import java.io.File

/** A finished result of a tool, still in the app cache until the user saves or shares it. */
data class OutputFile(val file: File, val name: String, val mime: String)

object ToolFiles {
    private fun root(context: Context, dir: String) = File(context.cacheDir, dir).apply { mkdirs() }

    fun sanitize(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "file" }

    /** Copies a picked document into private storage so every library can use a normal seekable File. */
    fun copyIn(context: Context, uri: Uri, name: String): File {
        val dir = File(root(context, "work"), System.nanoTime().toString()).apply { mkdirs() }
        val target = File(dir, sanitize(name))
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: error("Cannot open $name")
        return target
    }

    /** A fresh, empty output path. Every output gets its own folder so equal names never clash. */
    fun newOutput(context: Context, name: String): File {
        val dir = File(root(context, "out"), System.nanoTime().toString()).apply { mkdirs() }
        return File(dir, sanitize(name))
    }

    /** Deletes the private working copy made by [copyIn] (each lives in its own folder). */
    fun discard(file: File) {
        file.parentFile?.deleteRecursively()
    }

    fun name(context: Context, uri: Uri): String = DocRepository.displayName(context, uri)

    fun size(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    fun human(context: Context, bytes: Long): String = if (bytes < 0) "?" else Formatter.formatShortFileSize(context, bytes)

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(context: Context, files: List<OutputFile>) {
        if (files.isEmpty()) return
        val uris = files.map { uriFor(context, it.file) }
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(files[0].mime)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openInViewer(context: Context, file: OutputFile) {
        val intent = Intent(context, ViewerActivity::class.java)
            .setDataAndType(uriFor(context, file.file), file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Needs the legacy storage permission only on Android 9 and older; newer versions write through MediaStore without one. */
    val needsLegacyStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Copies every file into the device's main Documents folder. A name that is already taken gets " (1)" etc.
     * Returns the names the files ended up with; files that couldn't be written are left out.
     */
    fun saveToDocuments(context: Context, files: List<OutputFile>): List<String> =
        files.mapNotNull { f ->
            runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeViaMediaStore(context, f) else writeLegacy(context, f) }.getOrNull()
        }

    private fun writeViaMediaStore(context: Context, f: OutputFile): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, f.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until fully written
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> f.file.inputStream().use { it.copyTo(out) } } ?: error("Cannot write $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: f.name
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // never leave a half-written file behind
            null
        }
    }

    private fun writeLegacy(context: Context, f: OutputFile): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).apply { mkdirs() }
        val base = f.name.substringBeforeLast('.')
        val ext = f.name.substringAfterLast('.', "")
        var target = File(dir, f.name)
        var n = 1
        while (target.exists()) target = File(dir, "$base ($n)" + if (ext.isEmpty()) "" else ".$ext").also { n++ }
        f.file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(f.mime), null)
        return target.name
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}
