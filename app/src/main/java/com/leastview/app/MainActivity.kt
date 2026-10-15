package com.leastview.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.leastview.app.data.DocFile
import com.leastview.app.data.DocRepository
import com.leastview.app.data.FileKind
import com.leastview.app.ui.AppShell
import com.leastview.app.ui.LeastViewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var files by mutableStateOf<List<DocFile>>(emptyList())
    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { openDocument(it) }
            }
            val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refresh()
            }
            LeastViewTheme {
                AppShell(
                    files = files,
                    hasAccess = hasAccess,
                    onRequestAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        } else {
                            legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                    onBrowse = {
                        picker.launch(FileKind.entries.flatMap { it.mimeTypes }.toTypedArray())
                    },
                    onOpen = { openDocument(it.uri) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh() // also picks up the grant when the user returns from the system settings page
    }

    private fun refresh() {
        hasAccess = DocRepository.canListDevice() &&
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (!hasAccess) {
            files = emptyList()
            return
        }
        // MediaStore can hold tens of thousands of rows; keep the query off the UI thread
        lifecycleScope.launch {
            files = withContext(Dispatchers.IO) { runCatching { DocRepository.queryDevice(this@MainActivity) }.getOrDefault(emptyList()) }
        }
    }

    private fun openDocument(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
