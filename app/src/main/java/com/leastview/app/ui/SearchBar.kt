package com.leastview.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Search toolbar used by every document type (PDF, Word, Excel, PowerPoint):
 * back arrow, the query, "2 of 12", then previous / next chevrons.
 * A one-line status or explanation shows underneath when there is something to say.
 */
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    /** 1-based position of the highlighted result, 0 when there is none. */
    position: Int,
    total: Int,
    busy: Boolean,
    message: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.background) {
        // the bar sits below the status bar instead of being drawn underneath its icons
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                Box(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search in document", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onNext() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    if (busy && total == 0) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(
                        if (total == 0) "0 results" else "$position of $total",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                    IconButton(onClick = onPrevious, enabled = total > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous result") }
                    IconButton(onClick = onNext, enabled = total > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next result") }
                }
            }
            if (message != null) {
                Text(
                    message,
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
