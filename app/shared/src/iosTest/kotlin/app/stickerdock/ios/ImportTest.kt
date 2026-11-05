package app.stickerdock.ios

import app.stickerdock.core.Rating
import app.stickerdock.core.StickerKind
import app.stickerdock.core.Webp
import app.stickerdock.ios.export.Archives
import app.stickerdock.ios.imports.ArchiveImport
import app.stickerdock.ios.imports.Checked
import app.stickerdock.ios.imports.FolderImport
import app.stickerdock.ios.imports.FolderSource
import app.stickerdock.ios.imports.StickerCheck
import app.stickerdock.ios.zip.ArraySink
import app.stickerdock.ios.zip.ArraySource
import app.stickerdock.ios.zip.ZipReader
import app.stickerdock.ios.zip.ZipWriter
import app.stickerdock.ios.zip.ZlibInflater
import app.stickerdock.library.StickerFilter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportTest {
    private fun zipOf(vararg files: Pair<String, ByteArray>): ZipReader {
        val sink = ArraySink()
        ZipWriter(sink).apply { files.forEach { (name, bytes) -> add(name, bytes) }; finish() }
        return ZipReader(ArraySource(sink.toByteArray()), ZlibInflater)
    }

    @Test fun keepsValidStickersByteExactAndExplainsWhatIphoneCantConvert() {
        val valid = TestWebp.vp8(salt = 1)
        val ok = assertIs<Checked.Ok>(noHash.check(valid))
        assertContentEquals(valid, ok.bytes)
        assertFalse(ok.repaired)
        assertEquals(StickerCheck.NEEDS_CONVERSION, assertIs<Checked.Fail>(noHash.check(TestWebp.pngHeader(300, 300))).reason)
        assertEquals(StickerCheck.NEEDS_RESIZE, assertIs<Checked.Fail>(noHash.check(TestWebp.vp8(300, 300))).reason)
        // A small animated canvas is centred on 512 × 512 without re-encoding.
        val repaired = assertIs<Checked.Ok>(noHash.check(TestWebp.animated(width = 256, height = 256)))
        assertTrue(repaired.repaired)
        assertEquals(StickerKind.ANIMATED, repaired.kind)
        assertEquals(512, Webp.inspect(repaired.bytes)?.width)
        assertTrue(StickerCheck.isTrayIcon(TestWebp.pngHeader(96, 96)))
    }

    @Test fun backupRoundTripKeepsLooseStickersPacksAndFavourites() {
        val (source, _) = newTestLibrary()
        val ids = (1..4).map { source.addTestSticker(TestWebp.vp8(salt = it), label = "sticker $it") }
        val animated = source.addTestSticker(TestWebp.animated(salt = 9), label = "moving")
        source.setFavorite(ids[1], true)
        val pack = source.createPack("Chai time", StickerKind.STATIC)
        source.addToPack(pack, ids.take(3))
        source.setShelf(pack, true)
        val vault = source.addSticker(TestWebp.vp8(salt = 77), app.stickerdock.library.Origin.IMPORT, vault = true)
        assertIs<app.stickerdock.library.AddResult.Added>(vault)

        val sink = ArraySink()
        assertEquals(5, Archives.writeBackup(source, sink, now = 1_793_853_058_000L))

        val (target, _) = newTestLibrary()
        val summary = ArchiveImport(target, noHash).import(ZipReader(ArraySource(sink.toByteArray()), ZlibInflater), "StickerDock-backup.zip")
        assertEquals(5, summary.added)
        assertEquals(1, summary.packs)
        assertEquals(0, summary.rejected)
        val restored = target.stickers(StickerFilter(limit = 100))
        assertEquals(setOf("sticker 1", "sticker 2", "sticker 3", "sticker 4", "moving"), restored.map { it.label }.toSet())
        assertTrue(restored.single { it.label == "sticker 2" }.favorite)
        assertTrue(target.stickers(StickerFilter(vault = true)).isEmpty())
        val restoredPack = target.packs().single()
        assertEquals("Chai time", restoredPack.name)
        assertEquals(3, restoredPack.count)
        assertTrue(restoredPack.onShelf)
        // Same backup again: everything is already there.
        val again = ArchiveImport(target, noHash).import(ZipReader(ArraySource(sink.toByteArray()), ZlibInflater), "again.zip")
        assertEquals(0, again.added)
        assertEquals(5, again.duplicates)
        assertTrue(animated.isNotEmpty())
    }

    @Test fun wastickersShareRoundTripKeepsMetadataAndRating() {
        val (source, _) = newTestLibrary()
        val ids = (1..3).map { source.addTestSticker(TestWebp.vp8(salt = it), label = "lol $it") }
        val pack = source.createPack("Savage", StickerKind.STATIC, Rating.ADULT)
        source.addToPack(pack, ids)
        val tray = TestWebp.pngHeader(96, 96)
        val sink = ArraySink()
        Archives.writePack(source, pack, tray, sink, now = 0L)
        val zip = ZipReader(ArraySource(sink.toByteArray()), ZlibInflater)
        assertEquals("Savage", zip.read(zip.entries.first { it.name == "title.txt" }, 100).decodeToString())
        assertContentEquals(tray, zip.read(zip.entries.first { it.name == "tray.png" }, 100))
        assertEquals(3, zip.entries.count { it.name.endsWith(".webp") })

        val (target, _) = newTestLibrary()
        val summary = ArchiveImport(target, noHash).import(zip, "Savage.wastickers")
        assertEquals(3, summary.added)
        assertEquals(1, summary.packs)
        val imported = target.packs(includeAdult = true).single()
        assertEquals("Savage · 18+", imported.name) // autoPack marks 18+ packs
        assertEquals(Rating.ADULT, imported.rating)
        assertEquals(listOf("lol 1", "lol 2", "lol 3"), target.packStickers(imported.id).map { it.label })
    }

    @Test fun looseArchiveGroupsByFolderSkipsTrayAndExplainsRejects() {
        val zip = zipOf(
            "Bollywood/a.webp" to TestWebp.vp8(salt = 1),
            "Bollywood/b.webp" to TestWebp.vp8(salt = 2),
            "Bollywood/c.webp" to TestWebp.vp8(salt = 3),
            "tray.png" to TestWebp.pngHeader(96, 96),
            "photo.png" to TestWebp.pngHeader(800, 600),
            "__MACOSX/._a.webp" to ByteArray(10),
        )
        val (library, _) = newTestLibrary()
        val summary = ArchiveImport(library, noHash).import(zip, "Stickers.zip")
        assertEquals(3, summary.added)
        assertEquals(1, summary.rejected)
        assertEquals(mapOf(StickerCheck.NEEDS_CONVERSION to 1), summary.reasons)
        assertEquals(listOf("Bollywood"), library.packs().map { it.name })
    }

    @Test fun v1BackupMaturePacksBecomeVaultPacks() {
        val manifest = """{"version":1,"packs":[{"id":"p1","name":"Old private","rating":"MATURE",
            "stickers":[{"name":"s1.webp","label":"one"},{"name":"s2.webp","label":"two"},{"name":"s3.webp","label":"three"}]}]}"""
        val zip = zipOf(
            "manifest.json" to manifest.encodeToByteArray(),
            "packs/p1/s1.webp" to TestWebp.vp8(salt = 11),
            "packs/p1/s2.webp" to TestWebp.vp8(salt = 12),
            "packs/p1/s3.webp" to TestWebp.vp8(salt = 13),
        )
        val (library, _) = newTestLibrary()
        val summary = ArchiveImport(library, noHash).import(zip, "v1.zip")
        assertEquals(3, summary.added)
        assertTrue(library.packs().isEmpty())
        assertEquals("Old private", library.packs(vault = true).single().name)
    }

    @Test fun folderImportJournalsResumesAndPauses() {
        val files = linkedMapOf(
            "WhatsApp Stickers/a.webp" to TestWebp.vp8(salt = 21),
            "WhatsApp Stickers/b.webp" to TestWebp.vp8(salt = 22),
            "WhatsApp Stickers/c.webp" to TestWebp.vp8(salt = 23),
            "WhatsApp Stickers/d.webp" to TestWebp.vp8(salt = 24),
            "WhatsApp Stickers/e.png" to TestWebp.pngHeader(512, 512),
        )
        val source = object : FolderSource {
            override val name = "WhatsApp Stickers"
            override fun images(maxDepth: Int, maxEntries: Int) = files.keys.sorted()
            override fun read(path: String, maxBytes: Int) = files.getValue(path)
        }
        val (library, _) = newTestLibrary()
        val importer = FolderImport(library, noHash)
        val session = library.startImport("/Files/WhatsApp Stickers", FolderImport.MODE)
        var reads = 0
        val paused = importer.run(source, session, stopped = { reads++ >= 2 }) {}
        assertTrue(paused.paused)
        assertEquals(2, paused.added)
        val done = importer.run(source, session, stopped = { false }) {}
        assertFalse(done.paused)
        assertEquals(4, done.added)
        assertEquals(1, done.rejected)
        assertEquals(1, done.packs)
        assertEquals("From WhatsApp", library.packs().single().name)
        assertEquals("DONE", library.importSession(session)?.state)
    }
}
