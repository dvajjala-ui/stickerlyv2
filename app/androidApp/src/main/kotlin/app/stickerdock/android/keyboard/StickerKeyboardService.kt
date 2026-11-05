package app.stickerdock.android.keyboard

import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import app.stickerdock.ui.KeyboardPanel
import app.stickerdock.ui.PanelSticker
import app.stickerdock.ui.PanelTab
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
        // InputMethodService.onDestroy() finishes the visible input view, so this also runs during teardown.
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onDestroy() {
        // super first: it calls onFinishInputView while the keyboard is showing (switching keyboards).
        // Moving to DESTROYED before that crashed with "cannot be moved to STARTED" (seen on device).
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
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

    @Composable
    private fun KeyboardContent() {
        var tabs by remember { mutableStateOf(emptyList<KeyboardTab>()) }
        var selected by remember { mutableStateOf(KeyboardData.FOR_YOU) }
        var stickers by remember { mutableStateOf(emptyList<KeyboardSticker>()) }
        var searching by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        LaunchedEffect(revision) { tabs = withContext(Dispatchers.IO) { data.tabs() } }
        LaunchedEffect(selected, tabs, revision, searching, query) {
            val tab = if (searching && query.isNotBlank()) KeyboardTab(KeyboardData.SEARCH + query, query)
                else tabs.firstOrNull { it.key == selected } ?: KeyboardTab(KeyboardData.FOR_YOU, "For you")
            stickers = withContext(Dispatchers.IO) { data.stickers(tab, typed) }
        }
        val byId = remember(stickers) { stickers.associateBy { it.id } }
        // Edge-to-edge IME window (Android 15+): keep the background behind the navigation bar, keys above it.
        Box(Modifier.fillMaxWidth().background(Color(0xFF12130F)).navigationBarsPadding()) {
            KeyboardPanel(
                tabs = tabs.map { PanelTab(it.key, it.title, it.symbol, it.icon) },
                selectedKey = selected,
                stickers = stickers.map { PanelSticker(it.id, it.label, it.model, it.animated) },
                searching = searching,
                query = query,
                onSelectTab = { selected = it },
                onSearching = { searching = it },
                onQuery = { query = it },
                onSend = { panel -> byId[panel.id]?.let(::send) },
                onSwitchKeyboard = { if (!switchToPreviousInputMethod()) requestHideSelf(0) },
                modifier = Modifier.height(310.dp),
                emptyText = if (selected == KeyboardData.FOR_YOU) "Your stickers appear here. Import or save some in StickerDock."
                    else "Nothing here yet",
            )
        }
    }
}
