package com.leastview.app.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.data.DocFile
import com.leastview.app.data.FileKind

fun FileKind.accent(): Color = when (this) {
    FileKind.PDF -> PdfRed
    FileKind.DOC -> DocBlue
    FileKind.XLS -> SheetGreen
    FileKind.PPT -> SlideOrange
    FileKind.TXT -> Color(0xFF78909C)
    FileKind.IMG -> Color(0xFF9C6ADE)
}

/** Which files the home list shows for a given filter and query. Pure, so it is easy to reason about and test. */
fun filterDocuments(files: List<DocFile>, query: String, filter: FileKind?): List<DocFile> {
    val q = query.trim()
    return files.filter { f ->
        (filter == null || f.kind == filter) &&
            // photos would drown the document list, so they only appear under the IMG filter or when searching
            (filter != null || q.isNotEmpty() || f.kind != FileKind.IMG) &&
            (q.isEmpty() || f.name.contains(q, ignoreCase = true))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    files: List<DocFile>,
    hasAccess: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: FileKind?,
    onFilterChange: (FileKind?) -> Unit,
    onMenu: () -> Unit,
    onRequestAccess: () -> Unit,
    onBrowse: () -> Unit,
    onOpen: (DocFile) -> Unit,
) {
    val shown = remember(files, query, filter) { filterDocuments(files, query, filter) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("LeastView", fontWeight = FontWeight.Medium) },
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Open menu") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onBrowse, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Filled.Add, contentDescription = "Open a file")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(query, onQueryChange)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(selected = filter == null, onClick = { onFilterChange(null) }, label = { Text("All") })
                }
                items(FileKind.entries) { kind ->
                    FilterChip(
                        selected = filter == kind,
                        onClick = { onFilterChange(if (filter == kind) null else kind) },
                        label = { Text(kind.label) },
                    )
                }
            }

            when {
                !hasAccess -> AccessPrompt(onRequestAccess, onBrowse)
                shown.isEmpty() -> EmptyResults(query)
                else -> FileList(shown, onOpen)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (query.isEmpty()) Text("Search all documents", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* results already update as you type */ }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyResults(query: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (query.isBlank()) "No documents found" else "No documents match \"${query.trim()}\"",
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccessPrompt(onRequestAccess: () -> Unit, onBrowse: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("See all your documents here", style = MaterialTheme.typography.titleMedium)
        Text(
            "Allow file access to list your PDF, Word, Excel, PowerPoint, text and image files, or pick one file yourself.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequestAccess) { Text("Allow access") }
        TextButton(onClick = onBrowse) { Text("Browse for a file") }
    }
}

@Composable
private fun FileList(files: List<DocFile>, onOpen: (DocFile) -> Unit) {
    val context = LocalContext.current
    val grouped = remember(files) { files.groupBy { dayLabel(it.modifiedMillis) } }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        grouped.forEach { (label, group) ->
            item(key = "h-$label") {
                Text(
                    "$label  |  ${group.size} item${if (group.size == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(group, key = { it.uri.toString() }) { file ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(file) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(file.kind.accent().copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Description, null, tint = file.kind.accent())
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(
                            "${file.kind.label}  |  ${Formatter.formatShortFileSize(context, file.sizeBytes)}  |  " +
                                DateUtils.formatDateTime(context, file.modifiedMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun dayLabel(millis: Long): String {
    val days = ((System.currentTimeMillis() - millis) / DateUtils.DAY_IN_MILLIS).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}
