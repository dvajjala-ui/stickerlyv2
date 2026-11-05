package app.stickerdock.android.export

import android.content.Context
import android.net.Uri
import app.stickerdock.android.AppGraph
import app.stickerdock.android.whatsapp.LocalStickerSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Portable formats.
 *  - Pack share: `<name>.wastickers` = title.txt + author.txt + tray.png + stickers (Sticker Maker
 *    compatible) + manifest.json (StickerDock v2, keeps labels/tags/emojis).
 *  - Full backup: manifest.json {schema: "stickerdock.library.v2"} + stickers/<sha>.webp.
 * ArchiveImporter reads both, plus v1 backups.
 */
object Exporters {
    const val PACK_SCHEMA = "stickerdock.pack.v2"
    const val LIBRARY_SCHEMA = "stickerdock.library.v2"

    fun packArchive(context: Context, packId: String): File {
        val graph = AppGraph.get(context)
        val pack = requireNotNull(graph.library.pack(packId)) { "Pack not found" }
        val stickers = graph.library.packStickers(packId)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = pack.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "stickers" }.take(40)
        val out = File(dir, "$safeName.wastickers")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.put("title.txt", pack.name.toByteArray())
            zip.put("author.txt", pack.publisher.toByteArray())
            LocalStickerSource.trayFile(context, packId)?.let { zip.put("tray.png", it.readBytes()) }
            val items = JSONArray()
            stickers.forEachIndexed { index, s ->
                val name = "%02d_%s.webp".format(index + 1, s.sha256.take(12))
                graph.files.read(s.id)?.let { zip.put(name, it) }
                items.put(JSONObject().put("file", name).put("label", s.label).put("tags", s.tags).put("emojis", s.emojis)
                    .put("rating", s.rating.name))
            }
            zip.put("manifest.json", JSONObject().put("schema", PACK_SCHEMA).put("name", pack.name)
                .put("kind", pack.kind.name).put("rating", pack.rating.name).put("stickers", items).toString(2).toByteArray())
        }
        return out
    }

    /** Whole library except the vault unless [includeVault]. */
    fun libraryBackup(context: Context, target: Uri, includeVault: Boolean = false): Int {
        val graph = AppGraph.get(context)
        val lib = graph.library
        var count = 0
        context.contentResolver.openOutputStream(target).use { raw ->
            requireNotNull(raw) { "Can't write backup" }
            ZipOutputStream(raw.buffered()).use { zip ->
                val stickers = JSONArray()
                val seen = mutableSetOf<String>()
                val packs = JSONArray()
                val vaults = if (includeVault) listOf(false, true) else listOf(false)
                fun addSticker(s: app.stickerdock.library.StickerItem) {
                    if (seen.add(s.sha256)) {
                        graph.files.read(s.id)?.let { zip.put("stickers/${s.sha256}.webp", it); count++ }
                        stickers.put(JSONObject().put("sha256", s.sha256).put("label", s.label).put("tags", s.tags)
                            .put("emojis", s.emojis).put("rating", s.rating.name).put("vault", s.vault)
                            .put("favorite", s.favorite).put("origin", s.origin))
                    }
                }
                for (vault in vaults) {
                    // Every sticker, including ones not in any pack.
                    lib.stickers(app.stickerdock.library.StickerFilter(includeAdult = true, vault = vault, limit = 1_000_000)).forEach(::addSticker)
                    lib.packs(includeAdult = true, vault = vault).forEach { pack ->
                        val members = lib.packStickers(pack.id)
                        packs.put(JSONObject().put("id", pack.id).put("name", pack.name).put("kind", pack.kind.name)
                            .put("rating", pack.rating.name).put("vault", pack.vault).put("on_shelf", pack.onShelf)
                            .put("tray", pack.trayStickerId).put("stickers", JSONArray(members.map { it.sha256 })))
                        members.forEach(::addSticker)
                    }
                }
                zip.put("manifest.json", JSONObject().put("schema", LIBRARY_SCHEMA).put("created_at", System.currentTimeMillis())
                    .put("stickers", stickers).put("packs", packs).toString().toByteArray())
            }
        }
        return count
    }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }
}
