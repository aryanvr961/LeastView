package com.leastview.app.ui.tools

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.R
import kotlinx.coroutines.launch

private const val DEVELOPER = "Harsh Pal"
private const val FEEDBACK_EMAIL = "business.aryanvr961@gmail.com"
private const val UPI_ID = "harshh040707@ybl"

@Composable
fun AboutScreen(onMenu: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf("") }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    fun note(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    ToolScaffold("About", onMenu, snackbar) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A73E8))) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Text("LeastView", Modifier.padding(top = 12.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (version.isNotEmpty()) Text("Version $version", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(
                "A light, fast reader and editor for PDF, Word, Excel and PowerPoint files, with tools to resize, merge and split them.",
                Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text("Developed by $DEVELOPER", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Medium)
        }

        AboutCard("Support / donate") {
            Text("If LeastView is useful to you, you can support its development by UPI.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Image(
                painterResource(R.drawable.donate_qr), "UPI QR code",
                Modifier.padding(top = 4.dp).size(220.dp).clip(RoundedCornerShape(12.dp)).align(Alignment.CenterHorizontally),
                contentScale = ContentScale.Fit,
            )
            Text("Scan with any UPI app", Modifier.align(Alignment.CenterHorizontally), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CopyRow("UPI ID", UPI_ID) { copy(context, "UPI ID", UPI_ID); note("UPI ID copied") }
        }

        AboutCard("Feedback") {
            OutlinedTextField(
                value = feedback,
                onValueChange = { feedback = it },
                label = { Text("Your message") },
                placeholder = { Text("Tell me what works, what doesn't, or what you'd like to see") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (feedback.isBlank()) note("Write a message first")
                    else if (!sendFeedback(context, feedback.trim(), version)) note("No app on this device can send the feedback. Copy the email address below and write to it directly.")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Send, null, Modifier.size(18.dp))
                Text("Send feedback", Modifier.padding(start = 8.dp))
            }
            Text(
                "Opens your email app with the message ready to send to $FEEDBACK_EMAIL.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyRow("Email", FEEDBACK_EMAIL) { copy(context, "Email", FEEDBACK_EMAIL); note("Email address copied") }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable ColumnScopeAlias.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

private typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 15.sp)
        }
        IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy $label") }
    }
}

private fun copy(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens an email draft addressed to the developer. Returns false if the device has no email app. */
private fun sendFeedback(context: Context, message: String, version: String): Boolean {
    val body = "$message\n\n— LeastView $version, Android ${Build.VERSION.RELEASE}"
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, "LeastView feedback")
        .putExtra(Intent.EXTRA_TEXT, body)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        // no email app: let the user pick any app that can share text (messaging, notes, ...)
        try {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                .putExtra(Intent.EXTRA_SUBJECT, "LeastView feedback")
                .putExtra(Intent.EXTRA_TEXT, body)
            context.startActivity(Intent.createChooser(share, "Send feedback"))
            true
        } catch (e2: ActivityNotFoundException) {
            false
        }
    }
}
