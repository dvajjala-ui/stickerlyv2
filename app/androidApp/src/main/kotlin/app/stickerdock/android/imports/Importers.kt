package app.stickerdock.android.imports

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import app.stickerdock.android.AppGraph
import app.stickerdock.android.media.Normalized
import app.stickerdock.android.media.StickerNormalizer
import app.stickerdock.core.Rating
import app.stickerdock.library.AddResult
import app.stickerdock.library.Origin
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class ImportSummary(val packs: Int, val added: Int, val duplicates: Int, val rejected: Int, val paused: Boolean = false) {
    fun describe() = buildString {
        append(if (paused) "Paused: " else "Done: ")
        append("$added added")
        if (duplicates > 0) append(" · $duplicates already in library")
        if (rejected > 0) append(" · $rejected skipped")
        if (packs > 0) append(" · $packs packs")
    }
}

data class ImportTick(val scanned: Int, val added: Int, val duplicates: Int, val rejected: Int, val current: String?)

private val IMAGE_EXTENSIONS = setOf("webp", "png", "jpg", "jpeg", "heic", "heif", "gif")

internal fun readBounded(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

/**
 * Imports a user-chosen folder (e.g. Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers)
 * through the Storage Access Framework. One DocumentsContract query per directory (v1 issued
 * several IPC calls per file), byte-exact keeping of valid stickers, a SQLite journal so a
 * killed import resumes where it stopped, and packs built per original pack name at the end.
 */
class FolderImporter(private val context: Context) {
    private val graph = AppGraph.get(context)

    suspend fun run(tree: Uri, sessionId: String, stopped: () -> Boolean, tick: suspend (ImportTick) -> Unit): ImportSummary {
        val library = graph.library
        val resolver = context.contentResolver
        val done = library.importedKeys(sessionId)
        val totals = library.importTotals(sessionId)
        var added = totals.added; var dup = totals.duplicates; var rejected = totals.rejected
        val rootName = DocumentTree.rootName(tree)
        tick(ImportTick(added + dup + rejected, added, dup, rejected, null))
        for (entry in DocumentTree.images(context, tree, MAX_DEPTH, MAX_ENTRIES)) {
            if (entry.docId in done) continue
            if (stopped()) {
                library.setImportState(sessionId, "PAUSED")
                return ImportSummary(0, added, dup, rejected, paused = true)
            }
            if (entry.size > MAX_FILE) {
                library.recordRejected(sessionId, entry.docId, "Larger than 5 MB", 0); rejected++; continue
            }
            val bytes = runCatching { resolver.openInputStream(entry.uri)?.use { readBounded(it, MAX_FILE) } }.getOrNull()
            if (bytes == null) {
                library.recordRejected(sessionId, entry.docId, "Unreadable", 0); rejected++; continue
            }
            when (val n = StickerNormalizer.normalize(bytes)) {
                is Normalized.Fail -> { library.recordRejected(sessionId, entry.docId, n.reason, bytes.size); rejected++ }
                is Normalized.Ok -> {
                    val group = n.exif?.packName ?: entry.folder
                    val result = library.addSticker(n.bytes, Origin.IMPORT, originRef = group,
                        label = entry.name, emojis = n.exif?.emojis?.joinToString(",").orEmpty())
                    library.recordImport(sessionId, entry.docId, result, group, bytes.size)
                    when (result) {
                        is AddResult.Added -> added++
                        is AddResult.Duplicate -> dup++
                        is AddResult.Rejected -> rejected++
                    }
                }
            }
            done += entry.docId
            if ((added + dup + rejected) % 20 == 0) tick(ImportTick(added + dup + rejected, added, dup, rejected, entry.name))
        }
        val packs = buildPacks(sessionId, rootName)
        library.setImportState(sessionId, "DONE", "$added added")
        tick(ImportTick(added + dup + rejected, added, dup, rejected, null))
        return ImportSummary(packs, added, dup, rejected)
    }

    private fun buildPacks(sessionId: String, fallback: String): Int {
        val library = graph.library
        var count = 0
        library.importedGroups(sessionId).forEach { (group, ids) ->
            count += library.autoPack(ids, group.ifBlank { fallback }.take(60)).size
        }
        return count
    }

    companion object {
        const val MAX_DEPTH = 6
        const val MAX_ENTRIES = 60_000
        const val MAX_FILE = 5_000_000
    }
}

/**
 * ZIP / .wastickers / StickerDock v1 backup import. v1 MATURE packs become vault packs
 * (they were private and offline in v1).
 */
class ArchiveImporter(private val context: Context) {
    private val graph = AppGraph.get(context)

    private class Item(val path: String, val bytes: ByteArray)

    fun import(uri: Uri): ImportSummary {
        val name = displayName(uri) ?: "Imported"
        val items = mutableListOf<Item>()
        var manifest: JSONObject? = null
        var title: String? = null
        var total = 0L
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "Can't open $name" }
            ZipInputStream(raw).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= MAX_ENTRIES) { "Archive has too many files" }
                    val path = entry.name.replace('\\', '/')
                    if (entry.isDirectory || path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith(".")) continue
                    require(!path.startsWith("/") && ".." !in path.split('/')) { "Unsafe path in archive" }
                    val bytes = readBounded(zip, MAX_ENTRY) ?: error("$path is larger than 5 MB")
                    total += bytes.size
                    require(total <= MAX_TOTAL) { "Archive is larger than 300 MB" }
                    when {
                        path.equals("manifest.json", true) -> manifest = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull()
                        path.equals("title.txt", true) -> title = bytes.decodeToString().trim().take(60)
                        extensionOf(path) in IMAGE_EXTENSIONS -> items += Item(path, bytes)
                    }
                }
            }
        }
        val schema = manifest?.optString("schema").orEmpty()
        val packsMeta = manifest?.optJSONArray("packs")
        return when {
            schema == "stickerdock.library.v2" -> importLibraryV2(items, requireNotNull(manifest))
            schema == "stickerdock.pack.v2" -> importPackV2(items, requireNotNull(manifest))
            packsMeta != null -> importV1(items, packsMeta)
            else -> importLoose(items, title ?: name.substringBeforeLast('.'))
        }
    }

    private fun importLibraryV2(items: List<Item>, manifest: JSONObject): ImportSummary {
        val library = graph.library
        val bytesBySha = items.associate { it.path.removePrefix("stickers/").removeSuffix(".webp") to it.bytes }
        val idBySha = mutableMapOf<String, String>()
        var added = 0; var dup = 0; var rejected = 0
        val stickers = manifest.optJSONArray("stickers") ?: org.json.JSONArray()
        for (i in 0 until stickers.length()) {
            val s = stickers.getJSONObject(i)
            val sha = s.optString("sha256")
            val bytes = bytesBySha[sha] ?: run { rejected++; null } ?: continue
            val rating = runCatching { Rating.valueOf(s.optString("rating", "GENERAL")) }.getOrDefault(Rating.GENERAL)
            when (val r = library.addSticker(bytes, Origin.RESTORE, originRef = "Backup", label = s.optString("label"),
                tags = s.optString("tags"), emojis = s.optString("emojis"), rating = rating, vault = s.optBoolean("vault"))) {
                is AddResult.Added -> { idBySha[sha] = r.id; added++; if (s.optBoolean("favorite")) library.setFavorite(r.id, true) }
                is AddResult.Duplicate -> { idBySha[sha] = r.id; dup++ }
                is AddResult.Rejected -> rejected++
            }
        }
        var created = 0
        val packs = manifest.optJSONArray("packs") ?: org.json.JSONArray()
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i)
            val members = p.optJSONArray("stickers") ?: continue
            val ids = List(members.length()) { members.getString(it) }.mapNotNull(idBySha::get)
            if (ids.isEmpty()) continue
            val kind = runCatching { app.stickerdock.core.StickerKind.valueOf(p.optString("kind")) }.getOrDefault(app.stickerdock.core.StickerKind.STATIC)
            val rating = runCatching { Rating.valueOf(p.optString("rating", "GENERAL")) }.getOrDefault(Rating.GENERAL)
            val packId = library.createPack(p.optString("name", "Restored"), kind, rating, p.optBoolean("vault"), origin = "IMPORT")
            library.addToPack(packId, ids)
            if (p.optBoolean("on_shelf")) library.setShelf(packId, true)
            created++
        }
        return ImportSummary(created, added, dup, rejected)
    }

    private fun importPackV2(items: List<Item>, manifest: JSONObject): ImportSummary {
        val library = graph.library
        val byName = items.associateBy { it.path.substringAfterLast('/') }
        val packName = manifest.optString("name", "Imported").take(60)
        val ids = mutableListOf<String>()
        var dup = 0; var rejected = 0
        val stickers = manifest.optJSONArray("stickers") ?: org.json.JSONArray()
        for (i in 0 until stickers.length()) {
            val s = stickers.getJSONObject(i)
            val n = byName[s.optString("file")]?.bytes?.let(StickerNormalizer::normalize)
            if (n !is Normalized.Ok) { rejected++; continue }
            when (val r = library.addSticker(n.bytes, Origin.SHARE, originRef = packName, label = s.optString("label"),
                tags = s.optString("tags"), emojis = s.optString("emojis"))) {
                is AddResult.Added -> ids += r.id
                is AddResult.Duplicate -> { ids += r.id; dup++ }
                is AddResult.Rejected -> rejected++
            }
        }
        val packs = if (ids.isNotEmpty()) library.autoPack(ids, packName, origin = "IMPORT").size else 0
        return ImportSummary(packs, ids.size - dup, dup, rejected)
    }

    private fun importLoose(items: List<Item>, title: String): ImportSummary {
        val library = graph.library
        val groups = linkedMapOf<String, MutableList<String>>()
        var dup = 0; var rejected = 0
        for (item in items) {
            val n = StickerNormalizer.normalize(item.bytes)
            if (n !is Normalized.Ok) { rejected++; continue }
            // .wastickers trays are 96×96 PNGs, not stickers.
            if (n.reencoded && isTray(item.bytes)) continue
            val folder = item.path.substringBeforeLast('/', "").substringAfterLast('/')
            val group = n.exif?.packName ?: folder.ifBlank { title }
            when (val r = library.addSticker(n.bytes, Origin.IMPORT, originRef = group, label = item.path.substringAfterLast('/'),
                emojis = n.exif?.emojis?.joinToString(",").orEmpty())) {
                is AddResult.Added -> groups.getOrPut(group) { mutableListOf() } += r.id
                is AddResult.Duplicate -> dup++
                is AddResult.Rejected -> rejected++
            }
        }
        val packs = groups.entries.sumOf { (group, ids) -> library.autoPack(ids, group).size }
        return ImportSummary(packs, groups.values.sumOf { it.size }, dup, rejected)
    }

    private fun importV1(items: List<Item>, packs: org.json.JSONArray): ImportSummary {
        val library = graph.library
        val byPath = items.associateBy { it.path }
        var added = 0; var dup = 0; var rejected = 0; var created = 0
        for (i in 0 until packs.length()) {
            val pack = packs.getJSONObject(i)
            val id = pack.optString("id"); val packName = pack.optString("name", "Imported").take(60)
            val vault = pack.optString("rating") == "MATURE"
            val stickers = pack.optJSONArray("stickers") ?: continue
            val ids = mutableListOf<String>()
            for (j in 0 until stickers.length()) {
                val s = stickers.getJSONObject(j)
                val bytes = byPath["packs/$id/${s.optString("name")}"]?.bytes
                if (bytes == null) { rejected++; continue }
                val n = StickerNormalizer.normalize(bytes)
                if (n !is Normalized.Ok) { rejected++; continue }
                when (val r = library.addSticker(n.bytes, Origin.RESTORE, originRef = packName, label = s.optString("label"),
                    tags = s.optString("tags"), rating = Rating.GENERAL, vault = vault)) {
                    is AddResult.Added -> { ids += r.id; added++ }
                    is AddResult.Duplicate -> { ids += r.id; dup++ }
                    is AddResult.Rejected -> rejected++
                }
            }
            if (ids.isNotEmpty()) created += library.autoPack(ids, packName, origin = "IMPORT").size
        }
        return ImportSummary(created, added, dup, rejected)
    }

    private fun isTray(bytes: ByteArray): Boolean {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth == 96 && options.outHeight == 96
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val MAX_ENTRIES = 5_000
        const val MAX_ENTRY = 5_000_000
        const val MAX_TOTAL = 300_000_000L
    }
}

/** Images shared into the app from another app's share sheet. */
class SharedImageImporter(private val context: Context) {
    fun import(uris: List<Uri>): ImportSummary {
        val library = AppGraph.get(context).library
        val ids = mutableListOf<String>()
        var dup = 0; var rejected = 0
        uris.take(100).forEach { uri ->
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { readBounded(it, StickerNormalizer.MAX_INPUT_BYTES) } }.getOrNull()
            val n = bytes?.let(StickerNormalizer::normalize)
            if (n !is Normalized.Ok) { rejected++; return@forEach }
            when (val r = library.addSticker(n.bytes, Origin.SHARE, originRef = "Shared")) {
                is AddResult.Added -> ids += r.id
                is AddResult.Duplicate -> dup++
                is AddResult.Rejected -> rejected++
            }
        }
        val packs = if (ids.isNotEmpty()) library.autoPack(ids, "Shared with StickerDock", origin = "IMPORT").size else 0
        return ImportSummary(packs, ids.size, dup, rejected)
    }
}
