package app.stickerdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stickerdock.discover.RemotePack
import app.stickerdock.discover.RemoteSticker
import app.stickerdock.library.AddResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CATEGORIES = listOf(
    null to "Trending", "reactions" to "Reactions", "funny" to "Funny", "hinglish" to "Hinglish", "movies" to "Movies",
    "relationship" to "Relationship", "office" to "Office", "festivals" to "Festivals", "animals" to "Animals",
    "baby" to "Cute", "dark" to "Dark", "adult" to "18+",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(app: AppModel, modifier: Modifier, onOpenPack: (String) -> Unit, scope: CoroutineScope) {
    val client = app.discover
    val snackbar = LocalSnackbar.current
    if (!client.configured) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Discover is warming up",
                "Fresh AI-made reaction stickers and packs appear here once the StickerDock feed is connected. " +
                    "Everything else works offline.")
        }
        return
    }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var stickers by remember { mutableStateOf(emptyList<RemoteSticker>()) }
    var packs by remember { mutableStateOf(emptyList<RemotePack>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var openSticker by remember { mutableStateOf<RemoteSticker?>(null) }
    var openPack by remember { mutableStateOf<RemotePack?>(null) }
    var askAge by remember { mutableStateOf(false) }
    val adult = app.adultEnabled

    LaunchedEffect(category, query, adult) {
        if (query.isNotBlank()) delay(350)
        error = null
        runCatching {
            stickers = if (query.isNotBlank()) client.search(query, adult) else client.feed(adult || category == "adult", category)
            packs = if (query.isBlank() && category == null) client.packs(adult) else emptyList()
        }.onFailure { error = "Couldn't load Discover. Check your connection." }
    }

    LazyVerticalGrid(GridCells.Adaptive(96.dp), modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Discover", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search reactions, moods, moments…") })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CATEGORIES.filter { it.first != "adult" || adult || true }) { (key, label) ->
                        FilterChip(category == key, {
                            if (key == "adult" && !adult) askAge = true else category = key
                        }, { Text(label) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (packs.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionTitle("Packs", Modifier.padding(start = 0.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(packs, key = { it.id }) { pack ->
                        Card(onClick = { openPack = pack }, modifier = Modifier.size(width = 150.dp, height = 190.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                StickerTile(pack.coverPath?.let(client::imageUrl) ?: "", pack.title, Modifier.fillMaxWidth(),
                                    animated = pack.animated, blurred = pack.adult && !adult, onClick = { openPack = pack })
                                Text(pack.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${pack.stickerCount} stickers", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                SectionTitle("Stickers", Modifier.padding(start = 0.dp, top = 8.dp))
            }
        }
        items(stickers, key = { it.id }) { sticker ->
            StickerTile(client.imageUrl(sticker.filePath), sticker.caption ?: "Sticker", animated = sticker.animated,
                blurred = sticker.adult && !adult, onClick = { if (sticker.adult && !adult) askAge = true else openSticker = sticker })
        }
    }

    openSticker?.let { sticker ->
        ModalBottomSheet(onDismissRequest = { openSticker = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    StickerTile(client.imageUrl(sticker.filePath), sticker.caption ?: "", Modifier.size(220.dp), animated = sticker.animated, onClick = {})
                }
                sticker.caption?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                if (sticker.tags.isNotEmpty()) Text(sticker.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Text(buildString {
                    if (sticker.aiGenerated) append("AI-generated · ")
                    sticker.credit?.get("site")?.let { append("Source: ").append(it.toString().trim('"')) }
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        openSticker = null
                        scope.launch {
                            val result = runCatching { app.saver.saveSticker(sticker) }
                            scope.notify(snackbar, result.fold({
                                when (it) { is AddResult.Rejected -> it.reason; is AddResult.Duplicate -> "Already in your library"; else -> "Saved to My stickers" }
                            }, { "Couldn't save: ${it.message}" }))
                        }
                    }) { Text("Save") }
                    ReportButton(app, "sticker", sticker.id, scope) { openSticker = null }
                }
            }
        }
    }
    openPack?.let { pack ->
        PackPreviewSheet(app, pack, onDismiss = { openPack = null }, onSaved = { localId -> openPack = null; onOpenPack(localId) }, scope = scope)
    }
    if (askAge) AgeGateDialog(onConfirm = { app.setAdult(true); askAge = false; category = "adult" }, onDismiss = { askAge = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackPreviewSheet(app: AppModel, pack: RemotePack, onDismiss: () -> Unit, onSaved: (String) -> Unit, scope: CoroutineScope) {
    val client = app.discover
    val snackbar = LocalSnackbar.current
    var items by remember { mutableStateOf(emptyList<RemoteSticker>()) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(pack.id) { items = runCatching { client.packStickers(pack.id) }.getOrDefault(emptyList()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pack.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            pack.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        runCatching { app.saver.savePack(pack) }
                            .onSuccess(onSaved)
                            .onFailure { scope.notify(snackbar, "Couldn't save the pack: ${it.message}"); saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Save & use in WhatsApp") }
                ReportButton(app, "pack", pack.id, scope, onDismiss)
            }
            Text("Saved packs follow updates: new stickers arrive in WhatsApp automatically.", style = MaterialTheme.typography.bodySmall)
            LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxWidth().heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.id }) { s -> StickerTile(client.imageUrl(s.filePath), s.caption ?: "", animated = s.animated, onClick = {}) }
            }
        }
    }
}

@Composable
private fun ReportButton(app: AppModel, type: String, id: String, scope: CoroutineScope, onDone: () -> Unit) {
    val snackbar = LocalSnackbar.current
    var open by remember { mutableStateOf(false) }
    TextButton({ open = true }) { Text("Report") }
    if (open) {
        val reasons = listOf("copyright" to "It's my content / copyright", "person" to "It shows me or someone without consent",
            "hate" to "Hateful or abusive", "sexual" to "Sexual or explicit", "minor" to "Involves a child", "other" to "Something else")
        AlertDialog({ open = false }, title = { Text("Report this $type") },
            text = {
                Column {
                    reasons.forEach { (key, label) ->
                        TextButton({
                            open = false; onDone()
                            scope.launch {
                                val ok = runCatching { app.discover.report(type, id, key, "") }.isSuccess
                                scope.notify(snackbar, if (ok) "Thanks — we'll review it quickly" else "Couldn't send the report")
                            }
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton({ open = false }) { Text("Cancel") } })
    }
}

@Composable
fun AgeGateDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismiss,
        title = { Text("18+ stickers") },
        text = {
            Text("This section has adult humor — double meanings, savage and dark jokes. No nudity. " +
                "Confirm you are 18 or older to see it. You can turn it off any time in Settings.")
        },
        confirmButton = { TextButton(onConfirm) { Text("I'm 18 or older") } },
        dismissButton = { TextButton(onDismiss) { Text("Not now") } })
}
