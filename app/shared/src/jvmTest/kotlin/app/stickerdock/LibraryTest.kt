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
import java.util.Properties
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
        assertTrue(lib.pack(pack)!!.dataVersion > v2)
        assertTrue(pack in changed)
        lib.undoDelete(listOf(ids[1]))
        assertEquals(1, lib.shelfPacks().size)
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
