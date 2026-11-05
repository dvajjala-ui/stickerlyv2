package app.stickerdock.android.create

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stickerdock.android.AppGraph
import app.stickerdock.android.media.AnimatedReencoder
import app.stickerdock.android.media.StickerNormalizer
import app.stickerdock.core.StickerKind
import app.stickerdock.library.AddResult
import app.stickerdock.library.Origin
import app.stickerdock.ui.StickerDockTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Video or GIF section → animated sticker (10 fps, ≤3 s) with an optional caption on every frame. */
class CreateVideoStickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent.data ?: run { finish(); return }
        setContent { StickerDockTheme { Screen(uri) } }
    }

    @Composable
    private fun Screen(uri: Uri) {
        val scope = rememberCoroutineScope()
        var durationMs by remember { mutableLongStateOf(0L) }
        var startMs by remember { mutableLongStateOf(0L) }
        var lengthMs by remember { mutableLongStateOf(2_000L) }
        var caption by remember { mutableStateOf("") }
        var poster by remember { mutableStateOf<Bitmap?>(null) }
        var result by remember { mutableStateOf<ByteArray?>(null) }
        var busy by remember { mutableStateOf(false) }

        LaunchedEffect(uri) {
            durationMs = withContext(Dispatchers.IO) {
                runCatching { retrieve(uri) { r -> r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } }.getOrDefault(0L)
            }
            if (durationMs <= 0) { Toast.makeText(this@CreateVideoStickerActivity, "Couldn't read that video", Toast.LENGTH_LONG).show(); finish() }
            lengthMs = minOf(lengthMs, durationMs)
        }
        LaunchedEffect(startMs, durationMs) {
            if (durationMs <= 0) return@LaunchedEffect
            delay(120)
            poster = withContext(Dispatchers.IO) {
                runCatching { retrieve(uri) { r -> r.getScaledFrameAtTime(startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 512, 512) } }.getOrNull()
            }
            result = null
        }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Make an animated sticker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    when {
                        result != null -> AsyncImage(result, "Animated sticker preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        poster != null -> Image(poster!!.asImageBitmap(), "Start frame", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else -> Text("Loading…")
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Start at %.1f s of %.1f s".format(startMs / 1000f, durationMs / 1000f), style = MaterialTheme.typography.bodyMedium)
                Slider(startMs.toFloat(), { startMs = it.toLong(); result = null }, valueRange = 0f..maxOf(0f, (durationMs - lengthMs).toFloat()))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Whole-second options that fit, plus the full clip when it's 3 s or shorter.
                    val options = (listOf(1_000L, 2_000L, 3_000L).filter { it < durationMs } +
                        listOfNotNull(durationMs.takeIf { it in 1..3_000 })).distinct()
                    options.forEach { len ->
                        FilterChip(lengthMs == len, { lengthMs = len; startMs = minOf(startMs, maxOf(0, durationMs - len)); result = null },
                            { Text(if (len % 1000 == 0L) "${len / 1000} s" else "%.1f s".format(len / 1000f)) })
                    }
                }
                OutlinedTextField(caption, { caption = it.take(60); result = null }, Modifier.fillMaxWidth(), label = { Text("Caption (optional)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            result = withContext(Dispatchers.Default) { AnimatedReencoder.fromVideo(this@CreateVideoStickerActivity, uri, startMs, lengthMs, caption) }
                            busy = false
                            if (result == null) Toast.makeText(this@CreateVideoStickerActivity, "Couldn't make this clip fit — try a shorter one", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Preview") }
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val bytes = result ?: withContext(Dispatchers.Default) {
                                AnimatedReencoder.fromVideo(this@CreateVideoStickerActivity, uri, startMs, lengthMs, caption)
                            }
                            val message = if (bytes == null) "Couldn't make this clip fit — try a shorter one"
                                else withContext(Dispatchers.IO) { save(bytes, caption) }
                            busy = false
                            Toast.makeText(this@CreateVideoStickerActivity, message, Toast.LENGTH_LONG).show()
                            if (message.startsWith("Saved")) finish()
                        }
                    }) { Text("Save sticker") }
                }
            }
        }
    }

    /** MediaMetadataRetriever is only AutoCloseable from API 29; release explicitly for API 28. */
    private fun <T> retrieve(uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            return block(r)
        } finally {
            r.release()
        }
    }

    private fun save(bytes: ByteArray, caption: String): String {
        val library = AppGraph.get(this).library
        val tags = caption.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.joinToString(" ")
        val id = when (val r = library.addSticker(bytes, Origin.CREATE, originRef = "Created", label = caption, tags = tags,
            dhash = StickerNormalizer.dhash(bytes))) {
            is AddResult.Added -> r.id
            is AddResult.Duplicate -> return "You already have this sticker"
            is AddResult.Rejected -> return r.reason
        }
        val pack = library.packs(includeAdult = true).firstOrNull { it.origin == "CREATED" && it.kind == StickerKind.ANIMATED && it.count < 30 }?.id
            ?: library.createPack("My animated creations", StickerKind.ANIMATED, origin = "CREATED")
        library.addToPack(pack, listOf(id))
        return "Saved to ${library.pack(pack)?.name}"
    }

    companion object {
        fun start(context: Context, video: Uri) = context.startActivity(
            Intent(context, CreateVideoStickerActivity::class.java).setData(video).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
