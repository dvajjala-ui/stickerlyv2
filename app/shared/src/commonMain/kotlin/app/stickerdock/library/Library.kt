package app.stickerdock.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.stickerdock.core.Rating
import app.stickerdock.core.Sha256
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Tags
import app.stickerdock.core.Webp
import app.stickerdock.db.Pack
import app.stickerdock.db.PackSummaries
import app.stickerdock.db.ShelfPacks
import app.stickerdock.db.Sticker
import app.stickerdock.db.StickerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Platform storage for sticker bytes, one file per sticker id. */
interface StickerFiles {
    fun write(id: String, bytes: ByteArray)
    fun read(id: String): ByteArray?
    fun delete(id: String)
    /** Absolute path usable by image loaders. */
    fun path(id: String): String
}

/** Fired after packs change so WhatsApp's provider observers refresh. */
fun interface PackChangeListener { fun onPacksChanged(packIds: Collection<String>) }

enum class Origin { IMPORT, CREATE, DISCOVER, SHARE, RESTORE }

data class StickerItem(
    val id: String,
    val sha256: String,
    val kind: StickerKind,
    val rating: Rating,
    val vault: Boolean,
    val bytes: Long,
    val frames: Int,
    val label: String,
    val tags: String,
    val emojis: String,
    val origin: String,
    val originRef: String?,
    val favorite: Boolean,
    val createdAt: Long,
) {
    val title: String get() = label.ifBlank { tags.substringBefore(' ').ifBlank { "Sticker" } }
    val whatsAppEmojis: List<String> get() = Tags.whatsAppEmojis(emojis, label, tags)
}

data class PackInfo(
    val id: String,
    val name: String,
    val publisher: String,
    val kind: StickerKind,
    val rating: Rating,
    val vault: Boolean,
    val count: Int,
    val onShelf: Boolean,
    val dataVersion: Long,
    val trayStickerId: String?,
    val origin: String,
    val remoteId: String?,
    val followUpdates: Boolean,
    val updatedAt: Long,
) {
    val whatsAppReady: Boolean get() = count in StickerRules.MIN_PACK..StickerRules.MAX_PACK
}

sealed interface AddResult {
    data class Added(val id: String, val restored: Boolean = false) : AddResult
    data class Duplicate(val id: String) : AddResult
    data class Rejected(val reason: String) : AddResult
}

data class PackAddResult(val added: List<String>, val skipped: Map<String, String>)

data class StickerFilter(
    val query: String = "",
    val kind: StickerKind? = null,
    val favoritesOnly: Boolean = false,
    val includeAdult: Boolean = false,
    val vault: Boolean = false,
    val limit: Int = 600,
)

