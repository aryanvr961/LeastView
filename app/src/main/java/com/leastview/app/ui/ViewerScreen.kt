package com.leastview.app.ui

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import com.leastview.app.data.DocRepository
import com.leastview.app.data.FileKind
import com.leastview.app.pdf.PdfViewer
import com.leastview.app.ui.docx.DocxScreen
import com.leastview.app.ui.pptx.PptxScreen
import com.leastview.app.ui.xlsx.XlsxScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Loaded {
    data class Ok(val file: File, val kind: FileKind) : Loaded
    data class Failed(val message: String) : Loaded
}

@Composable
fun ViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val name = remember(uri) { DocRepository.displayName(context, uri) }
    // Bumped to throw away unsaved edits and read the file again from disk.
    var generation by remember { mutableIntStateOf(0) }

    val loaded by produceState<Loaded?>(null, uri, generation) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val kind = FileKind.detect(name, context.contentResolver.getType(uri))
                    ?: return@withContext Loaded.Failed("This file type isn't supported. LeastView opens PDF, DOCX, XLSX, PPTX, TXT and images.")
                Loaded.Ok(DocRepository.copyToCache(context, uri, name), kind)
            } catch (e: Exception) {
                Loaded.Failed("Couldn't open this file (${e.message ?: "unreadable"}).")
            }
        }
    }

    val reload: () -> Unit = { generation++ }

    when (val state = loaded) {
        null -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredLoading(Modifier.padding(it))
        }
        is Loaded.Failed -> Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) {
            CenteredMessage(state.message, Modifier.padding(it))
        }
        is Loaded.Ok -> when (state.kind) {
            FileKind.PDF -> PdfScreen(state.file, uri, name, onBack)
            FileKind.DOC -> DocxScreen(state.file, uri, name, onBack, reload)
            FileKind.XLS -> XlsxScreen(state.file, uri, name, onBack, reload)
            FileKind.PPT -> PptxScreen(state.file, uri, name, onBack, reload)
            FileKind.TXT -> TxtScreen(state.file, name, onBack)
            FileKind.IMG -> ImageScreen(state.file, name, onBack)
        }
    }
}
