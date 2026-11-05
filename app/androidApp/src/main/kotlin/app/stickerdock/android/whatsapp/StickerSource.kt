package app.stickerdock.android.whatsapp

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.stickerdock.android.AppGraph
import app.stickerdock.android.dual.Dual
import app.stickerdock.android.media.TrayMaker
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import java.io.File

data class ShelfPack(
    val id: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    val dataVersion: Long,
    val count: Int,
)

data class ShelfSticker(val id: String, val emojis: List<String>, val accessibility: String) {
    val fileName: String get() = "$id.webp"
}

/** Where the WhatsApp provider reads packs from. */
interface StickerSource {
    fun shelf(): List<ShelfPack>
    fun stickers(packId: String): List<ShelfSticker>
    fun openSticker(packId: String, stickerId: String): ParcelFileDescriptor?
    fun openTray(packId: String): ParcelFileDescriptor?

    companion object {
        fun forContext(context: Context): StickerSource =
            if (Dual.isCompanion(context)) RemoteStickerSource(context) else LocalStickerSource(context)
    }
}

/** Main profile: the library on this device. */
class LocalStickerSource(private val context: Context) : StickerSource {
    private val graph get() = AppGraph.get(context)

    override fun shelf(): List<ShelfPack> = graph.library.shelfPacks().map {
        ShelfPack(it.id, it.name, it.publisher, it.kind == StickerKind.ANIMATED, it.dataVersion, it.count)
    }

    override fun stickers(packId: String): List<ShelfSticker> {
        val pack = graph.library.shelfPacks().firstOrNull { it.id == packId } ?: return emptyList()
        val limit = if (pack.kind == StickerKind.ANIMATED) StickerRules.ANIMATED_ACCESSIBILITY_MAX else StickerRules.STATIC_ACCESSIBILITY_MAX
        return graph.library.packStickers(packId).map {
            ShelfSticker(it.id, it.whatsAppEmojis, it.description.take(limit))
        }
    }

    override fun openSticker(packId: String, stickerId: String): ParcelFileDescriptor? {
        if (!graph.library.isInPack(packId, stickerId)) return null
        val file = graph.files.file(stickerId).takeIf { it.isFile } ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openTray(packId: String): ParcelFileDescriptor? = trayFile(context, packId)?.let {
        ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        fun trayFile(context: Context, packId: String): File? {
            val graph = AppGraph.get(context)
            val pack = graph.library.pack(packId) ?: return null
            val cover = pack.trayStickerId?.takeIf { graph.library.isInPack(packId, it) }
                ?: graph.library.packPreview(packId).firstOrNull()?.id ?: return null
            return TrayMaker.cached(File(context.filesDir, "trays"), packId, pack.dataVersion) { graph.files.read(cover) }
        }
    }
}

/**
 * Dual Messenger / clone-profile copy: serves this profile's WhatsApp straight from the main
 * profile's library over the cross-user sync provider. Requires the one-time adb grant.
 */
class RemoteStickerSource(private val context: Context) : StickerSource {
    private val base = "content://${Dual.HUB_USER}@${context.packageName}.sync"

    override fun shelf(): List<ShelfPack> = query("$base/shelf") { c ->
        ShelfPack(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getLong(4), c.getInt(5))
    }

    override fun stickers(packId: String): List<ShelfSticker> = query("$base/pack/$packId/stickers") { c ->
        ShelfSticker(c.getString(0), c.getString(1).split(',').filter { it.isNotBlank() }, c.getString(2))
    }

    override fun openSticker(packId: String, stickerId: String): ParcelFileDescriptor? =
        open("$base/sticker/$packId/$stickerId")

    override fun openTray(packId: String): ParcelFileDescriptor? = open("$base/tray/$packId")

    private fun open(uri: String) = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") }
        .onFailure { AppGraph.get(context).events.record("companion", "open failed $uri: ${it.message}") }
        .getOrNull()

    private fun <T> query(uri: String, map: (android.database.Cursor) -> T): List<T> = runCatching {
        context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(map(c)) }
        }.orEmpty()
    }.onFailure { AppGraph.get(context).events.record("companion", "query failed $uri: ${it.message}") }.getOrDefault(emptyList())
}
