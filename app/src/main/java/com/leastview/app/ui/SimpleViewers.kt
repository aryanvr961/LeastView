package com.leastview.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.tools.ImageOps
import com.leastview.app.tools.TextFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TxtScreen(file: File, name: String, onBack: () -> Unit) {
    val loaded by produceState<Result<TextFiles.Loaded>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { TextFiles.read(file) } }
    }
    Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        val result = loaded
        when {
            result == null -> CenteredLoading(Modifier.padding(padding))
            result.isFailure -> CenteredMessage("Couldn't read this text file.", Modifier.padding(padding))
            else -> {
                val text = result.getOrThrow()
                val lines = remember(text) { text.text.split('\n') }
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                    itemsIndexed(lines) { _, line ->
                        Text(line.ifEmpty { " " }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, lineHeight = 22.sp)
                    }
                    if (text.truncated) item {
                        Text("Only the first 4 MB are shown.", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ImageScreen(file: File, name: String, onBack: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { ImageOps.loadOriented(file, maxPixels = 12_000_000)?.asImageBitmap() }.getOrNull() }
    }
    Scaffold(topBar = { ViewerTopBar(name, onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        val img = image
        if (img == null) CenteredLoading(Modifier.padding(padding))
        else ZoomBox(Modifier.padding(padding), maxScale = 6f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(img, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