class Library(
    private val db: StickerDatabase,
    val files: StickerFiles,
    private val now: () -> Long,
    private val newId: () -> String,
    var listener: PackChangeListener? = null,
) {
    private val q get() = db.libraryQueries

    // ---------------- stickers ----------------

    /** Adds already-normalized WebP bytes (platform codecs normalize before calling). */
    fun addSticker(
        bytes: ByteArray,
        origin: Origin,
        originRef: String? = null,
        label: String = "",
        tags: String = "",
        emojis: String = "",
        rating: Rating = Rating.GENERAL,
        vault: Boolean = false,
        dhash: Long? = null,
    ): AddResult {
        val info = Webp.inspect(bytes) ?: return AddResult.Rejected("Not a WebP sticker")
        val kind = if (info.animated) StickerKind.ANIMATED else StickerKind.STATIC
        StickerRules.problem(kind, info, bytes.size)?.let { return AddResult.Rejected(it) }
        val sha = Sha256.hex(bytes)
        if (dhash != null) nearDuplicate(kind, Fingerprint(dhash, info.frames, info.durationMs))?.let { return AddResult.Duplicate(it) }
        q.stickerBySha(sha).executeAsOneOrNull()?.let { existing ->
            if (existing.deleted_at != null) {
                q.undeleteSticker(now(), existing.id)
                packsOf(existing.id).forEach { q.bumpPack(now(), it) }
                notify(packsOf(existing.id))
                return AddResult.Added(existing.id, restored = true)
            }
            return AddResult.Duplicate(existing.id)
        }
        val id = newId()
        files.write(id, bytes)
        try {
            val t = now()
            q.insertSticker(
                id, sha, kind.name, rating.name, vault, bytes.size.toLong(), info.frames.toLong(), info.durationMs.toLong(),
                Tags.cleanLabel(label), Tags.normalize(tags), Tags.emojis(emojis).joinToString(","),
                origin.name, originRef?.take(200), t, t, dhash,
            )
        } catch (e: Throwable) {
            files.delete(id)
            throw e
        }
        if (dhash != null) hashes(kind)?.let { setHashes(kind, it + (id to Fingerprint(dhash, info.frames, info.durationMs))) }
        return AddResult.Added(id)
    }

    /** First-frame hash plus, for animations, frame count and duration (different animations often share a first frame). */
    private data class Fingerprint(val hash: Long, val frames: Int, val durationMs: Int) {
        fun matches(other: Fingerprint, animated: Boolean): Boolean {
            if (app.stickerdock.core.Dhash.distance(hash, other.hash) > app.stickerdock.core.Dhash.NEAR_DUPLICATE) return false
            if (!animated) return true
            val longest = maxOf(durationMs, other.durationMs).coerceAtLeast(1)
            return frames == other.frames && kotlin.math.abs(durationMs - other.durationMs) * 10 <= longest
        }
    }

    // Near-duplicate index per kind, loaded on first use; copy-on-write so readers on other threads see a full list.
    @kotlin.concurrent.Volatile private var staticHashes: List<Pair<String, Fingerprint>>? = null
    @kotlin.concurrent.Volatile private var animatedHashes: List<Pair<String, Fingerprint>>? = null

    private fun hashes(kind: StickerKind) = if (kind == StickerKind.ANIMATED) animatedHashes else staticHashes
    private fun setHashes(kind: StickerKind, list: List<Pair<String, Fingerprint>>) {
        if (kind == StickerKind.ANIMATED) animatedHashes = list else staticHashes = list
    }

    private fun nearDuplicate(kind: StickerKind, print: Fingerprint): String? {
        val list = hashes(kind) ?: q.stickerHashes(kind.name).executeAsList()
            .mapNotNull { row -> row.dhash?.let { row.id to Fingerprint(it, row.frame_count.toInt(), row.duration_ms.toInt()) } }
            .also { setHashes(kind, it) }
        return list.asSequence()
            .filter { (_, other) -> print.matches(other, kind == StickerKind.ANIMATED) }
            .map { it.first }
            .firstOrNull { id -> q.stickerById(id).executeAsOneOrNull()?.let { it.deleted_at == null } == true }
    }

    fun sticker(id: String): StickerItem? = q.stickerById(id).executeAsOneOrNull()?.toItem()

    fun stickers(filter: StickerFilter): List<StickerItem> = stickerQuery(filter).executeAsList().map { it.toItem() }

    fun stickersFlow(filter: StickerFilter): Flow<List<StickerItem>> =
        stickerQuery(filter).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toItem() } }

    fun recent(limit: Int = 24, includeAdult: Boolean = false): List<StickerItem> =
        q.recentStickers(false, if (includeAdult) 1L else 0L, limit.toLong(), ::Sticker).executeAsList().map { it.toItem() }

    fun counts(): Map<StickerKind, Long> =
        q.countStickers().executeAsList().associate { StickerKind.valueOf(it.kind) to it.n }

    fun updateMeta(id: String, label: String, tags: String, emojis: String) {
        q.transaction {
            q.updateStickerMeta(Tags.cleanLabel(label), Tags.normalize(tags), Tags.emojis(emojis).joinToString(","), now(), id)
            packsOf(id).forEach { q.bumpPack(now(), it) }
        }
        notify(packsOf(id))
    }

    fun setFavorite(id: String, favorite: Boolean) { q.setFavorite(favorite, now(), id) }

    fun markUsed(id: String) { q.markUsed(now(), id) }

    /** Soft delete; returns affected pack ids. Call [undoDelete] to restore. */
    fun deleteStickers(ids: Collection<String>): Set<String> {
        val affected = mutableSetOf<String>()
        q.transaction {
            ids.forEach { id ->
                q.softDeleteSticker(now(), id)
                affected += packsOf(id)
            }
            affected.forEach { q.bumpPack(now(), it) }
        }
        notify(affected)
        return affected
    }

    fun undoDelete(ids: Collection<String>) {
        val affected = mutableSetOf<String>()
        q.transaction {
            ids.forEach { id -> q.undeleteSticker(now(), id); affected += packsOf(id) }
            affected.forEach { q.bumpPack(now(), it) }
        }
        notify(affected)
    }

    /** Permanently removes stickers soft-deleted before [before] (epoch ms) and their files. */
    fun purgeDeleted(before: Long): Int {
        val ids = q.purgeableStickers(before).executeAsList()
        q.transaction { ids.forEach { q.purgeSticker(it) } }
        ids.forEach(files::delete)
        return ids.size
    }

    // ---------------- packs ----------------

    fun createPack(
        name: String,
        kind: StickerKind,
        rating: Rating = Rating.GENERAL,
        vault: Boolean = false,
        origin: String = "USER",
        remoteId: String? = null,
        remoteVersion: Long? = null,
        followUpdates: Boolean = false,
        publisher: String = "StickerDock",
    ): String {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(StickerRules.MAX_NAME).ifBlank { "My stickers" }
        val id = newId()
        require(StickerRules.validIdentifier(id))
        val t = now()
        q.insertPack(id, clean, publisher.take(StickerRules.MAX_NAME), kind.name, rating.name, vault, origin,
            remoteId, remoteVersion, followUpdates, t, t)
        return id
    }

    fun pack(id: String): PackInfo? = q.packById(id).executeAsOneOrNull()?.let { it.toInfo(countOf(it.id)) }

    fun packByRemote(remoteId: String): PackInfo? = q.packByRemoteId(remoteId).executeAsOneOrNull()?.let { it.toInfo(countOf(it.id)) }

    fun packs(includeAdult: Boolean = false, vault: Boolean = false): List<PackInfo> =
        q.packSummaries(vault, if (includeAdult) 1L else 0L).executeAsList().map { it.toInfo() }

    fun packsFlow(includeAdult: Boolean = false, vault: Boolean = false): Flow<List<PackInfo>> =
        q.packSummaries(vault, if (includeAdult) 1L else 0L).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toInfo() } }

    fun packFlow(id: String): Flow<PackInfo?> =
        q.packById(id).asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.toInfo(countOf(it.id)) }

    fun packStickers(packId: String): List<StickerItem> = q.packStickers(packId).executeAsList().map { it.toItem() }

    fun packStickersFlow(packId: String): Flow<List<StickerItem>> =
        q.packStickers(packId).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toItem() } }

    fun packPreview(packId: String): List<StickerItem> = q.packPreview(packId).executeAsList().map { it.toItem() }

    fun packsContaining(stickerId: String): List<PackInfo> =
        q.packsContaining(stickerId).executeAsList().map { it.toInfo(countOf(it.id)) }

    /** Packs WhatsApp may read: on the shelf and holding 3–30 live stickers. */
    fun shelfPacks(): List<PackInfo> = q.shelfPacks().executeAsList().map { it.toInfo() }.filter { it.whatsAppReady }

    /** Every pack the user put on the shelf, including ones WhatsApp can't see until they hold 3–30 stickers again. */
    fun shelfFlow(): Flow<List<PackInfo>> =
        q.shelfPacks().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toInfo() } }

    fun isInPack(packId: String, stickerId: String) = q.isInPack(packId, stickerId).executeAsOne() > 0

    fun addToPack(packId: String, stickerIds: List<String>): PackAddResult {
        val pack = q.packById(packId).executeAsOneOrNull() ?: return PackAddResult(emptyList(), stickerIds.associateWith { "Pack not found" })
        val added = mutableListOf<String>()
        val skipped = linkedMapOf<String, String>()
        q.transaction {
            var count = countOf(packId)
            var position = (q.maxPosition(packId).executeAsOneOrNull()?.maxPos ?: -1L) + 1
            var adult = pack.rating == Rating.ADULT.name
            for (id in stickerIds.distinct()) {
                val s = q.stickerById(id).executeAsOneOrNull()
                when {
                    s == null || s.deleted_at != null -> skipped[id] = "Sticker not found"
                    s.kind != pack.kind -> skipped[id] = if (pack.kind == StickerKind.ANIMATED.name)
                        "Static stickers can't go in an animated pack" else "Animated stickers need an animated pack"
                    s.vault != pack.vault -> skipped[id] = if (pack.vault) "Only vault stickers go in vault packs"
                        else "Vault stickers stay in vault packs"
                    q.isInPack(packId, id).executeAsOne() > 0 -> skipped[id] = "Already in this pack"
                    count >= StickerRules.MAX_PACK -> skipped[id] = "Pack is full (30 stickers)"
                    else -> {
                        q.addItem(packId, id, position++)
                        count++
                        added += id
                        if (s.rating == Rating.ADULT.name) adult = true
                    }
                }
            }
            if (adult && pack.rating != Rating.ADULT.name) q.setPackRating(Rating.ADULT.name, now(), packId)
            if (pack.tray_sticker_id == null && added.isNotEmpty()) q.setPackTray(added.first(), now(), packId)
            if (added.isNotEmpty()) q.bumpPack(now(), packId)
        }
        if (added.isNotEmpty()) notify(listOf(packId))
        return PackAddResult(added, skipped)
    }

    /** Splits stickers into as many packs as needed (same kind, rating and vault; ≤30 each). */
    fun autoPack(stickerIds: List<String>, baseName: String, origin: String = "IMPORT"): List<String> {
        val items = stickerIds.distinct().mapNotNull { q.stickerById(it).executeAsOneOrNull() }.filter { it.deleted_at == null }
        val groups = items.groupBy { Triple(it.kind, it.rating, it.vault) }
        val created = mutableListOf<String>()
        groups.forEach { (key, group) ->
            val (kind, rating, vault) = key
            group.chunked(StickerRules.MAX_PACK).forEachIndexed { index, chunk ->
                val suffix = buildString {
                    if (groups.size > 1 && kind == StickerKind.ANIMATED.name) append(" · Animated")
                    if (rating == Rating.ADULT.name) append(" · 18+")
                    if (index > 0) append(" ${index + 1}")
                }
                val packId = createPack(baseName + suffix, StickerKind.valueOf(kind), Rating.valueOf(rating), vault, origin)
                addToPack(packId, chunk.map { it.id })
                created += packId
            }
        }
        return created
    }

    /**
     * Packs for an import. Received WhatsApp stickers come from hundreds of original packs, so an original
     * pack name only becomes its own pack when it has at least [minGroup] stickers; the rest are merged
     * into [fallback] packs of 30 (static and animated apart). Original names stay searchable via originRef.
     */
    fun packImported(groups: Map<String, List<String>>, fallback: String, minGroup: Int = 4): List<String> {
        val own = groups.filter { (name, ids) -> name.isNotBlank() && ids.size >= minGroup }
        val rest = groups.filterKeys { it !in own.keys }.values.flatten()
        val created = mutableListOf<String>()
        own.forEach { (name, ids) -> created += autoPack(ids, name.take(StickerRules.MAX_NAME)) }
        if (rest.isNotEmpty()) created += autoPack(rest, fallback)
        return created
    }

    fun removeFromPack(packId: String, stickerIds: Collection<String>) {
        q.transaction {
            stickerIds.forEach { q.removeItem(packId, it) }
            val pack = q.packById(packId).executeAsOneOrNull()
            if (pack?.tray_sticker_id != null && pack.tray_sticker_id in stickerIds) {
                q.setPackTray(q.packStickers(packId).executeAsList().firstOrNull()?.id, now(), packId)
            }
            q.bumpPack(now(), packId)
        }
        notify(listOf(packId))
    }

    fun reorder(packId: String, orderedIds: List<String>) {
        q.transaction {
            orderedIds.forEachIndexed { index, id -> q.setItemPosition(index.toLong(), packId, id) }
            q.bumpPack(now(), packId)
        }
        notify(listOf(packId))
    }

    fun renamePack(packId: String, name: String) {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(StickerRules.MAX_NAME)
        require(clean.isNotBlank()) { "Name the pack" }
        q.renamePack(clean, now(), packId)
        notify(listOf(packId))
    }

    fun setTray(packId: String, stickerId: String) {
        require(isInPack(packId, stickerId)) { "Choose a sticker from this pack" }
        q.setPackTray(stickerId, now(), packId)
        notify(listOf(packId))
    }

    fun setShelf(packId: String, onShelf: Boolean) {
        q.setPackShelf(onShelf, now(), packId)
        notify(listOf(packId))
    }

    fun bump(packId: String) {
        q.bumpPack(now(), packId)
        notify(listOf(packId))
    }

    fun setRemoteVersion(packId: String, version: Long) { q.setPackRemoteVersion(version, now(), packId) }

    fun remoteVersion(packId: String): Long? = q.packById(packId).executeAsOneOrNull()?.remote_version

    /** Deletes the pack only; its stickers stay in the library. */
    fun deletePack(packId: String) {
        q.deletePack(packId)
        notify(listOf(packId))
    }

    // ---------------- import journal (resumable imports) ----------------

    data class ImportSession(val id: String, val source: String, val mode: String, val state: String, val message: String?)
    data class ImportTotals(val added: Int, val duplicates: Int, val rejected: Int) { val scanned get() = added + duplicates + rejected }

    fun startImport(source: String, mode: String): String {
        val id = newId()
        q.insertImportSession(id, source, mode, now(), now())
        return id
    }

    fun importSession(id: String): ImportSession? =
        q.importSessionById(id).executeAsOneOrNull()?.let { ImportSession(it.id, it.source, it.mode, it.state, it.message) }

    fun activeImports(): List<ImportSession> =
        q.activeImportSessions().executeAsList().map { ImportSession(it.id, it.source, it.mode, it.state, it.message) }

    fun importedKeys(sessionId: String): MutableSet<String> = q.importItemKeys(sessionId).executeAsList().toMutableSet()

    fun recordImport(sessionId: String, key: String, result: AddResult, group: String?, bytes: Int) {
        val (outcome, stickerId, reason) = when (result) {
            is AddResult.Added -> Triple("ADDED", result.id, null)
            is AddResult.Duplicate -> Triple("DUPLICATE", result.id, null)
            is AddResult.Rejected -> Triple("REJECTED", null, result.reason)
        }
        q.insertImportItem(sessionId, key, outcome, stickerId, group, reason?.take(200), bytes.toLong())
    }

    fun recordRejected(sessionId: String, key: String, reason: String, bytes: Int) {
        q.insertImportItem(sessionId, key, "REJECTED", null, null, reason.take(200), bytes.toLong())
    }

    fun importTotals(sessionId: String): ImportTotals {
        val rows = q.importTotals(sessionId).executeAsList().associate { it.outcome to it.n.toInt() }
        return ImportTotals(rows["ADDED"] ?: 0, rows["DUPLICATE"] ?: 0, rows["REJECTED"] ?: 0)
    }

    /** Stickers newly added by a session, grouped by their original pack/folder name. */
    fun importedGroups(sessionId: String): Map<String, List<String>> =
        q.importAddedGroups(sessionId).executeAsList()
            .groupBy({ it.group_key ?: "" }, { requireNotNull(it.sticker_id) })

    fun setImportState(sessionId: String, state: String, message: String? = null) {
        q.setImportState(state, message, now(), sessionId)
    }

    // ---------------- internals ----------------

    internal val queries get() = q

    fun <T> transaction(block: () -> T): T = q.transactionWithResult { block() }

    private fun stickerQuery(f: StickerFilter) = Tags.searchPattern(f.query)?.let { pattern ->
        q.searchStickers(f.vault, if (f.includeAdult) 1L else 0L, f.kind?.name, pattern, f.limit.toLong())
    } ?: q.browseStickers(f.kind?.name, if (f.favoritesOnly) 1L else 0L, if (f.includeAdult) 1L else 0L, f.vault,
        f.limit.toLong(), 0L)

    private fun countOf(packId: String) = q.countItems(packId).executeAsOne().toInt()

    private fun packsOf(stickerId: String) = q.packsContaining(stickerId).executeAsList().map { it.id }

    private fun notify(packIds: Collection<String>) {
        if (packIds.isNotEmpty()) listener?.onPacksChanged(packIds)
    }

    private fun Sticker.toItem() = StickerItem(
        id, sha256, StickerKind.valueOf(kind), Rating.valueOf(rating), vault, byte_size, frame_count.toInt(),
        label, tags, emojis, origin, origin_ref, favorite, created_at,
    )

    private fun Pack.toInfo(count: Int) = PackInfo(
        id, name, publisher, StickerKind.valueOf(kind), Rating.valueOf(rating), vault, count, on_shelf, data_version,
        tray_sticker_id, origin, remote_id, follow_updates, updated_at,
    )

    private fun PackSummaries.toInfo() = PackInfo(
        id, name, publisher, StickerKind.valueOf(kind), Rating.valueOf(rating), vault, item_count.toInt(), on_shelf,
        data_version, tray_sticker_id, origin, remote_id, follow_updates, updated_at,
    )

    private fun ShelfPacks.toInfo() = PackInfo(
        id, name, publisher, StickerKind.valueOf(kind), Rating.valueOf(rating), vault, item_count.toInt(), on_shelf,
        data_version, tray_sticker_id, origin, remote_id, follow_updates, updated_at,
    )
}
