package com.leastview.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

object DocRepository {
    /** Upper bound on listed files, so a phone with a huge photo library can't make the list heavy. Newest come first. */
    private const val MAX_FILES = 20_000

    /** True when the app is allowed to list documents that live outside its own sandbox. */
    fun canListDevice(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** All pdf/docx/xlsx/pptx files on shared storage, newest first. */
    fun queryDevice(context: Context): List<DocFile> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val clauses = FileKind.entries.flatMap { kind ->
            kind.mimeTypes.map { "${MediaStore.Files.FileColumns.MIME_TYPE} = '$it'" } +
                kind.extensions.map { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.$it'" }
        }
        val result = ArrayList<DocFile>()
        context.contentResolver.query(
            collection,
            projection,
            clauses.joinToString(" OR "),
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (c.moveToNext() && result.size < MAX_FILES) {
                val name = c.getString(nameCol) ?: continue
                val kind = FileKind.detect(name, c.getString(mimeCol)) ?: continue
                result += DocFile(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    name = name,
                    kind = kind,
                    sizeBytes = c.getLong(sizeCol),
                    modifiedMillis = c.getLong(dateCol) * 1000L,
                )
            }
        }
        return result
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "Document"
    }

    /**
     * Copies the document into the app cache so every viewer can work on a real, seekable file
     * (content:// streams from chat apps are often not seekable, which PdfRenderer requires).
     */
    fun copyToCache(context: Context, uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "open").apply { mkdirs() }
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error("Cannot open $uri")
        return target
    }
}
