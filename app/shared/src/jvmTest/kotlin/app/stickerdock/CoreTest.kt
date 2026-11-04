package app.stickerdock

import app.stickerdock.core.AnimatedWebpMuxer
import app.stickerdock.core.Sha256
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Tags
import app.stickerdock.core.Webp
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test fun sha256MatchesKnownVectorsAndJdk() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        val random = Random(7)
        for (size in listOf(1, 55, 56, 63, 64, 65, 1000, 100_003)) {
            val data = random.nextBytes(size)
            val jdk = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            assertEquals(jdk, Sha256.hex(data), "size $size")
        }
    }

    @Test fun inspectsSimpleLossyWithoutVp8x() {
        // v1 rejected these (VP8X-only parser); Android's encoder writes them for opaque images.
        val info = assertNotNull(Webp.inspect(TestWebp.vp8()))
        assertEquals(512, info.width); assertEquals(512, info.height)
        assertFalse(info.animated); assertFalse(info.extended)
        assertNull(StickerRules.problem(StickerKind.STATIC, info, 10_000))
    }

    @Test fun inspectsLosslessAndExtended() {
        val lossless = assertNotNull(Webp.inspect(TestWebp.vp8l(alpha = true)))
        assertTrue(lossless.hasAlpha); assertEquals(512, lossless.width)
        val ext = assertNotNull(Webp.inspect(TestWebp.extendedStatic()))
        assertTrue(ext.extended); assertFalse(ext.animated)
    }

    @Test fun rejectsWrongSizeAndKindMismatch() {
        val small = assertNotNull(Webp.inspect(TestWebp.vp8(256, 256)))
        assertEquals("Must be 512 × 512 (found 256 × 256)", StickerRules.problem(StickerKind.STATIC, small, 1000))
        val anim = assertNotNull(Webp.inspect(TestWebp.animated()))
        assertEquals("Animated sticker in a static pack", StickerRules.problem(StickerKind.STATIC, anim, 1000))
        assertTrue(StickerRules.problem(StickerKind.STATIC, Webp.inspect(TestWebp.vp8()), 100_001)!!.startsWith("Too large"))
    }

    @Test fun inspectsAnimationTiming() {
        val info = assertNotNull(Webp.inspect(TestWebp.animated(frames = 4, frameMs = 120)))
        assertTrue(info.animated); assertEquals(4, info.frames); assertEquals(480, info.durationMs); assertEquals(120, info.minFrameMs)
        assertNull(StickerRules.problem(StickerKind.ANIMATED, info, 200_000))
        val fast = assertNotNull(Webp.inspect(TestWebp.animated(frames = 3, frameMs = 5)))
        assertEquals("Frames must last at least 8 ms", StickerRules.problem(StickerKind.ANIMATED, fast, 1000))
    }

    @Test fun rejectsTruncatedAndGarbage() {
        assertNull(Webp.inspect(ByteArray(40)))
        val good = TestWebp.vp8()
        assertNull(Webp.inspect(good.copyOf(good.size - 10)))
        assertNull(Webp.inspect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
    }

    @Test fun readsWhatsAppPackExif() {
        val json = """{"sticker-pack-id":"abc123","sticker-pack-name":"Office Pain","sticker-pack-publisher":"Me","emojis":["😂","💼"]}"""
        val exif = assertNotNull(Webp.packExif(TestWebp.extendedStatic(exifJson = json)))
        assertEquals("Office Pain", exif.packName); assertEquals("abc123", exif.packId); assertEquals(listOf("😂", "💼"), exif.emojis)
        assertNull(Webp.packExif(TestWebp.vp8()))
    }

    @Test fun muxesAnimationFromStaticFrames() {
        val frames = (0 until 4).map { TestWebp.vp8(salt = it) }
        val out = AnimatedWebpMuxer.mux(frames, frameMs = 100)
        val info = assertNotNull(Webp.inspect(out))
        assertTrue(info.animated); assertEquals(4, info.frames); assertEquals(400, info.durationMs)
        assertTrue(Webp.isAnimated(out))
        val wrapped = AnimatedWebpMuxer.extendedStatic(TestWebp.vp8())
        assertTrue(assertNotNull(Webp.inspect(wrapped)).extended)
    }

    @Test fun fixerCentresSmallAnimationsOnA512Canvas() {
        val small = TestWebp.animated(frames = 3, frameMs = 90, width = 256, height = 256)
        assertEquals("Must be 512 × 512 (found 256 × 256)", StickerRules.problem(StickerKind.ANIMATED, Webp.inspect(small), small.size))
        val fixed = assertNotNull(app.stickerdock.core.AnimatedWebpFixer.fix(small))
        val info = assertNotNull(Webp.inspect(fixed))
        assertEquals(512, info.width); assertEquals(3, info.frames); assertEquals(270, info.durationMs)
        assertNull(StickerRules.problem(StickerKind.ANIMATED, info, fixed.size))
    }

    @Test fun fixerDropsAlternateKeyframesToFitSizeAndKeepsTiming() {
        val big = TestWebp.animated(frames = 12, frameMs = 100, extraBytes = 60_000)
        assertTrue(big.size > 500_000)
        val fixed = assertNotNull(app.stickerdock.core.AnimatedWebpFixer.fix(big))
        val info = assertNotNull(Webp.inspect(fixed))
        assertTrue(fixed.size <= 500_000)
        assertTrue(info.frames < 12)
        assertEquals(1200, info.durationMs, "dropped frames hand their time to neighbours")
    }

    @Test fun fixerScalesLongAnimationsAndRejectsLargeCanvas() {
        val long = TestWebp.animated(frames = 4, frameMs = 4000)
        val fixed = assertNotNull(app.stickerdock.core.AnimatedWebpFixer.fix(long))
        assertTrue(Webp.inspect(fixed)!!.durationMs <= 10_000)
        assertNull(app.stickerdock.core.AnimatedWebpFixer.fix(TestWebp.animated(frames = 3, width = 640, height = 640)))
    }

    @Test fun normalizesTagsAndEmojis() {
        assertEquals("office pain monday", Tags.normalize("Office, PAIN #monday office"))
        assertEquals(listOf("😂", "👍🏽", "❤️"), Tags.emojis("lol 😂 ok 👍🏽 love ❤️ extra 🔥"))
        assertEquals(listOf("👨‍👩‍👧"), Tags.emojis("family 👨‍👩‍👧"))
        assertEquals(listOf("🇮🇳"), Tags.emojis("india 🇮🇳"))
        assertEquals(listOf("😂"), Tags.whatsAppEmojis("", "haha so funny", ""))
        assertEquals(listOf("😀"), Tags.whatsAppEmojis("", "", ""))
        assertEquals("Good morning", Tags.cleanLabel("Good   morning.webp"))
        assertEquals("%50\\%\\_off%", Tags.searchPattern("50%_off"))
    }
}
