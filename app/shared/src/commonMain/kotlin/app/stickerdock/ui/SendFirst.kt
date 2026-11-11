package app.stickerdock.ui

import androidx.compose.material3.SnackbarHostState
import app.stickerdock.discover.RemoteSticker
import app.stickerdock.library.AddResult
import app.stickerdock.library.StickerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Send-first glue (docs/flows/send-first-spec.md): tap = Send, ★ = Save, ⋯ = power actions.
 * The rules that matter (rolling Saved pack, tray auto-pad) live in [app.stickerdock.library.Library]
 * so they are JVM-tested; these helpers only wire them to the share sheet and the snackbars.
 */
object SendFirst {
    const val PADDED_NOTICE = "WhatsApp needs 3+ in a tray, so we added your recent ones."
    const val THIN_NOTICE = "Save a couple more stickers and we'll fill your tray for WhatsApp."

    /** Tap on a library/pack sticker: straight to the share sheet with the sticker MIME. */
    fun sendLocal(app: AppModel, id: String) {
        app.library.markUsed(id)
        app.host.shareStickers(listOf(id))
    }

    /** Tap on a Discover sticker: download, then share. [busy] guards double taps while loading. */
    fun sendRemote(app: AppModel, sticker: RemoteSticker, scope: CoroutineScope, snackbar: SnackbarHostState, busy: (Boolean) -> Unit) {
        busy(true)
        scope.launch {
            val result = runCatching { app.discover.download(sticker.filePath) }
            busy(false)
            result.fold(
                { app.host.shareStickerBytes(it) },
                { scope.notify(snackbar, "Couldn't load the sticker — check your connection.") },
            )
        }
    }

    /** ★ on a Discover sticker: one tap into the rolling Saved pack, with Undo. */
    fun saveRemote(app: AppModel, sticker: RemoteSticker, scope: CoroutineScope, snackbar: SnackbarHostState) {
        scope.launch {
            val result = runCatching { app.saver.saveSticker(sticker) }
            scope.notify(snackbar, result.fold({
                when (it) {
                    is AddResult.Rejected -> it.reason
                    is AddResult.Duplicate -> "Already in your library"
                    is AddResult.Added -> "Saved"
                }
            }, { "Couldn't save: ${it.message}" }), undo = (result.getOrNull() as? AddResult.Added)?.let { added ->
                { app.library.deleteStickers(listOf(added.id)) }
            })
        }
    }

    /** ⋯ "Add to WhatsApp tray": Saved pack + auto-pad + offer to every ready WhatsApp. */
    fun addToTray(app: AppModel, id: String, scope: CoroutineScope, snackbar: SnackbarHostState) {
        val item = app.library.sticker(id) ?: return
        val packId = app.library.savedPack(item.kind, item.rating, item.vault)
        app.library.addToPack(packId, listOf(id))
        offerTray(app, packId, scope, snackbar)
    }

    /** Same, for a Discover sticker: save it first, then offer the tray. */
    fun addRemoteToTray(app: AppModel, sticker: RemoteSticker, scope: CoroutineScope, snackbar: SnackbarHostState) {
        scope.launch {
            val result = runCatching { app.saver.saveSticker(sticker) }
            val id = (result.getOrNull() as? AddResult.Added)?.id ?: (result.getOrNull() as? AddResult.Duplicate)?.id
            if (id == null) {
                scope.notify(snackbar, "Couldn't save the sticker: ${result.exceptionOrNull()?.message ?: "try again"}")
                return@launch
            }
            addToTray(app, id, scope, snackbar)
        }
    }

    private fun offerTray(app: AppModel, packId: String, scope: CoroutineScope, snackbar: SnackbarHostState) {
        val prep = app.library.prepareTray(packId)
        app.library.setShelf(packId, true)
        when {
            prep == null -> scope.notify(snackbar, THIN_NOTICE)
            prep.padded.isNotEmpty() -> {
                scope.notify(snackbar, PADDED_NOTICE)
                app.host.addToAllWhatsApps(packId)
            }
            else -> app.host.addToAllWhatsApps(packId)
        }
    }

    /** ★ on a library sticker: favourite toggle (the library's save). */
    fun toggleFavorite(app: AppModel, item: StickerItem) = app.library.setFavorite(item.id, !item.favorite)
}