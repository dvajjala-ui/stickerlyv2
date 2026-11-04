package app.stickerdock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stickerdock.core.Rating
import app.stickerdock.core.StickerKind
import app.stickerdock.host.ImportProgress
import app.stickerdock.library.PackInfo
import app.stickerdock.library.StickerItem
import coil3.compose.AsyncImage

/** Image model for a local sticker file (Coil understands file:// URIs on every platform). */
fun localModel(path: String) = "file://$path"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerTile(
    model: Any,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    favorite: Boolean = false,
    animated: Boolean = false,
    blurred: Boolean = false,
    cover: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = model, contentDescription = description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp).then(if (blurred) Modifier.blur(18.dp) else Modifier),
        )
        if (blurred) Text("18+", Modifier.align(Alignment.Center).clip(CircleShape).background(Brand.Adult)
            .padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (cover) Badge("Cover", Brand.Volt, Brand.Ink)
            if (animated) Badge("GIF")
            if (favorite) Badge("★")
        }
        if (selected) Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(20.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Badge(text: String, color: Color = Brand.Ink.copy(alpha = 0.72f), textColor: Color = Brand.Paper) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
        color = textColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun PackCard(
    pack: PackInfo,
    previews: List<StickerItem>,
    modelFor: (StickerItem) -> Any,
    addedTo: List<String>,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 4).forEach { index ->
                    val item = previews.getOrNull(index)
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        if (item != null) AsyncImage(modelFor(item), item.title, Modifier.fillMaxSize().padding(4.dp),
                            contentScale = ContentScale.Fit)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(buildString {
                        append("${pack.count} stickers")
                        if (pack.kind == StickerKind.ANIMATED) append(" · animated")
                        if (pack.rating == Rating.ADULT) append(" · 18+")
                        if (pack.vault) append(" · vault")
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (addedTo.isNotEmpty()) Badge("In ${addedTo.joinToString(" · ")}", Brand.Volt, Brand.Ink)
                else if (!pack.whatsAppReady) Badge(if (pack.count < 3) "Needs 3+" else "Too many", Brand.Mist, Brand.Ink)
            }
        }
    }
}

@Composable
fun ImportBanner(progress: ImportProgress, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (progress.running) "Importing stickers…" else progress.label, Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (progress.running) TextButton(onCancel) { Text("Pause") } else TextButton(onDismiss) { Text("OK") }
            }
            if (progress.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${progress.added} added · ${progress.duplicates} already saved · ${progress.rejected} skipped",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, primary: Pair<String, () -> Unit>? = null, secondary: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Brand.Volt), contentAlignment = Alignment.Center) {
            Text("☺", fontSize = 40.sp, color = Brand.Ink)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        primary?.let { (label, action) -> Button(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        secondary?.let { (label, action) -> OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(label) } }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
