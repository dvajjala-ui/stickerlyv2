package app.stickerdock.android.keyboard

import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.stickerdock.android.AppGraph
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sticker keyboard: sends any library sticker straight into the focused app, so it works in every
 * WhatsApp account and copy (main, Business, Dual Messenger via the companion, in-app second
 * account) without adding packs first. Edits are live because it reads the library directly.
 */
class StickerKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val data by lazy { KeyboardData.create(this) }
    private var typed by mutableStateOf("")
    private var target by mutableStateOf("")
    private var revision by mutableStateOf(0)

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StickerKeyboardService)
            setViewTreeViewModelStoreOwner(this@StickerKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@StickerKeyboardService)
            setContent { KeyboardContent() }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        target = info.packageName.orEmpty()
        typed = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        revision++
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        AppGraph.get(this).events.record("keyboard", "field in ${info.packageName} accepts [${accepted.joinToString()}] inputType=${info.inputType}")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun send(sticker: KeyboardSticker) {
        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        val mime = StickerMime.choose(accepted, sticker.animated)
        val events = AppGraph.get(this).events
        if (mime == null) {
            events.record("keyboard", "${info.packageName} accepts no sticker type [${accepted.joinToString()}]")
            Toast.makeText(this, "This text box doesn't accept stickers", Toast.LENGTH_SHORT).show()
            return
        }
        var file = data.fileFor(sticker) ?: return
        if (mime == "image/png") file = StickerMime.pngCopy(this, file) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val content = InputContentInfoCompat(uri, ClipDescription(sticker.label.ifBlank { "Sticker" }, arrayOf(mime)), null)
        val ok = InputConnectionCompat.commitContent(connection, info, content,
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        events.record("keyboard", "commit ${sticker.id} to ${info.packageName} as $mime ok=$ok")
        if (ok) data.markUsed(sticker)
    }

    /** Compact letter pad for searching inside the sticker keyboard (no system keyboard needed). */
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Key("space", Modifier.weight(1f), onSpace)
            }
        }
    }

    @Composable
    private fun Key(label: String, modifier: Modifier, onTap: () -> Unit) {
        Box(modifier.height(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2B26)).clickable(onClick = onTap),
            contentAlignment = Alignment.Center) {
            Text(label, color = Color(0xFFF7F6F1), fontSize = 15.sp)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun KeyboardContent() {
        var tabs by remember { mutableStateOf(emptyList<KeyboardTab>()) }
        var selected by remember { mutableStateOf(KeyboardTab(KeyboardData.FOR_YOU, "For you")) }
        var stickers by remember { mutableStateOf(emptyList<KeyboardSticker>()) }
        var searching by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        LaunchedEffect(revision) { tabs = withContext(Dispatchers.IO) { data.tabs() } }
        LaunchedEffect(selected, revision, searching, query) {
            val tab = if (searching && query.isNotBlank()) KeyboardTab(KeyboardData.SEARCH + query, query) else selected
            stickers = withContext(Dispatchers.IO) { data.stickers(tab, typed) }
        }
        // Edge-to-edge IME window (Android 15+): keep the background behind the navigation bar, keys above it.
        Column(Modifier.fillMaxWidth().background(Color(0xFF12130F)).navigationBarsPadding().height(310.dp)) {
            if (searching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (query.isEmpty()) "Search stickers…" else query, Modifier.weight(1f).clip(RoundedCornerShape(50))
                        .background(Color(0xFF2A2B26)).padding(horizontal = 14.dp, vertical = 7.dp),
                        color = if (query.isEmpty()) Color(0xFF8E8D86) else Color(0xFFF7F6F1), fontSize = 14.sp, maxLines = 1)
                    Text("✕", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2A2B26))
                        .clickable { searching = false; query = "" }.padding(horizontal = 12.dp, vertical = 7.dp),
                        color = Color(0xFFF7F6F1), fontSize = 13.sp)
                }
                LazyRow(Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 4.dp)) {
                    items(stickers, key = { it.id }) { sticker ->
                        AsyncImage(model = sticker.model, contentDescription = sticker.label,
                            modifier = Modifier.padding(4.dp).size(76.dp).clip(RoundedCornerShape(12.dp)).clickable { send(sticker) })
                    }
                }
                LetterPad(onKey = { query = (query + it).take(30) }, onBackspace = { query = query.dropLast(1) },
                    onSpace = { if (query.isNotEmpty() && !query.endsWith(' ')) query += " " })
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2A2B26))
                    .clickable { searching = true }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text("🔍", fontSize = 13.sp)
                }
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tabs, key = { it.key }) { tab ->
                        val active = tab.key == selected.key
                        Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) Color(0xFF12130F) else Color(0xFFF7F6F1),
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (active) Color(0xFFD9FF6A) else Color(0xFF2A2B26))
                                .clickable { selected = tab }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xFF2A2B26))
                    .clickable { if (!switchToPreviousInputMethod()) requestHideSelf(0) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text("ABC", color = Color(0xFFF7F6F1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (stickers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (selected.key == KeyboardData.FOR_YOU) "Your stickers appear here. Import or save some in StickerDock."
                        else "Nothing here yet", color = Color(0xFFB9B8B0), fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(74.dp), Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    items(stickers, key = { it.id }) { sticker ->
                        AsyncImage(model = sticker.model, contentDescription = sticker.label,
                            modifier = Modifier.padding(4.dp).size(74.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { send(sticker) })
                    }
                }
            }
        }
    }
}
