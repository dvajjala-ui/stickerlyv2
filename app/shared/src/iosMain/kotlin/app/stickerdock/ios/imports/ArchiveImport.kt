package app.stickerdock.ios.imports

import app.stickerdock.core.Rating
import app.stickerdock.core.StickerKind
import app.stickerdock.ios.zip.ZipEntry
import app.stickerdock.ios.zip.ZipException
import app.stickerdock.ios.zip.ZipReader
import app.stickerdock.library.AddResult
import app.stickerdock.library.Library
import app.stickerdock.library.Origin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * ZIP / .wastickers / StickerDock backup import — the same formats as Android's ArchiveImporter:
 *  - `manifest.json` schema `stickerdock.library.v2` (full backup, stickers/<sha>.webp),
 *  - schema `stickerdock.pack.v2` (StickerDock pack share),
 *  - StickerDock v1 backups (`packs[]`; v1 MATURE packs become vault packs),
 *  - anything else: loose images, grouped by WhatsApp EXIF pack name or folder.
 * Entries are read one at a time from the archive, so large backups don't have to fit in memory.
 */
class ArchiveImport(private val library: Library, private val check: StickerCheck) {
    private val reasons = Reasons()

    fun import(zip: ZipReader, displayName: String): ImportSummary {
        if (zip.entries.size > MAX_ENTRIES) throw ZipException("Archive has too many files")
        var manifest: JsonObject? = null
        var title: String? = null
        val images = mutableListOf<ZipEntry>()
        var total = 0L
        for (entry in zip.entries) {
            val path = entry.name
            if (entry.isDirectory || path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith(".")) continue
            if (path.startsWith("/") || ".." in path.split('/')) { reasons.add("Unsafe path in archive"); continue }
            when {
                path.equals("manifest.json", ignoreCase = true) ->
                    manifest = runCatching { Json.parseToJsonElement(zip.read(entry, MAX_MANIFEST).decodeToString()) as? JsonObject }.getOrNull()
                path.equals("title.txt", ignoreCase = true) ->
                    title = runCatching { zip.read(entry, 4_096).decodeToString().trim().take(60) }.getOrNull()?.ifBlank { null }
                extensionOf(path) in IMAGE_EXTENSIONS -> {
                    if (entry.size > MAX_ENTRY) { reasons.add("Larger than 5 MB"); continue }
                    total += entry.size
                    if (total > MAX_TOTAL) throw ZipException("Archive is larger than 300 MB")
                    images += entry
                }
            }
        }
        val schema = manifest?.str("schema").orEmpty()
        val v1Packs = manifest?.get("packs") as? JsonArray
        val summary = when {
            schema == LIBRARY_SCHEMA -> importLibraryV2(zip, images, requireNotNull(manifest))
            schema == PACK_SCHEMA -> importPackV2(zip, images, requireNotNull(manifest))
            v1Packs != null -> importV1(zip, images, v1Packs)
            else -> importLoose(zip, images, title ?: displayName.substringBeforeLast('.').ifBlank { "Imported" })
        }
        return summary.copy(rejected = reasons.total, reasons = reasons.toMap())
    }

    private fun bytes(zip: ZipReader, entry: ZipEntry): ByteArray? = try {
        zip.read(entry, MAX_ENTRY)
    } catch (e: ZipException) {
        reasons.add(e.message ?: "Damaged file in archive")
        null
    }

