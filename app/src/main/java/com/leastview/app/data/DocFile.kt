package com.leastview.app.data

import android.net.Uri

enum class FileKind(val label: String, val extensions: List<String>, val mimeTypes: List<String>) {
    PDF("PDF", listOf("pdf"), listOf("application/pdf")),
    DOC("DOC", listOf("docx"), listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    XLS("XLS", listOf("xlsx"), listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    PPT("PPT", listOf("pptx"), listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    TXT("TXT", listOf("txt"), listOf("text/plain")),
    IMG("IMG", listOf("jpg", "jpeg", "png", "webp"), listOf("image/jpeg", "image/png", "image/webp"));

    val mimeType: String get() = mimeTypes.first()

    /** Word, Excel and PowerPoint: zip + XML packages that LeastView parses itself. */
    val isOffice: Boolean get() = this == DOC || this == XLS || this == PPT

    companion object {
        fun detect(name: String?, mime: String?): FileKind? {
            if (mime != null) entries.firstOrNull { mime in it.mimeTypes }?.let { return it }
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

data class DocFile(
    val uri: Uri,
    val name: String,
    val kind: FileKind,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)
