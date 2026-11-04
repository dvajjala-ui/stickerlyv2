package app.stickerdock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stickerdock.core.StickerKind
import app.stickerdock.library.StickerFilter
import app.stickerdock.library.StickerItem
import kotlinx.coroutines.CoroutineScope

private enum class LibraryMode { STICKERS, PACKS }
private enum class KindFilter(val label: String) { ALL("All"), STATIC("Static"), ANIMATED("Animated"), FAVORITES("★ Favorites") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: AppModel, modifier: Modifier, onOpenPack: (String) -> Unit, onOpenSettings: () -> Unit, scope: CoroutineScope) {
    val library = app.library
    val host = app.host
    val snackbar = LocalSnackbar.current
    var mode by rememberSaveable { mutableStateOf(LibraryMode.STICKERS) }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf(KindFilter.ALL) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<StickerItem?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var addToPack by remember { mutableStateOf<List<String>?>(null) }
    val progress by host.importProgress.collectAsState()
    val whatsApp by host.whatsApp.collectAsState()
    var dismissedImport by remember { mutableStateOf<String?>(null) }

    val filter = StickerFilter(
        query = query,
        kind = when (kindFilter) { KindFilter.STATIC -> StickerKind.STATIC; KindFilter.ANIMATED -> StickerKind.ANIMATED; else -> null },
        favoritesOnly = kindFilter == KindFilter.FAVORITES,
        includeAdult = app.adultEnabled,
        vault = app.vaultOpen,
    )
    val stickers by remember(filter) { library.stickersFlow(filter) }.collectAsState(emptyList())
    val packs by remember(app.adultEnabled, app.vaultOpen) { library.packsFlow(app.adultEnabled, app.vaultOpen) }.collectAsState(emptyList())
    val visiblePacks = if (query.isBlank()) packs else packs.filter { it.name.contains(query, ignoreCase = true) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            if (selection.isEmpty()) ExtendedFloatingActionButton(onClick = { showImport = true },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add stickers") })
        },
        bottomBar = {
            if (selection.isNotEmpty()) BottomAppBar {
                Text("${selection.size} selected", Modifier.padding(start = 12.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton({ addToPack = selection.toList() }) { Text("Add to pack") }
                TextButton({
                    val ids = selection.toList()
                    val first = library.sticker(ids.first())
                    ids.forEach { library.setFavorite(it, !(first?.favorite ?: false)) }
                    selection = emptySet()
                }) { Text("★") }
                TextButton({
                    val ids = selection.toList()
                    library.deleteStickers(ids)
                    selection = emptySet()
                    scope.notify(snackbar, "Deleted ${ids.size} sticker(s)") { library.undoDelete(ids) }
                }) { Text("Delete") }
                IconButton({ selection = emptySet() }) { Icon(Icons.Filled.Clear, "Clear selection") }
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (app.vaultOpen) "Private vault" else "My stickers", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                placeholder = { Text("Search stickers, moods, packs", maxLines = 1) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                SegmentedButton(mode == LibraryMode.STICKERS, { mode = LibraryMode.STICKERS }, SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text("Stickers")
                }
                SegmentedButton(mode == LibraryMode.PACKS, { mode = LibraryMode.PACKS }, SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text("Packs · ${packs.size}")
                }
            }
            if (mode == LibraryMode.STICKERS) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(KindFilter.entries) { f -> FilterChip(kindFilter == f, { kindFilter = f }, { Text(f.label) }) }
            }
            progress?.let { p ->
                val key = "${p.label}-${p.added}-${p.running}"
                if (p.running || dismissedImport != key) ImportBanner(p, onCancel = host::cancelImport, onDismiss = { dismissedImport = key })
            }
            when {
                mode == LibraryMode.STICKERS && stickers.isEmpty() && query.isBlank() && kindFilter == KindFilter.ALL ->
                    EmptyState(
                        "Bring your stickers home",
                        "Import the stickers already on this phone — WhatsApp keeps them in Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers. " +
                            "Then add them to every WhatsApp you use.",
                        "Import from this phone" to host::importPhoneFolder,
                        "Import ZIP or .wastickers" to host::importArchives,
                    )
                mode == LibraryMode.STICKERS -> LazyVerticalGrid(
                    GridCells.Adaptive(76.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(stickers, key = { it.id }) { item ->
                        StickerTile(
                            model = app.model(item), description = item.title, selected = item.id in selection,
                            favorite = item.favorite, animated = item.kind == StickerKind.ANIMATED,
                            onClick = { if (selection.isNotEmpty()) selection = selection.toggle(item.id) else detail = item },
                            onLongClick = { selection = selection.toggle(item.id) },
                        )
                    }
                }
                visiblePacks.isEmpty() -> EmptyState("No packs yet", "Select stickers (long-press) and tap Add to pack, or import a pack.")
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visiblePacks, key = { it.id }) { pack ->
                        val previews = remember(pack.id, pack.dataVersion) { library.packPreview(pack.id) }
                        val addedTo = whatsApp.added[pack.id].orEmpty().mapNotNull { id -> whatsApp.targets.firstOrNull { it.id == id }?.label }
                        PackCard(pack, previews, app::model, addedTo) { onOpenPack(pack.id) }
                    }
                }
            }
        }
    }

    if (showImport) ModalBottomSheet(onDismissRequest = { showImport = false }) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Add stickers", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ListItem(headlineContent = { Text("From this phone's WhatsApp") },
                supportingContent = { Text("Choose the WhatsApp Stickers folder — packs keep their original names") },
                modifier = Modifier.clickableRow { showImport = false; host.importPhoneFolder() })
            ListItem(headlineContent = { Text("ZIP, .wastickers or backup") },
                supportingContent = { Text("Sticker Maker exports, StickerDock backups (v1 and v2), plain ZIPs") },
                modifier = Modifier.clickableRow { showImport = false; host.importArchives() })
            ListItem(headlineContent = { Text("Photos and images") },
                supportingContent = { Text("PNG, JPG and WebP become 512 × 512 stickers") },
                modifier = Modifier.clickableRow { showImport = false; host.importImages() })
        }
    }

    detail?.let { item -> StickerSheet(app, item, onDismiss = { detail = null }, onAddToPack = { addToPack = listOf(item.id); detail = null }, scope = scope) }
    addToPack?.let { ids -> AddToPackDialog(app, ids, onDone = { message -> addToPack = null; selection = emptySet(); message?.let { scope.notify(snackbar, it) } }) }
}

