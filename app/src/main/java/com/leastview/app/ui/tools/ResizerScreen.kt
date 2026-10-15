package com.leastview.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.leastview.app.data.DocFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leastview.app.data.FileKind
import com.leastview.app.tools.ImageOps
import com.leastview.app.tools.ToolFiles

private val ResizeAccent = Color(0xFF3FB27F)

private val resizeMimeTypes = arrayOf(
    "image/jpeg", "image/png", "image/webp", "application/pdf",
    FileKind.DOC.mimeType, FileKind.PPT.mimeType, FileKind.XLS.mimeType,
)

@Composable
fun ResizerScreen(
    onMenu: () -> Unit,
    files: List<DocFile>,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    vm: ResizerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showRecents by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.add(uris) }

    if (showRecents) {
        RecentFilesSheet(
            files = files, hasAccess = hasAccess,
            allowed = setOf(FileKind.IMG, FileKind.PDF, FileKind.DOC, FileKind.PPT, FileKind.XLS),
            multiple = true,
            onRequestAccess = onRequestAccess,
            onBrowse = { showRecents = false; picker.launch(resizeMimeTypes) },
            onPicked = { showRecents = false; vm.add(it) },
            onDismiss = { showRecents = false },
        )
    }

    ToolScaffold("Resizer", onMenu, snackbar) {
        FeatureCard(
            Icons.Filled.PhotoSizeSelectLarge, "Resize",
            "Resize JPG and PNG images, or shrink PDF, DOCX, PPTX and XLSX files. Originals are never changed.", ResizeAccent,
        )

        OutlinedButton(onClick = { showRecents = true }, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy) {
            Icon(Icons.Filled.NoteAdd, null, Modifier.size(18.dp))
            Text(if (vm.items.isEmpty()) "Choose files" else "Add more files", Modifier.padding(start = 8.dp))
        }
        vm.notice?.let { NoteText(it) }

        if (vm.items.isNotEmpty()) {
            vm.items.forEachIndexed { i, item ->
                FileRow(item.name, "${item.kind?.label ?: "?"}  |  ${ToolFiles.human(context, item.size)}", item.kind) {
                    RemoveButton { vm.remove(i) }
                }
            }

            if (vm.hasImages) ImageSettings(vm)
            if (vm.hasPdf || vm.hasOffice) DocumentSettings(vm)

            vm.settingsProblem?.let { NoteText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = vm::run, enabled = vm.canRun, modifier = Modifier.weight(1f)) {
                    Text("Resize ${vm.items.size} file${if (vm.items.size == 1) "" else "s"}")
                }
                OutlinedButton(onClick = vm::clear, enabled = !vm.busy) { Text("Clear") }
            }
        }

        if (vm.busy) ProgressCard(vm.progress, vm.status)

        if (vm.results.isNotEmpty()) {
            SectionLabel("Results")
            vm.results.forEach { r -> ResultCard(r, context, snackbar) }
            val outputs = vm.results.mapNotNull { it.output }
            if (outputs.isNotEmpty()) SaveShareBar(outputs, snackbar)
        }
    }
}

@Composable
private fun ImageSettings(vm: ResizerViewModel) {
    SettingsCard("Images") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResizerViewModel.ImageMode.entries.forEach { m ->
                FilterChip(selected = vm.imageMode == m, onClick = { vm.imageMode = m }, label = { Text(m.label) })
            }
        }
        when (vm.imageMode) {
            ResizerViewModel.ImageMode.PERCENT -> {
                Text("Scale to ${vm.percent}% of the original size", fontSize = 14.sp)
                Slider(value = vm.percent.toFloat(), onValueChange = { vm.percent = it.toInt() }, valueRange = 10f..100f, steps = 17)
            }
            ResizerViewModel.ImageMode.CUSTOM -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = vm.customWidth, onValueChange = { vm.customWidth = it.filter(Char::isDigit).take(5) },
                        label = { Text("Width (px)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = vm.customHeight, onValueChange = { vm.customHeight = it.filter(Char::isDigit).take(5) },
                        label = { Text("Height (px)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.keepAspect, onCheckedChange = { vm.keepAspect = it })
                    Text("Keep proportions (fit inside the box)", Modifier.padding(start = 10.dp), fontSize = 14.sp)
                }
            }
        }
        Text("Quality ${vm.imageQuality}%  (JPG only)", fontSize = 14.sp)
        Slider(value = vm.imageQuality.toFloat(), onValueChange = { vm.imageQuality = it.toInt() }, valueRange = 30f..100f)
        Text("Save as", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.imageFormat == null, onClick = { vm.imageFormat = null }, label = { Text("Same as original") })
            ImageOps.Format.entries.forEach { f ->
                FilterChip(selected = vm.imageFormat == f, onClick = { vm.imageFormat = f }, label = { Text(f.label) })
            }
        }
    }
}

@Composable
private fun DocumentSettings(vm: ResizerViewModel) {
    SettingsCard(if (vm.hasPdf && vm.hasOffice) "PDF and Office documents" else if (vm.hasPdf) "PDF" else "Office documents") {
        Text("Compression strength", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResizerViewModel.Strength.entries.forEach { s ->
                FilterChip(selected = vm.strength == s, onClick = { vm.strength = s }, label = { Text(s.label) })
            }
        }
        NoteText("Pictures inside the document are scaled down to at most ${vm.strength.maxDim} px and recompressed. Text, fonts and layout are not touched.")
        if (vm.hasPdf) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.flattenPdf, onCheckedChange = { vm.flattenPdf = it })
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Flatten PDF pages to pictures", fontSize = 14.sp)
                    NoteText("Smallest files, but text can no longer be selected or searched.")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ResultCard(r: ResizeResult, context: android.content.Context, snackbar: SnackbarHostState) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.name, fontWeight = FontWeight.Medium)
            when {
                r.error != null -> Text(r.error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                r.output == null -> Text(r.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                else -> {
                    val saved = if (r.before > 0) 100 - (r.after * 100 / r.before).toInt() else 0
                    Text(
                        "${ToolFiles.human(context, r.before)} → ${ToolFiles.human(context, r.after)}" +
                            if (saved > 0) "  (${saved}% smaller)" else if (saved < 0) "  (${-saved}% larger)" else "",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                    Text(r.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    if (r.beforeThumb != null && r.afterThumb != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Thumb("Before", r.beforeThumb, Modifier.weight(1f))
                            Thumb("After", r.afterThumb, Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { ToolFiles.openInViewer(context, r.output) }) { Text("Preview") }
                        OutlinedButton(onClick = { ToolFiles.share(context, listOf(r.output)) }) { Text("Share") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(label: String, image: ImageBitmap, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            image, label, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)),
        )
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
