package app.stickerdock.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Facts read from a WebP container without decoding pixels. */
data class WebpInfo(
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val frames: Int,
    val durationMs: Int,
    val minFrameMs: Int,
    val hasAlpha: Boolean,
    val hasExif: Boolean,
    val extended: Boolean,
)

/** WhatsApp writes this JSON into a sticker's EXIF chunk; it survives in received-sticker caches. */
data class StickerPackExif(
    val packId: String?,
    val packName: String?,
    val publisher: String?,
    val emojis: List<String>,
)

/**
 * RIFF/WebP parsing per Google's container spec. Supports simple lossy (`VP8 `), simple
 * lossless (`VP8L`) and extended (`VP8X`, incl. animation) files. v1 only understood VP8X,
 * which rejected opaque stickers produced by Android's encoder.
 */
object Webp {
    fun isWebp(bytes: ByteArray) = bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP"

    fun isAnimated(bytes: ByteArray) = isWebp(bytes) && bytes.size >= 21 && ascii(bytes, 12, 4) == "VP8X" &&
        (bytes[20].toInt() and 0x02) != 0

    fun inspect(bytes: ByteArray): WebpInfo? {
        if (bytes.size < 20 || !isWebp(bytes)) return null
        val riff = u32(bytes, 4)
        if (riff < 4 || riff + 8 > bytes.size) return null
        val end = (riff + 8).toInt()
        var offset = 12
        var width = 0; var height = 0
        var extended = false; var animatedFlag = false; var alpha = false; var exif = false
        var animHeader = false; var frames = 0; var duration = 0L; var minFrame = Int.MAX_VALUE
        var simpleImages = 0
        while (offset + 8 <= end) {
            val tag = ascii(bytes, offset, 4)
            val length = u32(bytes, offset + 4)
            val start = offset + 8
            if (length > Int.MAX_VALUE || start + length > end) return null
            val len = length.toInt()
            when (tag) {
                "VP8X" -> {
                    if (offset != 12 || len < 10) return null
                    extended = true
                    val flags = bytes[start].toInt()
                    animatedFlag = flags and 0x02 != 0
                    alpha = alpha || flags and 0x10 != 0
                    exif = exif || flags and 0x08 != 0
                    width = 1 + u24(bytes, start + 4)
                    height = 1 + u24(bytes, start + 7)
                }
                "VP8 " -> {
                    val dims = vp8Dimensions(bytes, start, len) ?: return null
                    simpleImages++
                    if (!extended) { width = dims.first; height = dims.second }
                }
                "VP8L" -> {
                    if (len < 5 || bytes[start].toInt() and 255 != 0x2f) return null
                    val bits = u32(bytes, start + 1).toInt()
                    simpleImages++
                    if (!extended) {
                        width = 1 + (bits and 0x3fff)
                        height = 1 + ((bits ushr 14) and 0x3fff)
                        alpha = alpha || (bits ushr 28) and 1 != 0
                    }
                }
                "ALPH" -> alpha = true
                "EXIF" -> exif = true
                "ANIM" -> animHeader = len >= 6
                "ANMF" -> {
                    if (len < 16) return null
                    val x = 2 * u24(bytes, start); val y = 2 * u24(bytes, start + 3)
                    val w = 1 + u24(bytes, start + 6); val h = 1 + u24(bytes, start + 9)
                    val frameMs = u24(bytes, start + 12)
                    if (extended && (x + w > width || y + h > height)) return null
                    frames++
                    duration += frameMs
                    minFrame = minOf(minFrame, frameMs)
                }
            }
            offset = start + len + (len and 1)
        }
        if (width <= 0 || height <= 0) return null
        val animated = animatedFlag
        if (animated && (!animHeader || frames == 0)) return null
        if (!animated && (frames != 0 || simpleImages != 1)) return null
        return WebpInfo(
            width = width, height = height, animated = animated,
            frames = if (animated) frames else 1,
            durationMs = if (animated) duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
            minFrameMs = if (animated) minFrame else 0,
            hasAlpha = alpha, hasExif = exif, extended = extended,
        )
    }

    /** Reads WhatsApp's `sticker-pack-*` EXIF JSON; null when absent or malformed. */
    fun packExif(bytes: ByteArray): StickerPackExif? {
        if (!isWebp(bytes)) return null
        val end = minOf(bytes.size, (u32(bytes, 4) + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var offset = 12
        while (offset + 8 <= end) {
            val length = u32(bytes, offset + 4)
            val start = offset + 8
            if (length > end - start) return null
            val len = length.toInt()
            if (ascii(bytes, offset, 4) == "EXIF" && len <= 65_536) {
                val raw = bytes.decodeToString(start, start + len)
                val open = raw.indexOf('{'); val close = raw.lastIndexOf('}')
                if (open < 0 || close <= open || close - open > 16_384) return null
                val json = runCatching { Json.parseToJsonElement(raw.substring(open, close + 1)) as? JsonObject }
                    .getOrNull() ?: return null
                fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull?.let(::cleanMetadata)?.takeIf { it.isNotEmpty() }
                val emojis = (json["emojis"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it.isNotBlank() }?.take(StickerRules.MAX_EMOJIS).orEmpty()
                val id = text("sticker-pack-id"); val name = text("sticker-pack-name")
                if (id == null && name == null) return null
                return StickerPackExif(id, name, text("sticker-pack-publisher"), emojis)
            }
            offset = start + len + (len and 1)
        }
        return null
    }

    private fun vp8Dimensions(bytes: ByteArray, start: Int, len: Int): Pair<Int, Int>? {
        if (len < 10) return null
        if (bytes[start + 3].toInt() and 255 != 0x9d || bytes[start + 4].toInt() and 255 != 0x01 ||
            bytes[start + 5].toInt() and 255 != 0x2a) return null
        val w = ((bytes[start + 6].toInt() and 255) or ((bytes[start + 7].toInt() and 255) shl 8)) and 0x3fff
        val h = ((bytes[start + 8].toInt() and 255) or ((bytes[start + 9].toInt() and 255) shl 8)) and 0x3fff
        return w to h
    }

    private fun cleanMetadata(value: String) =
        value.trim().filter { it != '/' && it != '\\' && it.code >= 32 && it.code != 127 }.take(60)

    internal fun ascii(bytes: ByteArray, offset: Int, length: Int) = bytes.decodeToString(offset, offset + length)
    internal fun u24(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8) or
        ((b[i + 2].toInt() and 255) shl 16)
    internal fun u32(b: ByteArray, i: Int): Long = (b[i].toLong() and 255) or ((b[i + 1].toLong() and 255) shl 8) or
        ((b[i + 2].toLong() and 255) shl 16) or ((b[i + 3].toLong() and 255) shl 24)
}
