package com.leastview.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leastview.app.R
import com.leastview.app.data.DocFile
import com.leastview.app.data.FileKind
import com.leastview.app.ui.tools.AboutScreen
import com.leastview.app.ui.tools.MergerScreen
import com.leastview.app.ui.tools.ResizerScreen
import com.leastview.app.ui.tools.SplitterScreen
import kotlinx.coroutines.launch

enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Resizer("Resizer", Icons.Filled.PhotoSizeSelectLarge),
    Mergers("Mergers", Icons.Filled.CallMerge),
    Splitter("Splitter", Icons.Filled.CallSplit),
    About("About", Icons.Filled.Info),
}

/**
 * The app frame: a hamburger-opened drawer with Home, Resizer, Mergers, Splitter and About.
 * Home's search text and filter live here, above the screen switch, so visiting another page and
 * coming back finds the list exactly as it was left.
 */
@Composable
fun AppShell(
    files: List<DocFile>,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onBrowse: () -> Unit,
    onOpen: (DocFile) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterLabel by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = FileKind.entries.firstOrNull { it.label == filterLabel }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && destination != Destination.Home) { destination = Destination.Home }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen || destination == Destination.Home,
        drawerContent = {
            ModalDrawerSheet {
                DrawerHeader()
                HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                Destination.entries.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(d.label) },
                        icon = { Icon(d.icon, null) },
                        selected = destination == d,
                        onClick = {
                            destination = d
                            scope.launch { drawer.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        when (destination) {
            Destination.Home -> HomeScreen(
                files = files,
                hasAccess = hasAccess,
                query = query,
                onQueryChange = { query = it },
                filter = filter,
                onFilterChange = { filterLabel = it?.label },
                onMenu = openMenu,
                onRequestAccess = onRequestAccess,
                onBrowse = onBrowse,
                onOpen = onOpen,
            )
            Destination.Resizer -> ResizerScreen(openMenu, files, hasAccess, onRequestAccess)
            Destination.Mergers -> MergerScreen(openMenu, files, hasAccess, onRequestAccess)
            Destination.Splitter -> SplitterScreen(openMenu, files, hasAccess, onRequestAccess)
            Destination.About -> AboutScreen(openMenu)
        }
    }
}

@Composable
private fun DrawerHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A73E8))) {
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.Center) {
            Text("LeastView", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Documents, simply", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
