package com.leastview.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leastview.app.data.DocFile
import com.leastview.app.data.FileKind
import com.leastview.app.tools.ToolFiles

private val MergeAccent = Color(0xFF8E67E8)

private val mergeMimeTypes = arrayOf(
    "application/pdf", "text/plain", "image/*",
    FileKind.DOC.mimeType, FileKind.XLS.mimeType, FileKind.PPT.mimeType,
)

@Composable
fun MergerScreen(
    onMenu: () -> Unit,
    files: List<DocFile>,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    vm: MergerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askConsent by remember { mutableStateOf(false) }
    var showRecents by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.add(uris) }

    if (showRecents) {
        RecentFilesSheet(
            files = files, hasAccess = hasAccess,
            allowed = setOf(FileKind.PDF, FileKind.TXT, FileKind.IMG, FileKind.DOC, FileKind.XLS, FileKind.PPT),
            multiple = true,
            onRequestAccess = onRequestAccess,
            onBrowse = { showRecents = false; picker.launch(mergeMimeTypes) },
            onPicked = { showRecents = false; vm.add(it) },
            onDismiss = { showRecents = false },
        )
    }

    ToolScaffold("Mergers", onMenu, snackbar) {
        FeatureCard(
            Icons.Filled.CallMerge, "Merge files",
            "Combine PDF, TXT, Word, Excel, PowerPoint and image files into one, in the order you want.", MergeAccent,
        )

        OutlinedButton(onClick = { showRecents = true }, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy) {
            Icon(Icons.Filled.NoteAdd, null, Modifier.size(18.dp))
            Text(if (vm.items.isEmpty()) "Choose files" else "Add more files", Modifier.padding(start = 8.dp))
        }
        vm.notice?.let { NoteText(it) }

        if (vm.items.isNotEmpty()) {
            SectionLabel("${vm.items.size} file${if (vm.items.size == 1) "" else "s"}, merged top to bottom")
            vm.items.forEachIndexed { i, item ->
                FileRow(
                    "${i + 1}. ${item.name}",
                    "${item.kind?.label ?: "?"}  |  ${ToolFiles.human(context, item.size)}",
                    item.kind,
                ) {
                    IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0 && !vm.busy) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                    IconButton(onClick = { vm.move(i, 1) }, enabled = i < vm.items.lastIndex && !vm.busy) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                    IconButton(onClick = { vm.remove(i) }, enabled = !vm.busy) { Icon(Icons.Filled.Close, "Remove") }
                }
            }

            NoteText(
                buildString {
                    append("Result: a single ${vm.output.label} file.")
                    if (vm.output == MergerViewModel.Output.PDF && vm.items.any { it.kind != FileKind.PDF }) {
                        append(" Files that aren't PDFs are converted to PDF first.")
                    }
                    if (vm.items.size < 2) append(" Add at least two files.")
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { if (vm.needsConversionConsent) askConsent = true else vm.merge() },
                    enabled = vm.canMerge,
                    modifier = Modifier.weight(1f),
                ) { Text("Merge") }
                OutlinedButton(onClick = vm::clear, enabled = !vm.busy) { Text("Clear") }
            }
        }

        if (vm.busy) ProgressCard(vm.progress, vm.status.ifBlank { "Merging…" })
        vm.error?.let { ErrorCard(it) }

        vm.result?.let { r ->
            SectionLabel("Merged file")
            OutputRow(
                r.output,
                buildString {
                    r.pages?.let { append("$it page${if (it == 1) "" else "s"}  |  ") }
                    append(ToolFiles.human(context, r.size))
                },
                if (r.output.mime == "application/pdf") FileKind.PDF else FileKind.TXT,
                onShare = { ToolFiles.share(context, listOf(r.output)) },
                onOpen = { ToolFiles.openInViewer(context, r.output) },
            )
            SaveShareBar(listOf(r.output), snackbar)
        }
    }

    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Merge as a PDF?") },
            text = {
                Text(
                    "Word, Excel and PowerPoint files can't be joined into one editable document. " +
                        "They will be converted to PDF and merged into a single PDF, so the result can't be edited as an Office file. " +
                        "Fonts and layout of the converted pages may differ slightly from the originals. Your original files are not changed.",
                )
            },
            confirmButton = { TextButton(onClick = { askConsent = false; vm.merge() }) { Text("Convert and merge") } },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Cancel") } },
        )
    }
}
