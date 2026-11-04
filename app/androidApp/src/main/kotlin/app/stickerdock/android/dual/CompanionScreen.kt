package app.stickerdock.android.dual

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.stickerdock.android.AndroidHost
import app.stickerdock.android.AppGraph
import app.stickerdock.android.whatsapp.RemoteStickerSource
import app.stickerdock.android.whatsapp.ShelfPack
import app.stickerdock.android.whatsapp.WhatsAppDirectory
import app.stickerdock.ui.StickerDockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI of the StickerDock copy that lives inside Dual Messenger (or another clone/work profile).
 * It has no library of its own: packs come live from the main profile, and this copy hands
 * them to the WhatsApp installed in this profile.
 */
@Composable
fun CompanionScreen(host: AndroidHost) {
    val activity = LocalActivity.current as ComponentActivity
    val graph = remember { AppGraph.get(activity) }
    val directory = remember { WhatsAppDirectory(activity) }
    val source = remember { RemoteStickerSource(activity) }
    val hub = "content://${Dual.HUB_USER}@${activity.packageName}.sync"
    var packs by remember { mutableStateOf<List<ShelfPack>>(emptyList()) }
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<Dual.Pending>>(emptyList()) }
    var active by remember { mutableStateOf<Dual.Pending?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val item = active
        active = null
        if (item != null) {
            val ok = result.resultCode == Activity.RESULT_OK
            graph.events.record("companion", "add ${item.packId} result=${result.resultCode} ${result.data?.getStringExtra("validation_error") ?: ""}")
            runCatching {
                activity.contentResolver.insert(Uri.parse("$hub/status"), ContentValues().apply {
                    put("pack_id", item.packId); put("added", if (ok) 1 else 0)
                })
                activity.contentResolver.delete(Uri.parse("$hub/pending/${item.id}"), null, null)
            }
            queue = queue.drop(1)
        }
        refresh++
    }

    fun add(packId: String, name: String) {
        active = Dual.Pending("local", packId, name, "com.whatsapp")
        runCatching { launcher.launch(directory.enableIntent(packId, name, "com.whatsapp")) }
            .onFailure { active = null; host.showMessage("WhatsApp in this profile didn't open: ${it.message}") }
    }

    var importStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch(Dispatchers.IO) {
            importStatus = "Sending stickers to your main library…"
            importStatus = runCatching {
                CompanionImporter(activity).run(tree) { p ->
                    importStatus = if (p.done) p.summary ?: "Done" else "Sent ${p.sent} · skipped ${p.skipped}…"
                }.summary ?: "Done"
            }.getOrElse { "Import failed: ${it.message}" }
            refresh++
        }
    }

    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }

    LaunchedEffect(refresh) {
        val snapshot = withContext(Dispatchers.IO) {
            val shelf = source.shelf()
            val ok = runCatching { activity.contentResolver.query(Uri.parse("$hub/shelf"), null, null, null, null)?.use { true } ?: false }.getOrDefault(false)
            val pending = runCatching {
                activity.contentResolver.query(Uri.parse("$hub/pending"), null, null, null, null)?.use { c ->
                    buildList { while (c.moveToNext()) add(Dual.Pending(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
                }.orEmpty()
            }.getOrDefault(emptyList())
            val addedHere = shelf.filter { Dual.isPackAdded(activity, "com.whatsapp", Dual.myUserId(), it.id) == true }.map { it.id }.toSet()
            Triple(shelf to ok, pending, addedHere)
        }
        packs = snapshot.first.first
        reachable = snapshot.first.second
        queue = snapshot.second
        added = snapshot.third
    }

    // Requests queued by the main app ("Add to WhatsApp · Dual Messenger") run one at a time.
    LaunchedEffect(queue, active) {
        val next = queue.firstOrNull()
        if (active == null && next != null) {
            active = next
            runCatching { launcher.launch(directory.enableIntent(next.packId, next.packName, next.whatsAppPackage)) }
                .onFailure { active = null; host.showMessage("WhatsApp in this profile didn't open: ${it.message}") }
        }
    }

    StickerDockTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("StickerDock · Dual Messenger", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("This copy serves the WhatsApp in this profile from your main StickerDock library. " +
                    "Edit stickers in the main app — changes show up here automatically.",
                    style = MaterialTheme.typography.bodyMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val granted = Dual.crossUserGranted(activity)
                        Text(when {
                            !granted -> "Not connected: run the one-time setup command from your PC."
                            reachable == true -> "Connected to your main library"
                            reachable == false -> "Can't reach the main library. Open StickerDock in your main profile once."
                            else -> "Checking connection…"
                        }, fontWeight = FontWeight.SemiBold)
                        if (!granted) Text(Dual.setupCommands(activity.packageName, Dual.myUserId()), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Bring this WhatsApp's stickers over", fontWeight = FontWeight.SemiBold)
                        Text("Choose WhatsApp/Media/WhatsApp Stickers in this profile. They join your one main library.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            folderPicker.launch(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                                "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"))
                        }) { Text("Import stickers from this WhatsApp") }
                        importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text("Packs on your WhatsApp shelf", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(packs, key = { it.id }) { pack ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pack.name, fontWeight = FontWeight.SemiBold)
                                Text("${pack.count} stickers${if (pack.animated) " · animated" else ""}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (pack.id in added) OutlinedButton(onClick = { add(pack.id, pack.name) }) { Text("Added ✓") }
                            else Button(onClick = { add(pack.id, pack.name) }) { Text("Add") }
                        }
                    }
                }
            }
        }
    }
}
