package app.stickerdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppModel, onBack: () -> Unit, onOpenDuplicates: () -> Unit = {}) {
    val host = app.host
    var askAge by remember { mutableStateOf(false) }
    var askVault by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingRow("18+ stickers", "Adult humor in Discover and your library. No nudity.", app.adultEnabled) { on ->
                        if (on) askAge = true else app.setAdult(false)
                    }
                    HorizontalDivider()
                    SettingRow("Private vault", "Show vault stickers. Vault packs never sync or appear in Discover.", app.vaultOpen) { on ->
                        if (on) askVault = true else app.vaultOpen = false
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Clean up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Find near-identical stickers (the same picture saved twice, from both WhatsApps or old imports) " +
                        "and merge them without losing pack places.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onOpenDuplicates) { Text("Find duplicates") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Save your whole library (stickers, packs, names and tags) as one ZIP. The vault stays out.",
                        style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(host::exportBackup) { Text("Export backup") }; TextButton(host::importArchives) { Text("Restore backup") } }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Keyboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row { TextButton(host::openKeyboardSettings) { Text("Keyboard settings") }; TextButton(host::openKeyboardTest) { Text("Test field") } }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("What StickerDock sees on this phone: WhatsApp installs, profiles, keyboard, recent integration events.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton({ diagnostics = host.diagnostics() }) { Text("Show diagnostics") }
                }
            }
            Text("StickerDock ${host.appVersion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (askAge) AgeGateDialog(onConfirm = { app.setAdult(true); askAge = false }, onDismiss = { askAge = false })
    if (askVault) AlertDialog({ askVault = false }, title = { Text("Open private vault?") },
        text = { Text("Vault stickers can be explicit — they're yours, stay on this phone, and are never shared or synced.") },
        confirmButton = { TextButton({ app.vaultOpen = true; askVault = false }) { Text("Open vault") } },
        dismissButton = { TextButton({ askVault = false }) { Text("Cancel") } })
    diagnostics?.let { text ->
        AlertDialog({ diagnostics = null }, title = { Text("Diagnostics") },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton({ clipboard.setText(AnnotatedString(text)); diagnostics = null }) { Text("Copy") } },
            dismissButton = { TextButton({ diagnostics = null }) { Text("Close") } })
    }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
