package com.leastview.app.ui.tools

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.data.DocFile
import com.leastview.app.data.FileKind
import com.leastview.app.ui.accent

/** A small preview of the file (picture, first page, embedded preview), or the type icon while loading or when there is none. */
@Composable
private fun Preview(f: DocFile) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 64.dp.roundToPx() }
    val thumb by produceState<ImageBitmap?>(null, f.uri) { value = Thumbnails.load(context, f, px)?.asImageBitmap() }
    val accent = f.kind.accent()
    Box(
        Modifier.size(width = 52.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumb
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            Text(
                f.kind.label,
                Modifier.align(Alignment.BottomStart).background(accent, RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(Icons.Filled.Description, null, tint = accent)
        }
    }
}

/**
 * "Choose files" for the tools: the most recent documents on the device, newest first, with search.
 * Tapping picks a file (or ticks it, when several are allowed). Files outside this list can still be
 * reached with "Browse other files", which opens the system picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesSheet(
    files: List<DocFile>,
    hasAccess: Boolean,
    allowed: Set<FileKind>,
    multiple: Boolean,
    onRequestAccess: () -> Unit,
    onBrowse: () -> Unit,
    onPicked: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<DocFile>>(emptyList()) }
    val shown = remember(files, allowed, query) {
        files.filter { it.kind in allowed && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
            Text("Recent files", Modifier.padding(horizontal = 20.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Newest first. " + allowed.joinToString(", ") { it.label } + " files.",
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )

            if (!hasAccess) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Allow file access to see your recent files here, or browse for a file yourself.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRequestAccess, modifier = Modifier.padding(top = 12.dp)) { Text("Allow access") }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search recent files") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (shown.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "No matching files found" else "No files match \"${query.trim()}\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(shown, key = { it.uri.toString() }) { f ->
                            val checked = selected.any { it.uri == f.uri }
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (multiple) selected = if (checked) selected.filterNot { it.uri == f.uri } else selected + f
                                        else onPicked(listOf(f.uri))
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Preview(f)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                                    Text(
                                        "${f.kind.label}  |  ${Formatter.formatShortFileSize(context, f.sizeBytes)}  |  " +
                                            DateUtils.getRelativeTimeSpanString(f.modifiedMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                    )
                                }
                                if (multiple) Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBrowse) { Text("Browse other files") }
                Box(Modifier.weight(1f))
                if (multiple) {
                    Button(onClick = { onPicked(selected.map { it.uri }) }, enabled = selected.isNotEmpty()) {
                        Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}")
                    }
                }
            }
        }
    }
}