internal fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSheet(app: AppModel, item: StickerItem, onDismiss: () -> Unit, onAddToPack: () -> Unit, scope: CoroutineScope) {
    val library = app.library
    val snackbar = LocalSnackbar.current
    var label by remember(item.id) { mutableStateOf(item.label) }
    var tags by remember(item.id) { mutableStateOf(item.tags) }
    var emojis by remember(item.id) { mutableStateOf(item.emojis.replace(",", " ")) }
    val inPacks = remember(item.id) { library.packsContaining(item.id) }
    ModalBottomSheet(onDismissRequest = {
        if (label != item.label || tags != item.tags || emojis != item.emojis.replace(",", " ")) library.updateMeta(item.id, label, tags, emojis)
        onDismiss()
    }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StickerTile(app.model(item), item.title, Modifier.size(220.dp), animated = item.kind == StickerKind.ANIMATED, onClick = {})
            }
            OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags (search words, Hinglish ok)") })
            OutlinedTextField(emojis, { emojis = it }, Modifier.fillMaxWidth(), label = { Text("Emojis for WhatsApp search (up to 3)") }, singleLine = true)
            if (inPacks.isNotEmpty()) Text("In: " + inPacks.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddToPack) { Text("Add to pack") }
                TextButton({ library.setFavorite(item.id, !item.favorite); onDismiss() }) { Text(if (item.favorite) "Unfavorite" else "★ Favorite") }
                TextButton({
                    library.deleteStickers(listOf(item.id)); onDismiss()
                    scope.notify(snackbar, "Sticker deleted") { library.undoDelete(listOf(item.id)) }
                }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun AddToPackDialog(app: AppModel, stickerIds: List<String>, onDone: (String?) -> Unit) {
    val library = app.library
    val stickers = remember(stickerIds) { stickerIds.mapNotNull(library::sticker) }
    val kinds = stickers.map { it.kind }.toSet()
    val packs = remember { library.packs(includeAdult = true, vault = app.vaultOpen).filter { it.kind in kinds } }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Add ${stickers.size} to a pack") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("New pack name") }, placeholder = { Text("e.g. Office pain") })
                if (packs.isNotEmpty()) Text("Or choose a pack", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.fillMaxWidth().size(width = 400.dp, height = 260.dp)) {
                    items(packs, key = { it.id }) { pack ->
                        ListItem(headlineContent = { Text(pack.name) },
                            supportingContent = { Text("${pack.count}/30${if (pack.kind == StickerKind.ANIMATED) " · animated" else ""}") },
                            modifier = Modifier.clickableRow {
                                val r = library.addToPack(pack.id, stickers.filter { it.kind == pack.kind }.map { it.id })
                                onDone("Added ${r.added.size} to ${pack.name}" + if (r.skipped.isNotEmpty()) " · ${r.skipped.size} skipped (${r.skipped.values.first()})" else "")
                            })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                val created = library.autoPack(stickers.map { it.id }, newName.trim(), origin = "USER")
                onDone("Created ${created.size} pack(s)")
            }) { Text("Create pack") }
        },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } },
    )
}
