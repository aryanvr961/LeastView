package com.leastview.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.leastview.app.data.DocFile
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leastview.app.data.FileKind
import com.leastview.app.tools.ToolFiles

private val SplitAccent = Color(0xFFE5574C)

@Composable
fun SplitterScreen(
    onMenu: () -> Unit,
    files: List<DocFile>,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    vm: SplitterViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showRecents by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pick) }

    if (showRecents) {
        RecentFilesSheet(
            files = files, hasAccess = hasAccess,
            allowed = setOf(FileKind.PDF),
            multiple = false,
            onRequestAccess = onRequestAccess,
            onBrowse = { showRecents = false; picker.launch(arrayOf("application/pdf")) },
            onPicked = { showRecents = false; vm.pick(it.first()) },
            onDismiss = { showRecents = false },
        )
    }

    ToolScaffold("Splitter", onMenu, snackbar) {
        FeatureCard(
            Icons.Filled.CallSplit, "Split PDF",
            "Separate one page or a whole set for easy conversion into independent PDF files.", SplitAccent,
        )

        OutlinedButton(onClick = { showRecents = true }, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
            Text(if (vm.item == null) "Choose a PDF" else "Choose a different PDF", Modifier.padding(start = 8.dp))
        }

        if (vm.loading) ProgressCard(null, "Reading the PDF…")

        vm.item?.let { item ->
            FileRow(item.name, "${vm.pageCount} page${if (vm.pageCount == 1) "" else "s"}  |  ${ToolFiles.human(context, item.size)}", FileKind.PDF)

            SectionLabel("How to split")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitterViewModel.Mode.entries.forEach { m ->
                    FilterChip(selected = vm.mode == m, onClick = { vm.mode = m }, label = { Text(m.label) })
                }
            }
            NoteText(vm.mode.help)

            when (vm.mode) {
                SplitterViewModel.Mode.RANGES, SplitterViewModel.Mode.EXTRACT -> OutlinedTextField(
                    value = vm.rangeText,
                    onValueChange = { vm.rangeText = it },
                    label = { Text("Pages") },
                    placeholder = { Text("1-3, 5, 8-10") },
                    singleLine = true,
                    isError = vm.inputError != null,
                    supportingText = { Text(vm.inputError ?: "This PDF has ${vm.pageCount} pages. Use commas between ranges; \"8-\" means page 8 to the end.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                SplitterViewModel.Mode.EVERY_N -> OutlinedTextField(
                    value = vm.everyN,
                    onValueChange = { vm.everyN = it.filter(Char::isDigit).take(5) },
                    label = { Text("Pages per file") },
                    singleLine = true,
                    isError = vm.inputError != null,
                    supportingText = { Text(vm.inputError ?: "") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SplitterViewModel.Mode.EVERY_PAGE -> vm.inputError?.let { NoteText(it) }
            }

            Button(onClick = vm::run, enabled = vm.canRun, modifier = Modifier.fillMaxWidth()) { Text("Split PDF") }
        }

        if (vm.busy) ProgressCard(vm.progress, "Splitting… ${(vm.progress * 100).toInt()}%")
        vm.error?.let { ErrorCard(it) }

        if (vm.outputs.isNotEmpty()) {
            SectionLabel("${vm.outputs.size} file${if (vm.outputs.size == 1) "" else "s"} created")
            vm.outputs.forEach { o ->
                OutputRow(
                    o.file,
                    "${o.pages} page${if (o.pages == 1) "" else "s"}  |  ${ToolFiles.human(context, o.size)}",
                    FileKind.PDF,
                    onShare = { ToolFiles.share(context, listOf(o.file)) },
                    onOpen = { ToolFiles.openInViewer(context, o.file) },
                )
            }
            SaveShareBar(vm.outputs.map { it.file }, snackbar)
        }
    }
}
