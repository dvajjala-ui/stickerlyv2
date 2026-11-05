package app.stickerdock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/** A keyboard tab: fixed ones show [symbol]; pack tabs show their cover [icon]. */
data class PanelTab(val key: String, val title: String, val symbol: String? = null, val icon: Any? = null)

data class PanelSticker(val id: String, val label: String, val model: Any, val animated: Boolean)

private object Keys {
    val Background = Color(0xFF12130F)
    val Surface = Color(0xFF2A2B26)
    val Text = Color(0xFFF7F6F1)
    val Muted = Color(0xFFB9B8B0)
    val Accent = Color(0xFFD9FF6A)
}

/**
 * The sticker keyboard's panel, shared so it can be reviewed without a phone (the IME service supplies data and
 * sends stickers). Tap sends; long-press previews first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardPanel(
    tabs: List<PanelTab>,
    selectedKey: String,
    stickers: List<PanelSticker>,
    searching: Boolean,
    query: String,
    onSelectTab: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onSend: (PanelSticker) -> Unit,
    onSwitchKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here yet",
) {
    val haptics = LocalHapticFeedback.current
    var preview by remember { mutableStateOf<PanelSticker?>(null) }
    val send = { s: PanelSticker -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSend(s) }
    val tile = { s: PanelSticker, size: Int ->
        Modifier.padding(4.dp).size(size.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { send(s) }, onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress); preview = s
            })
    }
    Box(modifier.fillMaxWidth().background(Keys.Background)) {
        Column(Modifier.fillMaxSize()) {
            if (searching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (query.isEmpty()) "Search stickers…" else query, Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(Keys.Surface).padding(horizontal = 14.dp, vertical = 7.dp),
                        color = if (query.isEmpty()) Color(0xFF8E8D86) else Keys.Text, fontSize = 14.sp, maxLines = 1)
                    Chip("✕", Modifier.padding(start = 6.dp)) { onSearching(false); onQuery("") }
                }
                LazyRow(Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 4.dp)) {
                    items(stickers, key = { it.id }) { s ->
                        AsyncImage(s.model, s.label, tile(s, 76), contentScale = ContentScale.Fit)
                    }
                }
                LetterPad(onKey = { onQuery((query + it).take(30)) }, onBackspace = { onQuery(query.dropLast(1)) },
                    onSpace = { if (query.isNotEmpty() && !query.endsWith(' ')) onQuery("$query ") })
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("🔍", Modifier.padding(end = 6.dp)) { onSearching(true) }
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        items(tabs, key = { it.key }) { tab -> TabButton(tab, tab.key == selectedKey) { onSelectTab(tab.key) } }
                    }
                    Chip("ABC", Modifier.padding(start = 6.dp), bold = true, onTap = onSwitchKeyboard)
                }
                val current = tabs.firstOrNull { it.key == selectedKey }
                if (current?.icon != null) Text("${current.title} · ${stickers.size}", Modifier.padding(start = 12.dp, bottom = 2.dp),
                    color = Keys.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (stickers.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyText, color = Keys.Muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                } else LazyVerticalGrid(GridCells.Adaptive(74.dp), Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    items(stickers, key = { it.id }) { s -> AsyncImage(s.model, s.label, tile(s, 74), contentScale = ContentScale.Fit) }
                }
            }
        }
        preview?.let { s ->
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { preview = null }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(s.model, s.label, Modifier.size(170.dp), contentScale = ContentScale.Fit)
                    if (s.label.isNotBlank()) Text(s.label, color = Keys.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Send", Modifier.clip(RoundedCornerShape(50)).background(Keys.Accent)
                        .clickable { preview = null; send(s) }.padding(horizontal = 28.dp, vertical = 8.dp),
                        color = Keys.Background, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TabButton(tab: PanelTab, active: Boolean, onTap: () -> Unit) {
    if (tab.icon != null) {
        // Pack tab: its cover, like WhatsApp's own sticker tray.
        Box(Modifier.size(38.dp).clip(CircleShape).background(Keys.Surface)
            .border(2.dp, if (active) Keys.Accent else Color.Transparent, CircleShape).clickable(onClick = onTap),
            contentAlignment = Alignment.Center) {
            AsyncImage(tab.icon, tab.title, Modifier.size(30.dp), contentScale = ContentScale.Fit)
        }
    } else {
        Text(tab.symbol ?: tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Keys.Background else Keys.Text,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) Keys.Accent else Keys.Surface)
                .clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun Chip(label: String, modifier: Modifier = Modifier, bold: Boolean = false, onTap: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Keys.Surface).clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = Keys.Text, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Compact letter pad for searching inside the sticker keyboard (works where the host field has no text). */
@Composable
private fun LetterPad(onKey: (String) -> Unit, onBackspace: () -> Unit, onSpace: () -> Unit) {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                row.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { onKey(c.toString()) } }
                if (index == 2) Key("⌫", Modifier.weight(1.5f), onBackspace)
            }
        }
        Row(Modifier.fillMaxWidth()) { Key("space", Modifier.weight(1f), onSpace) }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onTap: () -> Unit) {
    Box(modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(Keys.Surface).clickable(onClick = onTap),
        contentAlignment = Alignment.Center) {
        Text(label, color = Keys.Text, fontSize = 15.sp)
    }
}
