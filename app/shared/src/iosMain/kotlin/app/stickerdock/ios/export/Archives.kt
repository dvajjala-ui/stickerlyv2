package app.stickerdock.ios.export

import app.stickerdock.ios.imports.ArchiveImport
import app.stickerdock.ios.zip.ByteSink
import app.stickerdock.ios.zip.ZipWriter
import app.stickerdock.library.Library
import app.stickerdock.library.StickerFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Portable formats, byte-compatible with Android's Exporters so files move freely between phones:
 *  - pack share `<name>.wastickers`: title.txt + author.txt + tray.png + stickers (Sticker Maker layout)
 *    + manifest.json (`stickerdock.pack.v2`, keeps labels/tags/emojis);
 *  - full backup: manifest.json (`stickerdock.library.v2`) + stickers/<sha256>.webp.
 */
object Archives {
    /** File-system-safe base name for a pack, as on Android. */
    fun fileName(packName: String): String =
        packName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "stickers" }.take(40)

    fun writePack(library: Library, packId: String, trayPng: ByteArray?, sink: ByteSink, now: Long) {
        val pack = requireNotNull(library.pack(packId)) { "Pack not found" }
        val zip = ZipWriter(sink, now)
        zip.add("title.txt", pack.name.encodeToByteArray())
        zip.add("author.txt", pack.publisher.encodeToByteArray())
        trayPng?.let { zip.add("tray.png", it) }
        val items = buildJsonArray {
            library.packStickers(packId).forEachIndexed { index, s ->
                val bytes = library.files.read(s.id) ?: return@forEachIndexed
                val name = "${(index + 1).toString().padStart(2, '0')}_${s.sha256.take(12)}.webp"
                zip.add(name, bytes)
                add(buildJsonObject { put("file", name); put("label", s.label); put("tags", s.tags); put("emojis", s.emojis) })
            }
        }
        val manifest = buildJsonObject {
            put("schema", ArchiveImport.PACK_SCHEMA)
            put("name", pack.name)
            put("kind", pack.kind.name)
            put("rating", pack.rating.name)
            put("stickers", items)
        }
        zip.add("manifest.json", manifest.toString().encodeToByteArray())
        zip.finish()
    }

    /**
     * Every sticker (also those in no pack) plus all packs; the vault only when [includeVault].
     * Returns the number of sticker files written.
     */
    fun writeBackup(library: Library, sink: ByteSink, now: Long, includeVault: Boolean = false): Int {
        val zip = ZipWriter(sink, now)
        val vaults = if (includeVault) listOf(false, true) else listOf(false)
        val written = HashSet<String>()
        val stickers = mutableListOf<JsonObject>()
        for (vault in vaults) {
            for (s in library.stickers(StickerFilter(includeAdult = true, vault = vault, limit = Int.MAX_VALUE))) {
                if (s.sha256 in written) continue
                val bytes = library.files.read(s.id) ?: continue
                zip.add("stickers/${s.sha256}.webp", bytes)
                written += s.sha256
                stickers += buildJsonObject {
                    put("sha256", s.sha256); put("label", s.label); put("tags", s.tags); put("emojis", s.emojis)
                    put("rating", s.rating.name); put("vault", s.vault); put("favorite", s.favorite); put("origin", s.origin)
                }
            }
        }
        val packs = buildJsonArray {
            for (vault in vaults) for (pack in library.packs(includeAdult = true, vault = vault)) {
                val members = library.packStickers(pack.id).map { it.sha256 }.filter { it in written }
                add(buildJsonObject {
                    put("id", pack.id); put("name", pack.name); put("kind", pack.kind.name); put("rating", pack.rating.name)
                    put("vault", pack.vault); put("on_shelf", pack.onShelf)
                    pack.trayStickerId?.let { put("tray", it) }
                    put("stickers", JsonArray(members.map { JsonPrimitive(it) }))
                })
            }
        }
        val manifest = buildJsonObject {
            put("schema", ArchiveImport.LIBRARY_SCHEMA)
            put("created_at", now)
            put("stickers", JsonArray(stickers))
            put("packs", packs)
        }
        zip.add("manifest.json", manifest.toString().encodeToByteArray())
        zip.finish()
        return written.size
    }
}
