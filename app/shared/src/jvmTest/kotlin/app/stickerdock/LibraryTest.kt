package app.stickerdock

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.stickerdock.core.Rating
import app.stickerdock.core.StickerKind
import app.stickerdock.db.StickerDatabase
import app.stickerdock.library.AddResult
import app.stickerdock.library.Library
import app.stickerdock.library.Origin
import app.stickerdock.library.StickerFiles
import app.stickerdock.library.StickerFilter
import app.stickerdock.library.StickerSort
import java.util.Properties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryTest {
    private class MemoryFiles : StickerFiles {
        val data = mutableMapOf<String, ByteArray>()
        override fun write(id: String, bytes: ByteArray) { data[id] = bytes }
        override fun read(id: String) = data[id]
        override fun delete(id: String) { data.remove(id) }
        override fun path(id: String) = "/mem/$id.webp"
    }

    private lateinit var lib: Library
    private lateinit var files: MemoryFiles
    private val changed = mutableListOf<String>()
    private var clock = 1_000L
    private var seq = 0

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        StickerDatabase.Schema.create(driver)
        files = MemoryFiles()
        lib = Library(StickerDatabase(driver), files, now = { clock++ }, newId = { "id-${++seq}" })
        lib.listener = app.stickerdock.library.PackChangeListener { changed += it }
    }

    private fun addStatic(salt: Int, label: String = "", rating: Rating = Rating.GENERAL, vault: Boolean = false): String =
        (lib.addSticker(TestWebp.vp8(salt = salt), Origin.IMPORT, label = label, rating = rating, vault = vault) as AddResult.Added).id

    @Test fun dedupesByContentAndRestoresSoftDeleted() {
        val id = addStatic(1)
        assertIs<AddResult.Duplicate>(lib.addSticker(TestWebp.vp8(salt = 1), Origin.IMPORT))
        lib.deleteStickers(listOf(id))
        assertTrue(lib.stickers(StickerFilter()).isEmpty())
        val again = lib.addSticker(TestWebp.vp8(salt = 1), Origin.IMPORT)
        assertEquals(AddResult.Added(id, restored = true), again)
        assertEquals(1, lib.stickers(StickerFilter()).size)
        assertEquals(1, files.data.size)
    }

    @Test fun rejectsInvalidStickersWithoutWritingFiles() {
        val result = lib.addSticker(TestWebp.vp8(300, 300), Origin.IMPORT)
        assertIs<AddResult.Rejected>(result)
        assertTrue(files.data.isEmpty())
    }

    @Test fun packsEnforceKindVaultAndThirtyCap() {
        val pack = lib.createPack("Office", StickerKind.STATIC)
        val ids = (1..32).map { addStatic(it) }
        val animated = (lib.addSticker(TestWebp.animated(), Origin.IMPORT) as AddResult.Added).id
        val vaulted = addStatic(99, vault = true)
        val result = lib.addToPack(pack, ids + animated + vaulted)
        assertEquals(30, result.added.size)
        assertEquals("Pack is full (30 stickers)", result.skipped[ids[30]])
        assertEquals("Animated stickers need an animated pack", result.skipped[animated])
        assertEquals("Vault stickers stay in vault packs", result.skipped[vaulted])
        assertEquals(30, lib.pack(pack)!!.count)
        assertEquals(ids.first(), lib.pack(pack)!!.trayStickerId)
    }

    @Test fun adultStickerMakesPackAdultAndHiddenByDefault() {
        val pack = lib.createPack("Spicy", StickerKind.STATIC)
        lib.addToPack(pack, listOf(addStatic(1), addStatic(2, rating = Rating.ADULT), addStatic(3)))
        assertEquals(Rating.ADULT, lib.pack(pack)!!.rating)
        assertTrue(lib.packs(includeAdult = false).none { it.id == pack })
        assertTrue(lib.packs(includeAdult = true).any { it.id == pack })
        assertEquals(2, lib.stickers(StickerFilter(includeAdult = false)).size)
    }

    @Test fun shelfOnlyExposesReadyPacksAndVersionBumpsOnEdits() {
        val pack = lib.createPack("Shelf", StickerKind.STATIC)
        val ids = (1..3).map { addStatic(it) }
        lib.addToPack(pack, ids.take(2))
        lib.setShelf(pack, true)
        assertTrue(lib.shelfPacks().isEmpty(), "2 stickers is below WhatsApp's minimum")
        lib.addToPack(pack, listOf(ids[2]))
        assertEquals(listOf(pack), lib.shelfPacks().map { it.id })
        val v1 = lib.pack(pack)!!.dataVersion
        lib.updateMeta(ids[0], "Monday mood", "office monday", "😩")
        val v2 = lib.pack(pack)!!.dataVersion
        assertTrue(v2 > v1)
        lib.deleteStickers(listOf(ids[1]))
        assertTrue(lib.shelfPacks().isEmpty(), "deleting below 3 removes it from WhatsApp")
        val managed = runBlocking { lib.shelfFlow().first() }
        assertEquals(listOf(pack to 2), managed.map { it.id to it.count }, "the shelf screen still lists it so the user can fix it")
        assertTrue(lib.pack(pack)!!.dataVersion > v2)
        assertTrue(pack in changed)
        lib.undoDelete(listOf(ids[1]))
        assertEquals(1, lib.shelfPacks().size)
        lib.setShelf(pack, false)
        assertTrue(lib.shelfPacks().isEmpty())
        assertTrue(runBlocking { lib.shelfFlow().first() }.isEmpty())
    }

    @Test fun autoPackSplitsByKindAndSize() {
        val statics = (1..45).map { addStatic(it) }
        val anim = (lib.addSticker(TestWebp.animated(frames = 2), Origin.IMPORT) as AddResult.Added).id
        val packs = lib.autoPack(statics + anim, "WhatsApp Stickers")
        val infos = packs.map { lib.pack(it)!! }
        assertEquals(listOf(30, 15, 1), infos.map { it.count })
        assertEquals(StickerKind.ANIMATED, infos.last().kind)
        assertEquals("WhatsApp Stickers 2", infos[1].name)
    }

    @Test fun nearDuplicatesAreCaughtByPerceptualHash() {
        val gradient = IntArray(72) { i -> val v = (i % 9) * 28; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val hash = app.stickerdock.core.Dhash.of(gradient)
        val first = (lib.addSticker(TestWebp.vp8(salt = 1), Origin.IMPORT, dhash = hash) as AddResult.Added).id
        // Different bytes (re-encoded), same picture: one flipped bit.
        assertEquals(AddResult.Duplicate(first), lib.addSticker(TestWebp.vp8(salt = 2), Origin.IMPORT, dhash = hash xor 1L))
        // A clearly different picture is accepted.
        assertIs<AddResult.Added>(lib.addSticker(TestWebp.vp8(salt = 3), Origin.IMPORT, dhash = hash.inv()))
        // Once the original is deleted, its lookalike may come back.
        lib.deleteStickers(listOf(first))
        assertIs<AddResult.Added>(lib.addSticker(TestWebp.vp8(salt = 4), Origin.IMPORT, dhash = hash))
    }

    @Test fun animationsSharingAFirstFrameAreNotMerged() {
        val hash = 0x0f0f_0f0f_0f0f_0f0fL
        assertIs<AddResult.Added>(lib.addSticker(TestWebp.animated(frames = 3, frameMs = 100), Origin.IMPORT, dhash = hash))
        // Same first frame, different animation (frame count) → a different sticker.
        assertIs<AddResult.Added>(lib.addSticker(TestWebp.animated(frames = 6, frameMs = 100), Origin.IMPORT, dhash = hash))
        // Same first frame, same frames and timing, different bytes → near-duplicate.
        assertIs<AddResult.Duplicate>(lib.addSticker(TestWebp.animated(frames = 3, frameMs = 100, extraBytes = 80), Origin.IMPORT, dhash = hash))
    }

    @Test fun importPackingMergesTinyOriginalPacks() {
        // 5 stickers from "Office Pain", then 7 singles from 7 different original packs (typical WhatsApp cache).
        val office = (1..5).map { addStatic(it) }
        val singles = (10..16).map { addStatic(it) }
        val groups = mapOf("Office Pain" to office) + singles.mapIndexed { i, id -> "Pack $i" to listOf(id) }
        val created = lib.packImported(groups, "From WhatsApp")
        val infos = created.map { lib.pack(it)!! }
        assertEquals(listOf("Office Pain", "From WhatsApp"), infos.map { it.name })
        assertEquals(listOf(5, 7), infos.map { it.count })
    }

    @Test fun searchMatchesLabelsTagsAndPackNames() {
        val pack = lib.createPack("Desi Mom Reactions", StickerKind.STATIC)
        val a = addStatic(1, label = "Beta khana kha lo")
        val b = addStatic(2)
        lib.updateMeta(b, "", "exam result tension", "")
        lib.addToPack(pack, listOf(a))
        assertEquals(listOf(a), lib.stickers(StickerFilter(query = "khana")).map { it.id })
        assertEquals(listOf(b), lib.stickers(StickerFilter(query = "result")).map { it.id })
        assertEquals(listOf(a), lib.stickers(StickerFilter(query = "desi mom")).map { it.id })
        assertTrue(lib.stickers(StickerFilter(query = "100%")).isEmpty())
    }

    @Test fun sortsByUseNameAndAge() {
        val a = addStatic(1, label = "zebra"); val b = addStatic(2, label = "Apple"); val c = addStatic(3, label = "")
        repeat(3) { lib.markUsed(b) }; lib.markUsed(c)
        fun order(sort: StickerSort) = lib.stickers(StickerFilter(sort = sort)).map { it.id }
        assertEquals(listOf(c, b, a), order(StickerSort.NEWEST))
        assertEquals(listOf(a, b, c), order(StickerSort.OLDEST))
        assertEquals(listOf(b, a, c), order(StickerSort.NAME), "A–Z ignores case; unnamed stickers last")
        assertEquals(b, order(StickerSort.USED).first())
    }

    @Test fun findsAndMergesNearDuplicatesWithUndo() {
        // Stickers imported before hashes existed: same picture saved three times, plus one different picture.
        val keep = addStatic(1, label = "Chai time"); val copy1 = addStatic(2); val copy2 = addStatic(3, label = "chai")
        val other = addStatic(4)
        lib.setHash(keep, 0b1011L); lib.setHash(copy1, 0b1111L); lib.setHash(copy2, 0b1010L); lib.setHash(other, -1L)
        lib.updateMeta(copy1, "", "morning tea", "☕")
        lib.setFavorite(copy2, true)
        repeat(2) { lib.markUsed(copy1) }
        val packA = lib.createPack("A", StickerKind.STATIC)
        lib.addToPack(packA, listOf(other, copy1, keep))       // copy1 and keep are both here
        val packB = lib.createPack("B", StickerKind.STATIC)
        val fillers = (10..12).map { addStatic(it) }
        lib.addToPack(packB, listOf(copy2) + fillers)          // copy2 is the cover of B

        val groups = lib.findDuplicates()
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(setOf(keep, copy1, copy2), (listOf(group.keeper) + group.others).map { it.id }.toSet())
        assertTrue(other !in group.others.map { it.id })

        // copy1 is the suggested keeper (most used); the user picks "keep" instead.
        assertEquals(copy1, group.keeper.id)
        val result = lib.mergeDuplicates(keep, listOf(copy1, copy2))
        assertEquals(setOf(copy1, copy2), result.removed.toSet())
        assertEquals(listOf(packB), result.addedTo, "keep was already in A; it takes copy2's place in B")
        assertEquals(listOf(fillers[0], fillers[1], fillers[2], keep).toSet(), lib.packStickers(packB).map { it.id }.toSet())
        assertEquals(keep, lib.packStickers(packB).first().id, "same position as the copy it replaced")
        assertEquals(keep, lib.pack(packB)!!.trayStickerId)
        assertEquals(listOf(packA), result.thinPacks, "A had other+copy1+keep; now 2 stickers")
        val merged = lib.sticker(keep)!!
        assertTrue(merged.favorite)
        assertEquals("Chai time", merged.label)
        assertTrue("morning" in merged.tags && "tea" in merged.tags)
        assertEquals("☕", merged.emojis)
        assertTrue(lib.findDuplicates().isEmpty())

        lib.undoMerge(result)
        assertEquals(setOf(copy2) + fillers, lib.packStickers(packB).map { it.id }.toSet())
        assertEquals(copy2, lib.pack(packB)!!.trayStickerId)
        assertEquals(3, lib.packStickers(packA).size)
        assertFalse(lib.sticker(keep)!!.favorite)
        assertEquals(1, lib.findDuplicates().size)
    }

    @Test fun duplicatesNeedSameRatingAndAnimationShape() {
        val g = addStatic(1); val adult = addStatic(2, rating = Rating.ADULT)
        lib.setHash(g, 7L); lib.setHash(adult, 7L)
        assertTrue(lib.findDuplicates().isEmpty(), "never merge a general sticker with an 18+ one")
        val a1 = (lib.addSticker(TestWebp.animated(frames = 3), Origin.IMPORT) as AddResult.Added).id
        val a2 = (lib.addSticker(TestWebp.animated(frames = 5), Origin.IMPORT) as AddResult.Added).id
        lib.setHash(a1, 9L); lib.setHash(a2, 9L)
        assertTrue(lib.findDuplicates().isEmpty(), "same first frame, different animation")
        assertTrue(lib.missingHashes().isEmpty())
    }

    @Test fun ratingFollowsStickersAndVaultMovesLeavePacks() {
        val a = addStatic(1); val b = addStatic(2); val c = addStatic(3)
        val pack = lib.createPack("Mixed", StickerKind.STATIC)
        lib.addToPack(pack, listOf(a, b, c))
        val before = lib.setRating(listOf(a), Rating.ADULT)
        assertEquals(Rating.ADULT, lib.pack(pack)!!.rating, "one 18+ sticker makes the pack 18+")
        assertEquals(mapOf(a to Rating.GENERAL), before)
        lib.restoreRatings(before)
        assertEquals(Rating.GENERAL, lib.pack(pack)!!.rating, "no 18+ sticker left")
        lib.setTray(pack, b)
        assertEquals(listOf("Mixed"), lib.packsLeftByVaultMove(listOf(b), true).map { it.name })
        val v0 = lib.pack(pack)!!.dataVersion
        assertEquals(setOf(pack), lib.setVault(listOf(b), true))
        assertEquals(listOf(a, c), lib.packStickers(pack).map { it.id })
        assertTrue(lib.pack(pack)!!.dataVersion > v0, "WhatsApp reloads the pack")
        assertEquals(a, lib.pack(pack)!!.trayStickerId, "cover moves to the first remaining sticker")
        assertTrue(lib.stickers(StickerFilter()).none { it.id == b })
        assertEquals(listOf(b), lib.stickers(StickerFilter(vault = true)).map { it.id })
        assertTrue(lib.setVault(listOf(b), true).isEmpty(), "already in the vault")
        assertTrue(lib.packsLeftByVaultMove(listOf(b), false).isEmpty())
    }

    @Test fun autoTagsMakeImportedStickersSearchableWithoutTouchingUserWords() {
        val imported = addStatic(1, label = "STK-20241027-WA0008")
        val named = addStatic(2)
        lib.updateMeta(named, "Mera naam", "office", "")
        assertEquals("Sticker", lib.sticker(imported)!!.title, "file names don't show as names")
        assertEquals(2, lib.autoTagQueue().size)
        lib.applyAutoTags(imported, "Kya baat hai\nboss", listOf("Smile"))
        lib.applyAutoTags(named, "Different words", emptyList())
        assertEquals("Kya baat hai boss", lib.sticker(imported)!!.title, "the caption shows while there is no name")
        assertEquals("STK-20241027-WA0008", lib.sticker(imported)!!.label, "the stored name itself is never rewritten")
        assertEquals("Mera naam", lib.sticker(named)!!.title, "the user's name wins over a caption")
        assertEquals("Different words", lib.sticker(named)!!.autoCaption)
        assertEquals("office", lib.sticker(named)!!.tags, "the user's tags stay")
        assertEquals(listOf(imported), lib.stickers(StickerFilter(query = "khush")).map { it.id })
        assertEquals(listOf(named), lib.stickers(StickerFilter(query = "different")).map { it.id })
        assertTrue(lib.autoTagQueue().isEmpty())
        assertEquals(Library.AutoTagProgress(2, 2), lib.autoTagProgress())
        assertEquals(listOf(imported), lib.stickers(StickerFilter(query = "kya baat")).map { it.id }, "captions are searchable")
    }

    @Test fun deletingPackKeepsStickersAndPurgeRemovesFiles() {
        val pack = lib.createPack("Temp", StickerKind.STATIC)
        val ids = (1..3).map { addStatic(it) }
        lib.addToPack(pack, ids)
        lib.deletePack(pack)
        assertEquals(3, lib.stickers(StickerFilter()).size)
        lib.deleteStickers(ids)
        assertEquals(3, lib.purgeDeleted(before = Long.MAX_VALUE))
        assertTrue(files.data.isEmpty())
        assertFalse(lib.isInPack(pack, ids[0]))
    }
}
