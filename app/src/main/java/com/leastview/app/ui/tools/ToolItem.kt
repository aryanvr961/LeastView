package com.leastview.app.ui.tools

import android.content.Context
import android.net.Uri
import com.leastview.app.data.FileKind
import com.leastview.app.tools.ToolFiles

/** A file the user picked for a tool, read once when picked. */
class ToolItem(val uri: Uri, val name: String, val kind: FileKind?, val size: Long) {
    companion object {
        fun from(context: Context, uri: Uri): ToolItem {
            val name = ToolFiles.name(context, uri)
            return ToolItem(uri, name, FileKind.detect(name, context.contentResolver.getType(uri)), ToolFiles.size(context, uri))
        }
    }
}
