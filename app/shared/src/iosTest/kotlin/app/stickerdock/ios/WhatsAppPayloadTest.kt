package app.stickerdock.ios

import app.stickerdock.core.StickerKind
import app.stickerdock.core.Webp
import app.stickerdock.ios.whatsapp.PackSnapshot
import app.stickerdock.ios.whatsapp.WhatsAppPayload
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WhatsAppPayloadTest {
    private val tray = TestWebp.pngHeader(96, 96)

    @Test fun staticPackMatchesTheOfficialSampleJson() {
        val (library, _) = newTestLibrary()
        val stickers = (1..3).map { TestWebp.vp8(salt = it) }
        val ids = stickers.mapIndexed { i, bytes -> library.addTestSticker(bytes, label = if (i == 0) "haha so funny" else "") }
        val pack = library.createPack("Office chai", StickerKind.STATIC)
        library.addToPack(pack, ids)

        val snapshot = PackSnapshot.read(library, pack) { tray }
        assertEquals(emptyList(), WhatsAppPayload.problems(snapshot))
        val json = Json.parseToJsonElement(WhatsAppPayload.json(snapshot, iosAppStoreLink = "https://apps.apple.com/app/id1")).jsonObject
        assertEquals(pack, json.string("identifier"))
        assertEquals("Office chai", json.string("name"))
        assertEquals("StickerDock", json.string("publisher"))
        assertContentEquals(tray, Base64.decode(json.string("tray_image")))
        assertFalse("animated_sticker_pack" in json)
        assertEquals("https://apps.apple.com/app/id1", json.string("ios_app_store_link"))
        assertFalse("android_play_store_link" in json)
        val sent = json.getValue("stickers").jsonArray.map { it.jsonObject }
        assertEquals(3, sent.size)
        sent.forEachIndexed { i, s -> assertContentEquals(stickers[i], Base64.decode(s.string("image_data"))) }
        assertEquals(listOf("😂"), sent[0].getValue("emojis").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("haha so funny", sent[0].string("accessibility_text"))
        assertTrue(sent.all { (it["emojis"] as JsonArray).size in 1..3 })
    }

    @Test fun animatedPackIsFlagged() {
        val (library, _) = newTestLibrary()
        val ids = (1..3).map { library.addTestSticker(TestWebp.animated(salt = it)) }
        val pack = library.createPack("Moves", StickerKind.ANIMATED)
        library.addToPack(pack, ids)
        val json = Json.parseToJsonElement(WhatsAppPayload.json(PackSnapshot.read(library, pack) { tray })).jsonObject
        assertEquals("true", json.getValue("animated_sticker_pack").jsonPrimitive.content)
        assertTrue(Webp.isAnimated(Base64.decode(json.getValue("stickers").jsonArray[0].jsonObject.string("image_data"))))
    }

    @Test fun preflightCatchesWhatWhatsAppRejects() {
        val (library, _) = newTestLibrary()
        val ids = (1..2).map { library.addTestSticker(TestWebp.vp8(salt = it)) }
        val pack = library.createPack("Too small", StickerKind.STATIC)
        library.addToPack(pack, ids)
        assertTrue("Pack has 2 stickers (needs 3–30)" in WhatsAppPayload.problems(PackSnapshot.read(library, pack) { tray }))
        assertTrue(WhatsAppPayload.problems(PackSnapshot.read(library, pack) { null }).any { it.startsWith("Couldn't make the tray icon") })
        assertTrue("Tray icon must be a 96 × 96 PNG" in WhatsAppPayload.problems(PackSnapshot.read(library, pack) { TestWebp.pngHeader(100, 100) }))
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
