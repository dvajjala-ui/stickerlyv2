package app.stickerdock.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.stickerdock.library.DuplicateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Near-identical stickers already in the library (imports skip new ones), merged without losing pack places. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(app: AppModel, onBack: () -> Unit) {
    val library = app.library
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var keepers by remember { mutableStateOf(emptyMap<String, String>()) } // group's suggested keeper id → chosen id
    var scan by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(scan, app.adultEnabled, app.vaultOpen) {
        groups = withContext(Dispatchers.Default) {
            app.host.backfillHashes() // stickers saved before hashing existed
            library.findDuplicates(includeAdult = app.adultEnabled, vault = app.vaultOpen)
        }
        keepers = emptyMap()
    }

    fun merge(targets: List<DuplicateGroup>) {
        if (working) return
        working = true
        val plan = targets.map { group ->
            val keep = keepers[group.keeper.id] ?: group.keeper.id
            keep to (listOf(group.keeper) + group.others).map { it.id }.filter { it != keep }
        }
        scope.launch {
            val results = withContext(Dispatchers.Default) { plan.map { (keep, copies) -> library.mergeDuplicates(keep, copies) } }
            working = false
            scan++
            val removed = results.sumOf { it.removed.size }
            val thin = results.flatMap { it.thinPacks }.toSet().size
            scope.notify(snackbar, "Merged $removed cop${if (removed == 1) "y" else "ies"}" +
                if (thin > 0) " · $thin pack(s) now under 3 stickers, so WhatsApp hides them" else "") {
                scope.launch { withContext(Dispatchers.Default) { results.asReversed().forEach(library::undoMerge) }; scan++ }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Duplicates") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { inner ->
        val list = groups
        when {
            list == null -> Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Comparing your stickers…")
                }
            }
            list.isEmpty() -> Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No duplicates", "Every sticker here is unique. Imports skip near-identical copies automatically.")
            }
            else -> LazyColumn(Modifier.padding(inner).fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${list.size} group(s) · ${list.sumOf { it.others.size }} extra copies",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Merging keeps the highlighted sticker — tap another to keep that one instead. It takes the " +
                            "copies' places in your packs (WhatsApp updates by itself), their favourite and their words.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button({ merge(list) }, enabled = !working) { Text("Merge all") }
                    }
                }
                itemsIndexed(list, key = { _, g -> g.keeper.id }) { _, group ->
                    val members = listOf(group.keeper) + group.others
                    val keep = keepers[group.keeper.id] ?: group.keeper.id
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(members, key = { it.id }) { item ->
                                    StickerTile(app.model(item), item.title, Modifier.size(84.dp), selected = item.id == keep,
                                        favorite = item.favorite, animated = item.kind == StickerKind.ANIMATED,
                                        onClick = { keepers = keepers + (group.keeper.id to item.id) })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${members.size} copies of “${members.first { it.id == keep }.title}”",
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                TextButton({ merge(listOf(group)) }, enabled = !working) { Text("Merge") }
                            }
                        }
                    }
                }
            }
        }
    }
}
