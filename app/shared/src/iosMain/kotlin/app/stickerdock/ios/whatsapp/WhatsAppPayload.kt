package app.stickerdock.ios.whatsapp

import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Webp
import app.stickerdock.ios.imports.StickerCheck
import app.stickerdock.library.Library
import app.stickerdock.library.PackInfo
import app.stickerdock.library.StickerItem
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything WhatsApp needs for one pack, read from the library at send time (iOS packs are snapshots). */
class PackSnapshot(val pack: PackInfo, val stickers: List<Pair<StickerItem, ByteArray>>, val trayPng: ByteArray?) {
    companion object {
        /** Reads the pack's stickers; [tray] turns the cover sticker into a 96 × 96 PNG. */
        fun read(library: Library, packId: String, tray: (ByteArray) -> ByteArray?): PackSnapshot {
            val pack = requireNotNull(library.pack(packId)) { "Pack not found" }
            val items = library.packStickers(packId)
            val stickers = items.map { item -> item to (library.files.read(item.id) ?: error("${item.title}: sticker file is missing")) }
            val coverId = pack.trayStickerId?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id
            val cover = stickers.firstOrNull { it.first.id == coverId }?.second
            return PackSnapshot(pack, stickers, cover?.let(tray))
        }

        /** Just the tray icon (cover sticker, else the first one), without reading the whole pack. */
        fun tray(library: Library, packId: String, tray: (ByteArray) -> ByteArray?): ByteArray? {
            val pack = library.pack(packId) ?: return null
            val coverId = pack.trayStickerId?.takeIf { library.isInPack(packId, it) } ?: library.packPreview(packId).firstOrNull()?.id
            return coverId?.let(library.files::read)?.let(tray)
        }
    }
}

/**
 * The pasteboard payload of WhatsApp's official iOS sample (github.com/WhatsApp/stickers, iOS/):
 * Interoperability.swift puts this JSON on UIPasteboard.general under [PASTEBOARD_TYPE] (local only,
 * 60 s expiry) and opens [SEND_URL]; StickerPack.sendToWhatsApp builds the JSON. Limits follow
 * Limits.swift; StickerRules is equal or stricter (static 100 000 bytes vs 100 KiB).
 */
object WhatsAppPayload {
    const val PASTEBOARD_TYPE = "net.whatsapp.third-party.sticker-pack"
    const val SEND_URL = "whatsapp://stickerPack"
    const val PROBE_URL = "whatsapp://"
    const val PASTEBOARD_SECONDS = 60.0

    /** Everything WhatsApp would reject, worded for the user; empty when the pack can be sent. */
    fun problems(snapshot: PackSnapshot): List<String> {
        val pack = snapshot.pack
        val problems = mutableListOf<String>()
        if (!StickerRules.validIdentifier(pack.id)) problems += "Invalid pack id"
        if (pack.name.isBlank() || pack.name.length > StickerRules.MAX_NAME) problems += "Pack name must be 1–128 characters"
        if (pack.publisher.isBlank() || pack.publisher.length > StickerRules.MAX_NAME) problems += "Publisher must be 1–128 characters"
        val count = snapshot.stickers.size
        if (count !in StickerRules.MIN_PACK..StickerRules.MAX_PACK) problems += "Pack has $count stickers (needs 3–30)"
        val tray = snapshot.trayPng
        when {
            tray == null -> problems += "Couldn't make the tray icon from the cover sticker"
            tray.size > StickerRules.TRAY_MAX_BYTES -> problems += "Tray icon is over 50 KB"
            StickerCheck.pngSize(tray) != StickerRules.TRAY_PIXELS to StickerRules.TRAY_PIXELS -> problems += "Tray icon must be a 96 × 96 PNG"
        }
        snapshot.stickers.forEachIndexed { index, (item, bytes) ->
            StickerRules.problem(pack.kind, Webp.inspect(bytes), bytes.size)?.let { problems += "Sticker ${index + 1}: $it" }
            if (item.whatsAppEmojis.size !in 1..StickerRules.MAX_EMOJIS) problems += "Sticker ${index + 1}: needs 1–3 emojis"
        }
        return problems.distinct()
    }

    /** The JSON string; call only when [problems] is empty. Store links are optional, as in the sample. */
    fun json(snapshot: PackSnapshot, iosAppStoreLink: String? = null, androidPlayStoreLink: String? = null): String {
        val pack = snapshot.pack
        val animated = pack.kind == StickerKind.ANIMATED
        val textLimit = if (animated) StickerRules.ANIMATED_ACCESSIBILITY_MAX else StickerRules.STATIC_ACCESSIBILITY_MAX
        val tray = requireNotNull(snapshot.trayPng) { "Tray icon missing" }
        return buildJsonObject {
            put("identifier", pack.id)
            put("name", pack.name)
            put("publisher", pack.publisher)
            put("tray_image", Base64.encode(tray))
            if (animated) put("animated_sticker_pack", true)
            iosAppStoreLink?.takeIf { it.isNotBlank() }?.let { put("ios_app_store_link", it) }
            androidPlayStoreLink?.takeIf { it.isNotBlank() }?.let { put("android_play_store_link", it) }
            put("stickers", buildJsonArray {
                for ((item, bytes) in snapshot.stickers) add(buildJsonObject {
                    put("image_data", Base64.encode(bytes))
                    put("emojis", JsonArray(item.whatsAppEmojis.map { JsonPrimitive(it) }))
                    item.label.ifBlank { item.tags }.trim().take(textLimit).takeIf { it.isNotEmpty() }?.let { put("accessibility_text", it) }
                })
            })
        }.toString()
    }
}
