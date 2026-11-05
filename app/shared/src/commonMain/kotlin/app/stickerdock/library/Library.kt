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

/** Library grid order. Search results keep their own relevance order. */
enum class StickerSort(val label: String) { NEWEST("Newest"), USED("Most used"), OLDEST("Oldest"), NAME("A–Z") }

data class StickerFilter(
    val query: String = "",
    val kind: StickerKind? = null,
    val favoritesOnly: Boolean = false,
    val includeAdult: Boolean = false,
    val vault: Boolean = false,
    val limit: Int = 600,
    val sort: StickerSort = StickerSort.NEWEST,
)

/** Near-identical stickers; [keeper] is the one to keep (in most packs, then most used, favourite, oldest). */
data class DuplicateGroup(val keeper: StickerItem, val others: List<StickerItem>)

/** What [Library.mergeDuplicates] changed, so [Library.undoMerge] can put it back. */
data class MergeResult(
    val keeperId: String,
    val removed: List<String>,
    /** Packs the keeper joined in place of a removed copy. */
    val addedTo: List<String>,
    /** Packs that now hold fewer than 3 stickers (two copies were in the same pack), so WhatsApp hides them. */
    val thinPacks: List<String>,
    /** Pack id → the copy that was its cover. */
    val trayMoves: Map<String, String>,
    /** The keeper as it was, for undo. */
    val before: KeeperState,
) {
    data class KeeperState(val useCount: Long, val favorite: Boolean, val lastUsed: Long?, val label: String, val tags: String, val emojis: String)
}

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
            .map { row -> row.id to Fingerprint(row.dhash, row.frame_count.toInt(), row.duration_ms.toInt()) } // query keeps hashed rows only
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

    /**
     * Marks stickers 18+ or general. A pack's rating follows its stickers: 18+ as soon as one is, general once none is.
     * Returns each sticker's previous rating, for undo.
     */
    fun setRating(ids: Collection<String>, rating: Rating): Map<String, Rating> {
        val previous = linkedMapOf<String, Rating>()
        q.transaction {
            val t = now()
            val packs = mutableSetOf<String>()
            ids.distinct().forEach { id ->
                val s = q.stickerById(id).executeAsOneOrNull() ?: return@forEach
                previous[id] = Rating.valueOf(s.rating)
                q.setStickerRating(rating.name, t, id)
                packs += packsOf(id)
            }
            packs.forEach { packId ->
                val adult = q.packAdultCount(packId).executeAsOne() > 0
                q.setPackRating(if (adult) Rating.ADULT.name else Rating.GENERAL.name, t, packId)
            }
        }
        return previous
    }

    /** Puts back what [setRating] returned. */
    fun restoreRatings(previous: Map<String, Rating>) {
        previous.entries.groupBy({ it.value }, { it.key }).forEach { (rating, ids) -> setRating(ids, rating) }
    }

    /**
     * Moves stickers into or out of the private vault. Vault stickers only live in vault packs (and the reverse),
     * so each sticker leaves the packs on the other side; returns those packs (WhatsApp updates them).
     */
    fun setVault(ids: Collection<String>, vault: Boolean): Set<String> {
        val left = linkedSetOf<String>()
        q.transaction {
            val t = now()
            ids.distinct().forEach { id ->
                val s = q.stickerById(id).executeAsOneOrNull() ?: return@forEach
                if (s.vault == vault) return@forEach
                q.setStickerVault(vault, t, id)
                q.packsContaining(id).executeAsList().filter { it.vault != vault }.forEach { pack ->
                    q.removeItem(pack.id, id)
                    left += pack.id
                    if (pack.tray_sticker_id == id) q.setPackTray(q.packStickers(pack.id).executeAsList().firstOrNull()?.id, t, pack.id)
                }
            }
            left.forEach { q.bumpPack(t, it) }
        }
        notify(left)
        return left
    }

    /** Packs [setVault] would take these stickers out of. */
    fun packsLeftByVaultMove(ids: Collection<String>, vault: Boolean): List<PackInfo> =
        ids.distinct().flatMap { id ->
            val s = q.stickerById(id).executeAsOneOrNull()
            if (s == null || s.vault == vault) emptyList() else q.packsContaining(id).executeAsList().filter { it.vault != vault }
        }.distinctBy { it.id }.map { it.toInfo(countOf(it.id)) }

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

    // ---------------- duplicates ----------------

    /** Stickers saved before perceptual hashes existed; the platform hashes them with [setHash]. */
    fun missingHashes(limit: Int = 200): List<String> = q.stickersMissingHash(limit.toLong()).executeAsList()

    fun setHash(id: String, dhash: Long) {
        q.setStickerHash(dhash, id)
        staticHashes = null; animatedHashes = null // reload the near-duplicate index with the new hash
    }

    /**
     * Groups of near-identical stickers (same kind and rating; animations must also match in frames and duration).
     * Only stickers with a hash take part. Biggest groups first.
     */
    fun findDuplicates(includeAdult: Boolean = true, vault: Boolean = false): List<DuplicateGroup> {
        val rows = q.stickerFingerprints(vault, if (includeAdult) 1L else 0L).executeAsList()
        val groups = mutableListOf<DuplicateGroup>()
        rows.groupBy { it.kind to it.rating }.forEach { (key, list) ->
            val animated = key.first == StickerKind.ANIMATED.name
            val prints = list.map { Fingerprint(requireNotNull(it.dhash), it.frame_count.toInt(), it.duration_ms.toInt()) }
            val parent = IntArray(list.size) { it }
            fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
            for (i in list.indices) for (j in i + 1 until list.size) {
                if (prints[i].matches(prints[j], animated)) { val a = root(i); val b = root(j); if (a != b) parent[b] = a }
            }
            list.indices.groupBy(::root).values.filter { it.size > 1 }.forEach { members ->
                val ranked = members.sortedWith(compareByDescending<Int> { list[it].pack_count }.thenByDescending { list[it].use_count }
                    .thenByDescending { list[it].favorite }.thenBy { list[it].created_at })
                val keeper = ranked.first()
                // Chains (A≈B≈C) can link stickers that differ; only offer copies that match the keeper itself.
                val copies = ranked.drop(1).filter { prints[it].matches(prints[keeper], animated) }
                if (copies.isNotEmpty()) {
                    val items = (listOf(keeper) + copies).mapNotNull { sticker(list[it].id) }
                    if (items.size > 1) groups += DuplicateGroup(items.first(), items.drop(1))
                }
            }
        }
        return groups.sortedByDescending { it.others.size }
    }

    /**
     * Keeps [keeperId] and removes [otherIds]: the keeper takes each copy's place in its packs (same position, so
     * WhatsApp order holds), its use count, favourite and words. Copies are soft-deleted, so [undoMerge] restores all.
     */
    fun mergeDuplicates(keeperId: String, otherIds: List<String>): MergeResult {
        val affected = linkedSetOf<String>()
        val result = q.transactionWithResult {
            val keeper = q.stickerById(keeperId).executeAsOne()
            val copies = otherIds.distinct().filter { it != keeperId }.mapNotNull { q.stickerById(it).executeAsOneOrNull() }
                .filter { it.deleted_at == null && it.kind == keeper.kind && it.vault == keeper.vault }
            val keeperPacks = packsOf(keeperId).toMutableSet()
            affected += keeperPacks
            copies.forEach { affected += packsOf(it.id) }
            val countBefore = affected.associateWith { countOf(it) }
            val addedTo = mutableListOf<String>()
            val trayMoves = mutableMapOf<String, String>()
            val t = now()
            copies.forEach { copy ->
                q.packPositionsOf(copy.id).executeAsList().forEach { row ->
                    if (keeperPacks.add(row.pack_id)) { q.addItem(row.pack_id, keeperId, row.position); addedTo += row.pack_id }
                    if (q.packById(row.pack_id).executeAsOneOrNull()?.tray_sticker_id == copy.id) {
                        trayMoves[row.pack_id] = copy.id
                        q.setPackTray(keeperId, t, row.pack_id)
                    }
                }
                q.softDeleteSticker(t, copy.id)
            }
            val all = listOf(keeper) + copies
            q.absorbSticker(
                useCount = all.sumOf { it.use_count },
                favorite = all.any { it.favorite },
                lastUsed = all.mapNotNull { it.last_used_at }.maxOrNull(),
                label = keeper.label.ifBlank { copies.firstOrNull { it.label.isNotBlank() }?.label.orEmpty() },
                tags = Tags.normalize(all.joinToString(" ") { it.tags }),
                emojis = Tags.emojis(all.joinToString(" ") { it.emojis.replace(',', ' ') }, max = 12).distinct()
                    .take(StickerRules.MAX_EMOJIS).joinToString(","),
                now = t, id = keeperId,
            )
            affected.forEach { q.bumpPack(t, it) }
            val thin = affected.filter { (countBefore[it] ?: 0) >= StickerRules.MIN_PACK && countOf(it) < StickerRules.MIN_PACK }
            MergeResult(keeperId, copies.map { it.id }, addedTo, thin, trayMoves,
                MergeResult.KeeperState(keeper.use_count, keeper.favorite, keeper.last_used_at, keeper.label, keeper.tags, keeper.emojis))
        }
        notify(affected)
        return result
    }

    fun undoMerge(result: MergeResult) {
        val affected = mutableSetOf<String>()
        q.transaction {
            val t = now()
            result.addedTo.forEach { q.removeItem(it, result.keeperId); affected += it }
            result.removed.forEach { id -> q.undeleteSticker(t, id); affected += packsOf(id) }
            result.trayMoves.forEach { (packId, stickerId) -> q.setPackTray(stickerId, t, packId) }
            with(result.before) { q.absorbSticker(useCount, favorite, lastUsed, label, tags, emojis, t, result.keeperId) }
            affected += packsOf(result.keeperId)
            affected.forEach { q.bumpPack(t, it) }
        }
        notify(affected)
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
        f.sort.name, f.limit.toLong(), 0L)

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
