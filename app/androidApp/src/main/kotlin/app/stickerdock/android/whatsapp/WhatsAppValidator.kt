package app.stickerdock.android.whatsapp

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Tags
import app.stickerdock.core.Webp

/**
 * Reads a pack through our own WhatsApp provider exactly as WhatsApp does and applies the checks of the
 * official sample's StickerPackValidator. Used as a pre-flight before every "Add to WhatsApp" and by the
 * device self-test, so a pack is never handed to WhatsApp in a state it would reject.
 */
class WhatsAppValidator(private val context: Context) {
    private val authority = "${context.packageName}.stickers"
    private val identifierPattern = Regex("[\\w\\-.,' ]+")

    data class Report(val packId: String, val name: String, val stickers: Int, val problems: List<String>) {
        val ok get() = problems.isEmpty()
    }

    fun validateAll(): List<Report> = packIds().map(::validate)

    fun validate(packId: String): Report {
        val problems = mutableListOf<String>()
        val resolver = context.contentResolver
        var name = "?"
        var animated = false
        var tray = ""
        resolver.query(Uri.parse("content://$authority/metadata/$packId"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) problems += "Pack is not on the WhatsApp shelf or has fewer than 3 stickers"
            else {
                name = c.getString(c.getColumnIndexOrThrow("sticker_pack_name"))
                val publisher = c.getString(c.getColumnIndexOrThrow("sticker_pack_publisher"))
                tray = c.getString(c.getColumnIndexOrThrow("sticker_pack_icon"))
                animated = c.getInt(c.getColumnIndexOrThrow("animated_sticker_pack")) == 1
                if (!identifierPattern.matches(packId) || packId.length > StickerRules.MAX_NAME) problems += "Invalid pack id"
                if (name.isBlank() || name.length > StickerRules.MAX_NAME) problems += "Pack name must be 1–128 characters"
                if (publisher.isBlank() || publisher.length > StickerRules.MAX_NAME) problems += "Publisher must be 1–128 characters"
                if (c.getString(c.getColumnIndexOrThrow("image_data_version")).isNullOrBlank()) problems += "Missing image_data_version"
            }
        } ?: problems.add("Provider returned nothing")
        if (problems.isNotEmpty()) return Report(packId, name, 0, problems)

        // Tray: 24–512 px each side, ≤50 KB.
        val trayBytes = open("stickers_asset/$packId/$tray")
        if (trayBytes == null) problems += "Tray icon missing"
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(trayBytes, 0, trayBytes.size, bounds)
            if (trayBytes.size > StickerRules.TRAY_MAX_BYTES) problems += "Tray icon is over 50 KB"
            if (bounds.outWidth !in 24..512 || bounds.outHeight !in 24..512) problems += "Tray icon must be 24–512 px (is ${bounds.outWidth}×${bounds.outHeight})"
        }

        var count = 0
        resolver.query(Uri.parse("content://$authority/stickers/$packId"), null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                count++
                val file = c.getString(0)
                val emojis = c.getString(1).split(',').filter { it.isNotBlank() }
                val label = c.getString(2).orEmpty()
                if (emojis.isEmpty() || emojis.size > StickerRules.MAX_EMOJIS) problems += "$file: needs 1–3 emojis"
                if (emojis.any { Tags.emojis(it, 1).isEmpty() }) problems += "$file: emoji field has non-emoji text"
                val limit = if (animated) StickerRules.ANIMATED_ACCESSIBILITY_MAX else StickerRules.STATIC_ACCESSIBILITY_MAX
                if (label.length > limit) problems += "$file: accessibility text over $limit characters"
                val bytes = open("stickers_asset/$packId/$file")
                if (bytes == null) { problems += "$file: file missing"; continue }
                val kind = if (animated) StickerKind.ANIMATED else StickerKind.STATIC
                StickerRules.problem(kind, Webp.inspect(bytes), bytes.size)?.let { problems += "$file: $it" }
            }
        }
        if (count !in StickerRules.MIN_PACK..StickerRules.MAX_PACK) problems += "Pack has $count stickers (needs 3–30)"
        return Report(packId, name, count, problems.distinct())
    }

    private fun packIds(): List<String> =
        context.contentResolver.query(Uri.parse("content://$authority/metadata"), null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.orEmpty()

    private fun open(path: String): ByteArray? = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse("content://$authority/$path"), "r")?.use { afd ->
            afd.createInputStream().use { it.readBytes() }
        }
    }.getOrNull()
}
