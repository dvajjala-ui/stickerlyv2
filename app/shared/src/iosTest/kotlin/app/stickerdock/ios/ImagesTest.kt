package app.stickerdock.ios

import app.stickerdock.core.Dhash
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Webp
import app.stickerdock.ios.imports.Checked
import app.stickerdock.ios.imports.StickerCheck
import app.stickerdock.ios.zip.ArraySink
import app.stickerdock.ios.zip.ArraySource
import app.stickerdock.ios.zip.ZipReader
import app.stickerdock.ios.zip.ZipWriter
import app.stickerdock.ios.zip.ZlibInflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory

/** Platform behaviour the app relies on: UIImage decodes WebP, the renderer makes WhatsApp's tray PNG, files map. */
class ImagesTest {
    @Test fun fixtureIsAValidStaticSticker() {
        val info = assertNotNull(Webp.inspect(realWebp))
        assertEquals(512, info.width)
        assertTrue(StickerRules.problem(StickerKind.STATIC, info, realWebp.size) == null)
    }

    @Test fun trayIconIs96PxPngUnder50Kb() {
        val png = assertNotNull(Images.trayPng(realWebp))
        assertEquals(StickerRules.TRAY_PIXELS to StickerRules.TRAY_PIXELS, StickerCheck.pngSize(png))
        assertTrue(png.size <= StickerRules.TRAY_MAX_BYTES)
        assertNull(Images.trayPng(ByteArray(64) { 1 })) // not an image
    }

    @Test fun perceptualHashIsStableAndUsedByTheCheck() {
        val hash = assertNotNull(Images.dhash(realWebp))
        assertEquals(hash, Images.dhash(realWebp.copyOf()))
        val checked = assertIs<Checked.Ok>(StickerCheck(Images::dhash).check(realWebp))
        assertEquals(hash, checked.dhash)
        assertTrue(Dhash.distance(hash, hash) == 0)
    }

    @Test fun mappedFileAndFileSinkRoundTrip() {
        val path = Dirs.freshTemp("tests") + "/round-trip.zip"
        val sink = FileSink(path)
        val memory = ArraySink()
        listOf(sink, memory).forEach { out -> ZipWriter(out).apply { add("a.webp", realWebp); add("b.txt", "hi".encodeToByteArray()); finish() } }
        sink.close()
        val file = MappedFile(path)
        assertEquals(memory.size.toLong(), file.size)
        val zip = ZipReader(file, ZlibInflater)
        assertEquals(realWebp.toList(), zip.read(zip.entries[0], 10_000).toList())
        assertEquals("hi", zip.read(zip.entries[1], 10).decodeToString())
        assertEquals(ZipReader(ArraySource(memory.toByteArray()), ZlibInflater).entries.map { it.name }, zip.entries.map { it.name })
        assertTrue(NSTemporaryDirectory().isNotEmpty())
        Dirs.remove(path)
    }
}
