package app.stickerdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stickerdock.host.TargetKind

/**
 * One place to see every WhatsApp on the phone, what is added where, and the two ways to reach
 * accounts the pack API can't target directly (keyboard, Dual Messenger companion).
 */
@Composable
fun WhatsAppScreen(app: AppModel, modifier: Modifier, onOpenPack: (String) -> Unit, onOpenSettings: () -> Unit) {
    val host = app.host
    val state by host.whatsApp.collectAsState()
    val packs by remember(app.adultEnabled) { app.library.packsFlow(app.adultEnabled) }.collectAsState(emptyList())
    val clipboard = LocalClipboardManager.current

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WhatsApp", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(host::refreshWhatsApp) { Text("Refresh") }
            }
            Text("Add a pack once per WhatsApp. After that, edits in StickerDock update WhatsApp by themselves.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("On this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (state.targets.isEmpty() && !state.loading) Text("No WhatsApp or WhatsApp Business found.")
                    state.targets.forEach { target ->
                        val count = state.added.values.count { target.id in it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(target.label, fontWeight = FontWeight.SemiBold)
                                Text(if (target.ready) "$count pack(s) added" else target.setupHint ?: "Setup needed",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Badge(if (target.ready) "Ready" else "Setup", if (target.ready) Brand.Volt else Brand.Mist, Brand.Ink)
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("StickerDock keyboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Send any sticker from your whole library — in every WhatsApp account, Business, Dual Messenger and other apps. " +
                        "No packs to add, and it always shows your latest edits.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.keyboardEnabled) Button(host::openKeyboardSettings) { Text("Turn on") }
                        else OutlinedButton(host::pickKeyboard) { Text(if (state.keyboardSelected) "In use ✓" else "Switch to it") }
                        TextButton(host::openKeyboardTest) { Text("Try it") }
                    }
                }
            }
        }
        val dual = state.dual
        if (!state.companionMode && dual.cloneUserId != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dual Messenger", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (dual.companionInstalled && dual.crossUserAccess) {
                        Text("Connected. Use “WhatsApp · Dual Messenger” on any pack to add it to your second WhatsApp.")
                    } else {
                        Text("Your second WhatsApp runs in a separate Android profile (user ${dual.cloneUserId}), which normal apps can't reach. " +
                            "A one-time setup from a PC installs StickerDock inside it, linked to this library:")
                        val commands = remember(dual.cloneUserId) { setupCommands(host.appId, dual.cloneUserId) }
                        Text(commands, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ clipboard.setText(AnnotatedString(commands)); host.showMessage("Commands copied") }) { Text("Copy commands") }
                        }
                        Text("Until then, the StickerDock keyboard works in Dual Messenger once enabled there.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val ready = packs.filter { it.whatsAppReady }
        if (ready.isNotEmpty()) item { SectionTitle("Packs ready for WhatsApp", Modifier.padding(top = 4.dp)) }
        items(ready, key = { it.id }) { pack ->
            val addedTo = state.added[pack.id].orEmpty()
            Card(onClick = { onOpenPack(pack.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.name, fontWeight = FontWeight.SemiBold)
                        Text("${pack.count} stickers", style = MaterialTheme.typography.bodySmall)
                    }
                    state.targets.filter { it.ready }.forEach { t ->
                        val shortName = when (t.kind) { TargetKind.WHATSAPP -> "WA"; TargetKind.BUSINESS -> "Biz"; TargetKind.CLONE -> "Dual" }
                        if (t.id in addedTo) Badge("$shortName ✓", Brand.Volt, Brand.Ink)
                        else TextButton({ host.addToWhatsApp(pack.id, t.id) }) { Text("+ $shortName") }
                    }
                }
            }
        }
        item {
            TextButton(onOpenSettings) { Text("Diagnostics and settings") }
        }
    }
}

private fun setupCommands(appId: String, user: Int) = listOf(
    "adb shell pm install-existing --user $user $appId",
    "adb shell pm grant $appId android.permission.INTERACT_ACROSS_USERS",
    "adb shell pm grant --user $user $appId android.permission.INTERACT_ACROSS_USERS",
).joinToString("\n")
