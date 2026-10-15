package com.leastview.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerTopBar(
    title: String,
    onBack: () -> Unit,
    navIcon: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") },
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { navIcon() } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun EditFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Icon(Icons.Filled.Edit, contentDescription = "Edit")
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * Saves a document back to the file it was opened from. The new file is built in the cache and
 * verified first, so a failed write can never leave a half-written original. When the source
 * can't be written (read-only share from another app), it offers "Save a copy" instead.
 */
class DocSaver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uri: Uri,
    private val snackbar: SnackbarHostState,
) {
    var saving by mutableStateOf(false)
        private set

    internal var launchCreate: (() -> Unit)? = null
    private var pending: Pair<(OutputStream) -> Unit, (File) -> Unit>? = null

    /** The freshly built file failed [save]'s verification, so nothing was written anywhere. */
    private class VerificationFailed(cause: Throwable) : Exception(cause)

    /**
     * [verify] gets the finished temp file and must throw if it doesn't read back as the document the
     * user is looking at. It runs before the original is touched.
     */
    fun save(writer: (OutputStream) -> Unit, verify: (File) -> Unit = {}, onSaved: () -> Unit = {}) {
        if (saving) return
        scope.launch {
            saving = true
            val result = write(uri, writer, verify)
            saving = false
            val failure = result.exceptionOrNull()
            when {
                failure == null -> {
                    onSaved()
                    snackbar.showSnackbar("Saved")
                }
                failure is VerificationFailed ->
                    snackbar.showSnackbar("Couldn't save safely, your original file was not changed")
                // the document itself can't be written (e.g. a character the PDF can't hold): say so, don't offer a copy
                failure is com.leastview.app.tools.PdfOps.PdfProblem ->
                    snackbar.showSnackbar(failure.message ?: "Couldn't save this PDF")
                else -> {
                    pending = writer to verify
                    val r = snackbar.showSnackbar("Couldn't save to the original file", actionLabel = "Save a copy")
                    if (r == SnackbarResult.ActionPerformed) launchCreate?.invoke()
                }
            }
        }
    }

    internal fun saveCopyTo(target: Uri, onSaved: () -> Unit) {
        val (writer, verify) = pending ?: return
        scope.launch {
            val ok = write(target, writer, verify).isSuccess
            if (ok) onSaved()
            snackbar.showSnackbar(if (ok) "Copy saved" else "Couldn't save the copy")
        }
    }

    private suspend fun write(target: Uri, writer: (OutputStream) -> Unit, verify: (File) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "save-${System.nanoTime()}.tmp")
            try {
                tmp.outputStream().use(writer)
                ZipFile(tmp).use { z -> check(z.entries().hasMoreElements()) { "empty archive" } }
                try { verify(tmp) } catch (e: Exception) { throw VerificationFailed(e) }
                val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Cannot open $target for writing")
                out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                Unit
            } finally {
                tmp.delete()
            }
        }
    }
}

@Composable
fun rememberDocSaver(uri: Uri, name: String, mime: String, snackbar: SnackbarHostState, onCopySaved: () -> Unit = {}): DocSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saver = remember(uri) { DocSaver(context, scope, uri, snackbar) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { target ->
        target?.let { saver.saveCopyTo(it, onCopySaved) }
    }
    saver.launchCreate = { launcher.launch(name) }
    return saver
}
