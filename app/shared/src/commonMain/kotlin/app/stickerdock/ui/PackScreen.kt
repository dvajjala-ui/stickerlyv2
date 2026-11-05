package app.stickerdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.host.TargetKind
import app.stickerdock.host.WhatsAppTarget
import app.stickerdock.library.StickerFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(app: AppModel, packId: String, onBack: () -> Unit) {
    val library = app.library
    val host = app.host
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val pack by remember(packId) { library.packFlow(packId) }.collectAsState(library.pack(packId))
    val stickers by remember(packId) { library.packStickersFlow(packId) }.collectAsState(emptyList())
    val whatsApp by host.whatsApp.collectAsState()
    val offered by remember { library.shelfFlow() }.collectAsState(emptyList())
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(pack?.dataVersion) { host.refreshWhatsApp() }
    val current = pack ?: run { LaunchedEffect(Unit) { onBack() }; return }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(current.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Rename") }, { menu = false; rename = true })
                        DropdownMenuItem({ Text("Share pack") }, { menu = false; host.sharePack(packId) })
                        DropdownMenuItem({ Text("Refresh in WhatsApp") }, { menu = false; library.bump(packId); scope.notify(snackbar, "WhatsApp will reload this pack") })
                        if (current.onShelf) DropdownMenuItem({ Text("Stop offering to WhatsApp") }, {
                            menu = false
                            library.setShelf(packId, false)
                            scope.notify(snackbar, "No longer offered to WhatsApp. If WhatsApp still lists it, delete it there.") {
                                library.setShelf(packId, true)
                            }
                        })
                        DropdownMenuItem({ Text("Delete pack") }, { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { inner ->
        LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.padding(inner).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val othersOffered = offered.count { it.whatsAppReady && it.id != current.id }
                WhatsAppAddCard(app, current.id, current.count, current.whatsAppReady, whatsApp.targets, whatsApp.added[current.id].orEmpty(),
                    keyboardReady = whatsApp.keyboardEnabled,
                    overLimit = host.shelfGuideline?.takeIf { !current.onShelf && othersOffered >= it })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current.count}/${StickerRules.MAX_PACK} stickers" + if (current.kind == StickerKind.ANIMATED) " · animated" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (selection.isNotEmpty()) {
                        TextButton({ library.setTray(packId, selection.first()); selection = emptySet() }, enabled = selection.size == 1) { Text("Set cover") }
                        TextButton({
                            val ids = selection.toList(); library.removeFromPack(packId, ids); selection = emptySet()
                            scope.notify(snackbar, "Removed ${ids.size} from pack") { library.addToPack(packId, ids) }
                        }) { Text("Remove") }
                    } else if (current.count < StickerRules.MAX_PACK) TextButton({ picking = true }) { Text("+ Add stickers") }
                }
            }
            items(stickers, key = { it.id }) { item ->
                StickerTile(app.model(item), item.title, selected = item.id in selection, cover = item.id == current.trayStickerId,
                    favorite = item.favorite, animated = item.kind == StickerKind.ANIMATED,
                    onClick = { selection = selection.toggle(item.id) }, onLongClick = { selection = selection.toggle(item.id) })
            }
        }
    }

    if (rename) {
        var name by remember { mutableStateOf(current.name) }
        AlertDialog({ rename = false }, title = { Text("Rename pack") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ runCatching { library.renamePack(packId, name) }; rename = false }) { Text("Save") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancel") } })
    }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Delete “${current.name}”?") },
        text = { Text("The pack disappears from WhatsApp. Its stickers stay in your library.") },
        confirmButton = { TextButton({ confirmDelete = false; library.deletePack(packId); onBack() }) { Text("Delete pack") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    if (picking) StickerPicker(app, packId, current.kind, current.vault, onDone = { added ->
        picking = false
        if (added > 0) scope.notify(snackbar, "Added $added sticker(s)")
    })
}

@Composable
private fun WhatsAppAddCard(app: AppModel, packId: String, count: Int, ready: Boolean, targets: List<WhatsAppTarget>,
                            added: Set<String>, keyboardReady: Boolean, overLimit: Int?) {
    val host = app.host
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use in WhatsApp", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                !ready -> Text(if (count < StickerRules.MIN_PACK) "Add ${StickerRules.MIN_PACK - count} more sticker(s) — WhatsApp needs at least 3."
                    else "WhatsApp allows 30 stickers per pack. Remove ${count - StickerRules.MAX_PACK}.")
                targets.isEmpty() -> Text("No WhatsApp found on this phone.")
                else -> targets.forEach { target ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(target.label, fontWeight = FontWeight.SemiBold)
                            if (!target.ready) Text(target.setupHint ?: "Setup needed", style = MaterialTheme.typography.bodySmall)
                            else if (target.id in added) Text("Added · updates automatically", style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            !target.ready -> OutlinedButton({ host.showMessage(target.setupHint ?: "Setup needed") }) { Text("Set up") }
                            target.id in added -> OutlinedButton({ host.addToWhatsApp(packId, target.id) }) { Text("Added ✓") }
                            else -> Button({ host.addToWhatsApp(packId, target.id) }) { Text("Add") }
                        }
                    }
                }
            }
            if (ready && overLimit != null) Text(
                "You already offer $overLimit packs to WhatsApp, the most its guidelines allow per app. This one may not " +
                    "show up — remove one you rarely use in the WhatsApp tab first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val missing = targets.count { it.ready && it.id !in added }
            if (ready && missing > 1) Button({ host.addToAllWhatsApps(packId) }, Modifier.fillMaxWidth()) {
                Text("Add to all $missing WhatsApps")
            }
            if (targets.any { it.kind == TargetKind.WHATSAPP }) Text(
                "Two accounts inside one WhatsApp? Switch account in WhatsApp, then tap Add again." +
                    if (!keyboardReady) " Or turn on the StickerDock keyboard to send any sticker in any account." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StickerPicker(app: AppModel, packId: String, kind: StickerKind, vault: Boolean, onDone: (Int) -> Unit) {
    val library = app.library
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    val filter = StickerFilter(query = query, kind = kind, includeAdult = app.adultEnabled, vault = vault, limit = 400)
    val items by remember(filter) { library.stickersFlow(filter) }.collectAsState(emptyList())
    AlertDialog(
        onDismissRequest = { onDone(0) },
        title = { Text("Add to pack") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search") })
                LazyVerticalGrid(GridCells.Adaptive(72.dp), Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items.filterNot { library.isInPack(packId, it.id) }, key = { it.id }) { item ->
                        StickerTile(app.model(item), item.title, selected = item.id in chosen,
                            onClick = { chosen = chosen.toggle(item.id) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onDone(library.addToPack(packId, chosen.toList()).added.size) }) {
                Text("Add ${chosen.size}")
            }
        },
        dismissButton = { TextButton({ onDone(0) }) { Text("Cancel") } },
    )
}