    private fun importLibraryV2(zip: ZipReader, images: List<ZipEntry>, manifest: JsonObject): ImportSummary {
        val bySha = images.associateBy { it.name.removePrefix("stickers/").removeSuffix(".webp") }
        val idBySha = mutableMapOf<String, String>()
        var added = 0; var duplicates = 0
        for (s in manifest.objects("stickers")) {
            val sha = s.str("sha256")
            val entry = bySha[sha]
            if (entry == null) { reasons.add("Missing from backup"); continue }
            val bytes = bytes(zip, entry) ?: continue
            // Restores keep the backed-up bytes exactly (no near-duplicate merging), like Android.
            val result = library.addSticker(bytes, Origin.RESTORE, originRef = "Backup", label = s.str("label"), tags = s.str("tags"),
                emojis = s.str("emojis"), rating = s.enumOf("rating", Rating.GENERAL), vault = s.bool("vault"))
            when (result) {
                is AddResult.Added -> { idBySha[sha] = result.id; added++; if (s.bool("favorite")) library.setFavorite(result.id, true) }
                is AddResult.Duplicate -> { idBySha[sha] = result.id; duplicates++ }
                is AddResult.Rejected -> reasons.add(result.reason)
            }
        }
        var created = 0
        for (p in manifest.objects("packs")) {
            val ids = (p["stickers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content }
                .mapNotNull(idBySha::get)
            if (ids.isEmpty()) continue
            val packId = library.createPack(p.str("name", "Restored"), p.enumOf("kind", StickerKind.STATIC), p.enumOf("rating", Rating.GENERAL),
                p.bool("vault"), origin = "IMPORT")
            library.addToPack(packId, ids)
            if (p.bool("on_shelf")) library.setShelf(packId, true)
            created++
        }
        return ImportSummary(created, added, duplicates, 0)
    }

    private fun importPackV2(zip: ZipReader, images: List<ZipEntry>, manifest: JsonObject): ImportSummary {
        val byName = images.associateBy { it.name.substringAfterLast('/') }
        val packName = manifest.str("name", "Imported").take(60).ifBlank { "Imported" }
        // A shared 18+ pack stays 18+ (age-gated) on this phone.
        val rating = manifest.enumOf("rating", Rating.GENERAL)
        val ids = mutableListOf<String>()
        var duplicates = 0
        for (s in manifest.objects("stickers")) {
            val entry = byName[s.str("file")]
            if (entry == null) { reasons.add("Missing from archive"); continue }
            val bytes = bytes(zip, entry) ?: continue
            when (val n = check.check(bytes)) {
                is Checked.Fail -> reasons.add(n.reason)
                is Checked.Ok -> when (val r = library.addSticker(n.bytes, Origin.SHARE, originRef = packName, label = s.str("label"),
                    tags = s.str("tags"), emojis = s.str("emojis"), rating = rating, dhash = n.dhash)) {
                    is AddResult.Added -> ids += r.id
                    is AddResult.Duplicate -> { ids += r.id; duplicates++ }
                    is AddResult.Rejected -> reasons.add(r.reason)
                }
            }
        }
        val packs = if (ids.isNotEmpty()) library.autoPack(ids, packName, origin = "IMPORT").size else 0
        return ImportSummary(packs, ids.size - duplicates, duplicates, 0)
    }

    private fun importLoose(zip: ZipReader, images: List<ZipEntry>, title: String): ImportSummary {
        val groups = linkedMapOf<String, MutableList<String>>()
        var duplicates = 0
        for (entry in images) {
            val bytes = bytes(zip, entry) ?: continue
            if (StickerCheck.isTrayIcon(bytes)) continue
            when (val n = check.check(bytes)) {
                is Checked.Fail -> reasons.add(n.reason)
                is Checked.Ok -> {
                    val folder = entry.name.substringBeforeLast('/', "").substringAfterLast('/')
                    val group = n.exif?.packName ?: folder.ifBlank { title }
                    when (val r = library.addSticker(n.bytes, Origin.IMPORT, originRef = group, label = entry.name.substringAfterLast('/'),
                        emojis = n.exif?.emojis?.joinToString(",").orEmpty(), dhash = n.dhash)) {
                        is AddResult.Added -> groups.getOrPut(group) { mutableListOf() } += r.id
                        is AddResult.Duplicate -> duplicates++
                        is AddResult.Rejected -> reasons.add(r.reason)
                    }
                }
            }
        }
        val packs = library.packImported(groups, title, minGroup = 3).size
        return ImportSummary(packs, groups.values.sumOf { it.size }, duplicates, 0)
    }

    private fun importV1(zip: ZipReader, images: List<ZipEntry>, packs: JsonArray): ImportSummary {
        val byPath = images.associateBy { it.name }
        var added = 0; var duplicates = 0; var created = 0
        for (pack in packs.filterIsInstance<JsonObject>()) {
            val id = pack.str("id")
            val packName = pack.str("name", "Imported").take(60).ifBlank { "Imported" }
            val vault = pack.str("rating") == "MATURE"
            val ids = mutableListOf<String>()
            for (s in pack.objects("stickers")) {
                val entry = byPath["packs/$id/${s.str("name")}"]
                if (entry == null) { reasons.add("Missing from backup"); continue }
                val bytes = bytes(zip, entry) ?: continue
                when (val n = check.check(bytes)) {
                    is Checked.Fail -> reasons.add(n.reason)
                    is Checked.Ok -> when (val r = library.addSticker(n.bytes, Origin.RESTORE, originRef = packName, label = s.str("label"),
                        tags = s.str("tags"), rating = Rating.GENERAL, vault = vault, dhash = n.dhash)) {
                        is AddResult.Added -> { ids += r.id; added++ }
                        is AddResult.Duplicate -> { ids += r.id; duplicates++ }
                        is AddResult.Rejected -> reasons.add(r.reason)
                    }
                }
            }
            if (ids.isNotEmpty()) created += library.autoPack(ids, packName, origin = "IMPORT").size
        }
        return ImportSummary(created, added, duplicates, 0)
    }

    companion object {
        const val LIBRARY_SCHEMA = "stickerdock.library.v2"
        const val PACK_SCHEMA = "stickerdock.pack.v2"
        const val MAX_ENTRIES = 5_000
        const val MAX_ENTRY = 5_000_000
        const val MAX_TOTAL = 300_000_000L
        private const val MAX_MANIFEST = 8_000_000
    }
}

// org.json-like lenient accessors, so manifests written by Android read the same way here.

private fun JsonObject.str(key: String, default: String = ""): String = when (val v = this[key]) {
    null, JsonNull -> default
    is JsonPrimitive -> v.content
    else -> v.toString()
}

private fun JsonObject.bool(key: String): Boolean {
    val v = this[key] as? JsonPrimitive ?: return false
    return v.booleanOrNull ?: v.content.equals("true", ignoreCase = true)
}

private inline fun <reified E : Enum<E>> JsonObject.enumOf(key: String, default: E): E {
    val name = str(key)
    return enumValues<E>().firstOrNull { it.name == name } ?: default
}

private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
