package app.stickerdock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stickerdock.core.Rating
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.host.TargetKind
import app.stickerdock.host.WhatsAppState
import app.stickerdock.library.PackInfo
import coil3.compose.AsyncImage

/**
 * One place to see every WhatsApp on the phone, what is added where, and the two ways to reach
 * accounts the pack API can't target directly (keyboard, Dual Messenger companion).
 */
@Composable
fun WhatsAppScreen(app: AppModel, modifier: Modifier, onOpenPack: (String) -> Unit, onOpenSettings: () -> Unit) {
    val host = app.host
    val state by host.whatsApp.collectAsState()
    val packs by remember(app.adultEnabled) { app.library.packsFlow(app.adultEnabled) }.collectAsState(emptyList())
    val shelf by remember { app.library.shelfFlow() }.collectAsState(emptyList())
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

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
        // The shelf: packs StickerDock offers to WhatsApp. Private ones (vault, or 18+ while hidden) are only counted.
        val shown = { p: PackInfo -> (!p.vault || app.vaultOpen) && (p.rating == Rating.GENERAL || app.adultEnabled) }
        val offered = shelf.count { it.whatsAppReady }
        val limit = host.shelfGuideline
        if (shelf.isNotEmpty()) item {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Offered to WhatsApp", Modifier.weight(1f))
                if (limit != null) Text("$offered of $limit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = if (offered > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (limit != null && offered > limit) Card(Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("WhatsApp's guidelines allow $limit packs per app, so packs past the ${limit}th may not show up there. " +
                    "Remove packs you rarely use, or send them with the StickerDock keyboard — it has no pack limit.",
                    Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        items(shelf.filter(shown), key = { "shelf-${it.id}" }) { pack ->
            PackTargetsRow(app, pack, state, onOpenPack, onRemove = {
                app.library.setShelf(pack.id, false)
                scope.notify(snackbar, "“${pack.name}” is no longer offered to WhatsApp. If WhatsApp still lists it, delete it there.") {
                    app.library.setShelf(pack.id, true)
                }
            })
        }
        val private = shelf.count { !shown(it) }
        if (private > 0) item {
            Text("+ $private private pack(s) — open the vault or turn on 18+ to see them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val ready = packs.filter { it.whatsAppReady && !it.onShelf }
        if (ready.isNotEmpty()) item { SectionTitle("Ready to add", Modifier.padding(top = 4.dp)) }
        items(ready, key = { it.id }) { pack -> PackTargetsRow(app, pack, state, onOpenPack, onRemove = null) }
        item {
            TextButton(onOpenSettings) { Text("Diagnostics and settings") }
        }
    }
}

/** A pack with one add button or ✓ per WhatsApp; [onRemove] takes it off the shelf. */
@Composable
private fun PackTargetsRow(app: AppModel, pack: PackInfo, state: WhatsAppState, onOpenPack: (String) -> Unit, onRemove: (() -> Unit)?) {
    val addedTo = state.added[pack.id].orEmpty()
    val cover = remember(pack.trayStickerId, pack.dataVersion) { pack.trayStickerId?.let(app.library::sticker) }
    Card(onClick = { onOpenPack(pack.id) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                if (cover != null) AsyncImage(app.model(cover), null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(pack.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    !pack.whatsAppReady -> "Hidden from WhatsApp — needs ${StickerRules.MIN_PACK}–${StickerRules.MAX_PACK} stickers (has ${pack.count})"
                    else -> "${pack.count} stickers" + (if (pack.kind == StickerKind.ANIMATED) " · animated" else "") +
                        (if (pack.rating == Rating.ADULT) " · 18+" else "") + (if (pack.vault) " · vault" else "")
                }, style = MaterialTheme.typography.bodySmall,
                    color = if (pack.whatsAppReady) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (pack.whatsAppReady) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.targets.filter { it.ready }.forEach { t ->
                        val shortName = when (t.kind) { TargetKind.WHATSAPP -> "WhatsApp"; TargetKind.BUSINESS -> "Business"; TargetKind.CLONE -> "Dual" }
                        if (t.id in addedTo) Badge("$shortName ✓", Brand.Volt, Brand.Ink)
                        else TextButton({ app.host.addToWhatsApp(pack.id, t.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("+ $shortName")
                        }
                    }
                }
            }
            if (onRemove != null) IconButton(onRemove) { Icon(Icons.Filled.Close, "Stop offering to WhatsApp") }
        }
    }
}

private fun setupCommands(appId: String, user: Int) = listOf(
    "adb shell pm install-existing --user $user $appId",
    "adb shell pm grant $appId android.permission.INTERACT_ACROSS_USERS",
    "adb shell pm grant --user $user $appId android.permission.INTERACT_ACROSS_USERS",
).joinToString("\n")
