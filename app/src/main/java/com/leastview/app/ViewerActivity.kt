package com.leastview.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leastview.app.ui.LeastViewTheme
import com.leastview.app.ui.ViewerScreen

/** Target of "Open with LeastView" and of in-app taps on a document. */
class ViewerActivity : ComponentActivity() {

    private var uri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uri = documentUri(intent)
        setContent {
            LeastViewTheme {
                uri?.let { ViewerScreen(it, onBack = { finish() }) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        documentUri(intent)?.let { uri = it }
    }

    private fun documentUri(intent: Intent): Uri? = intent.data
}
