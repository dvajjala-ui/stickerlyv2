package app.stickerdock.android.dual

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import app.stickerdock.android.imports.DocumentTree
import app.stickerdock.android.imports.readBounded

/**
 * Runs in the Dual Messenger copy: sends this profile's WhatsApp sticker files into the main library
 * (dedup, EXIF pack names and packs happen on the main side), so both accounts end up in one library.
 */
class CompanionImporter(private val context: Context) {
    data class Progress(val sent: Int, val skipped: Int, val done: Boolean, val summary: String? = null)

    private val hub = "content://${Dual.HUB_USER}@${context.packageName}.sync"

    fun run(tree: Uri, onProgress: (Progress) -> Unit): Progress {
        val resolver = context.contentResolver
        val session = resolver.call(Uri.parse(hub), "begin_import", "dual:$tree", null)?.getString("session")
            ?: error("Main library not reachable — open StickerDock in your main profile and check the setup")
        var sent = 0; var skipped = 0
        for (file in DocumentTree.images(context, tree)) {
            // Binder transactions are capped near 1 MB; WhatsApp stickers are ≤500 KB.
            if (file.size > 900_000) { skipped++; continue }
            val bytes = runCatching { resolver.openInputStream(file.uri)?.use { readBounded(it, 900_000) } }.getOrNull()
            if (bytes == null) { skipped++; continue }
            val values = ContentValues().apply {
                put("session", session); put("key", file.docId); put("bytes", bytes); put("folder", file.folder)
            }
            runCatching { resolver.insert(Uri.parse("$hub/incoming"), values) }.onSuccess { sent++ }.onFailure { skipped++ }
            if ((sent + skipped) % 20 == 0) onProgress(Progress(sent, skipped, false))
        }
        val result = resolver.call(Uri.parse(hub), "finish_import", session, null)
        val summary = result?.let { "${it.getInt("added")} added · ${it.getInt("duplicates")} already in library · ${it.getInt("packs")} packs" }
        return Progress(sent, skipped, true, summary).also(onProgress)
    }
}
