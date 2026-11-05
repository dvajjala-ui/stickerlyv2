package app.stickerdock.android.create

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stickerdock.android.AppGraph
import app.stickerdock.android.media.StickerNormalizer
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.library.AddResult
import app.stickerdock.library.Origin
import app.stickerdock.ui.StickerDockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Photo → sticker: optional background removal, white outline, outlined caption, pinch/drag to place. */
class CreateStickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data ?: run { finish(); return }
        setContent { StickerDockTheme { CreateScreen(uri) } }
    }

    @Composable
    private fun CreateScreen(uri: Uri) {
        val graph = remember { AppGraph.get(this) }
        val scope = rememberCoroutineScope()
        var source by remember { mutableStateOf<Bitmap?>(null) }
        var cutout by remember { mutableStateOf<Bitmap?>(null) }
        var useCutout by remember { mutableStateOf(false) }
        var cutoutBusy by remember { mutableStateOf(false) }
        var recipe by remember { mutableStateOf(CreateRecipe()) }
        var preview by remember { mutableStateOf<Bitmap?>(null) }
        var saving by remember { mutableStateOf(false) }
        var packMenu by remember { mutableStateOf(false) }
        val packs = remember { graph.library.packs(includeAdult = true).filter { it.kind == StickerKind.STATIC && it.count < StickerRules.MAX_PACK } }
        var targetPack by remember { mutableStateOf(packs.firstOrNull { it.origin == "CREATED" }) }

        LaunchedEffect(uri) {
            source = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { StickerNormalizer.decode(it, 1024) }
            }
            if (source == null) { Toast.makeText(this@CreateStickerActivity, "Couldn't open that image", Toast.LENGTH_LONG).show(); finish() }
        }
        val subject = if (useCutout) cutout ?: source else source
        LaunchedEffect(subject, recipe) {
            val s = subject ?: return@LaunchedEffect
            delay(40)
            preview = withContext(Dispatchers.Default) { StickerComposer.render(s, recipe) }
        }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Make a sticker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            recipe = recipe.copy(zoom = (recipe.zoom * zoom).coerceIn(0.5f, 3f),
                                offsetX = (recipe.offsetX + pan.x / size.width).coerceIn(-0.5f, 0.5f),
                                offsetY = (recipe.offsetY + pan.y / size.height).coerceIn(-0.5f, 0.5f))
                        }
                    }, contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), "Sticker preview", Modifier.fillMaxSize()) }
                        ?: Text("Loading…")
                }
                Text("Pinch to zoom, drag to move", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cutoutBusy) "Removing background…" else "Remove background", Modifier.weight(1f))
                    Switch(useCutout, { on ->
                        useCutout = on
                        if (on && cutout == null && source != null) scope.launch {
                            cutoutBusy = true
                            cutout = runCatching { withContext(Dispatchers.Default) { Cutout.subject(source!!) } }
                                .onFailure { graph.events.record("create", "cutout failed: ${it.javaClass.name}: ${it.message}") }
                                .getOrNull()
                            cutoutBusy = false
                            if (cutout == null) {
                                useCutout = false
                                Toast.makeText(this@CreateStickerActivity, "Couldn't find a subject — try another photo", Toast.LENGTH_LONG).show()
                            }
                        }
                    }, enabled = !cutoutBusy)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("White outline", Modifier.weight(1f))
                    Switch(recipe.outline, { recipe = recipe.copy(outline = it) })
                }
                OutlinedTextField(recipe.caption, { recipe = recipe.copy(caption = it.take(80)) }, Modifier.fillMaxWidth(),
                    label = { Text("Caption (Hinglish welcome)") }, singleLine = false, maxLines = 3)
                if (recipe.caption.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(recipe.captionAtTop, { recipe = recipe.copy(captionAtTop = true) }, { Text("Top") })
                        FilterChip(!recipe.captionAtTop, { recipe = recipe.copy(captionAtTop = false) }, { Text("Bottom") })
                        Slider(recipe.captionScale, { recipe = recipe.copy(captionScale = it) }, Modifier.weight(1f), valueRange = 0.6f..1.6f)
                    }
                }
                Box {
                    OutlinedButton({ packMenu = true }, Modifier.fillMaxWidth()) {
                        Text("Save to: " + (targetPack?.name ?: "New pack “My creations”"))
                    }
                    DropdownMenu(packMenu, { packMenu = false }) {
                        DropdownMenuItem({ Text("New pack “My creations”") }, { targetPack = null; packMenu = false })
                        packs.forEach { p -> DropdownMenuItem({ Text("${p.name} (${p.count}/30)") }, { targetPack = p; packMenu = false }) }
                    }
                }
                Button(enabled = preview != null && !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                    saving = true
                    scope.launch {
                        val message = withContext(Dispatchers.IO) { save(subject!!, recipe, targetPack?.id) }
                        Toast.makeText(this@CreateStickerActivity, message, Toast.LENGTH_LONG).show()
                        saving = false
                        if (message.startsWith("Saved")) finish()
                    }
                }) { Text(if (saving) "Saving…" else "Save sticker") }
            }
        }
    }

    private fun save(subject: Bitmap, recipe: CreateRecipe, packId: String?): String {
        val library = AppGraph.get(this).library
        val rendered = StickerComposer.render(subject, recipe)
        val bytes = try { StickerNormalizer.encodeStatic(rendered) } finally { rendered.recycle() } ?: return "Couldn't fit under 100 KB"
        val tags = recipe.caption.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.joinToString(" ")
        val result = library.addSticker(bytes, Origin.CREATE, originRef = "Created", label = recipe.caption, tags = tags,
            dhash = StickerNormalizer.dhash(bytes))
        val id = when (result) {
            is AddResult.Added -> result.id
            is AddResult.Duplicate -> return "You already have this sticker"
            is AddResult.Rejected -> return result.reason
        }
        val target = packId ?: library.packs(includeAdult = true).firstOrNull { it.origin == "CREATED" && it.kind == StickerKind.STATIC && it.count < 30 }?.id
            ?: library.createPack("My creations", StickerKind.STATIC, origin = "CREATED")
        library.addToPack(target, listOf(id))
        return "Saved to ${library.pack(target)?.name}"
    }

    companion object {
        fun start(context: Context, image: Uri) = context.startActivity(
            Intent(context, CreateStickerActivity::class.java).setData(image).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
