package com.leastview.app.ui.tools

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.data.FileKind
import com.leastview.app.tools.OutputFile
import com.leastview.app.tools.ToolFiles
import com.leastview.app.ui.accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Page frame shared by Resizer, Mergers, Splitter and About: hamburger toolbar plus a scrolling column of cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onMenu: () -> Unit,
    snackbar: SnackbarHostState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Medium) },
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Open menu") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Icon on the left, bold title and a short description under it. */
@Composable
fun FeatureCard(icon: ImageVector, title: String, description: String, accent: Color) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp)) }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun FileRow(name: String, subtitle: String, kind: FileKind?, trailing: @Composable RowScope.() -> Unit = {}) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = kind?.accent() ?: MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Description, null, tint = tint)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@Composable
fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.Close, "Remove") }
}

@Composable
fun ProgressCard(progress: Float?, message: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, fontSize = 14.sp)
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
        }
    }
}

@Composable
fun NoteText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

/** One finished file: name, size, and Preview / Share. */
@Composable
fun OutputRow(output: OutputFile, subtitle: String, kind: FileKind?, onShare: () -> Unit, onOpen: () -> Unit) {
    FileRow(output.name, subtitle, kind) {
        IconButton(onClick = onOpen) { Icon(Icons.Filled.OpenInNew, "Preview") }
        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
    }
}

/** Save (straight into the Documents folder, no questions asked) and Share for a set of results. */
@Composable
fun SaveShareBar(outputs: List<OutputFile>, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun save() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { ToolFiles.saveToDocuments(context, outputs) }
            snackbar.showSnackbar(
                when {
                    saved.isEmpty() -> "Couldn't save to the Documents folder"
                    saved.size < outputs.size -> "Saved ${saved.size} of ${outputs.size} files to Documents"
                    saved.size == 1 -> "Saved to Documents: ${saved[0]}"
                    else -> "Saved ${saved.size} files to Documents"
                },
            )
        }
    }

    // Android 9 and older ask once for storage permission; newer versions need none
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else scope.launch { snackbar.showSnackbar("Allow storage access to save into Documents") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val needsAsk = ToolFiles.needsLegacyStoragePermission &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsAsk) permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
            Text("Save to Documents", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = { ToolFiles.share(context, outputs) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
            Text("Share", Modifier.padding(start = 8.dp))
        }
    }
}
