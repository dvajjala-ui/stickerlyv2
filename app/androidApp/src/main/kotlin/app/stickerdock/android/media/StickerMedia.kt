package app.stickerdock.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerPackExif
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Webp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface Normalized {
    data class Ok(val bytes: ByteArray, val kind: StickerKind, val exif: StickerPackExif?, val reencoded: Boolean) : Normalized
    data class Fail(val reason: String) : Normalized
}

/**
 * Turns any supported image into WhatsApp-ready WebP. Files that already satisfy WhatsApp's
 * rules are kept byte-for-byte so hashes stay stable across devices and re-imports.
 */
object StickerNormalizer {
    const val MAX_INPUT_BYTES = 20_000_000

    fun normalize(input: ByteArray): Normalized {
        if (input.size > MAX_INPUT_BYTES) return Normalized.Fail("File is larger than 20 MB")
        var exif: StickerPackExif? = null
        if (Webp.isWebp(input)) {
            val info = Webp.inspect(input) ?: return Normalized.Fail("Damaged WebP file")
            exif = Webp.packExif(input)
            val kind = if (info.animated) StickerKind.ANIMATED else StickerKind.STATIC
            val problem = StickerRules.problem(kind, info, input.size)
            if (problem == null) return Normalized.Ok(input, kind, exif, reencoded = false)
            if (info.animated) return Normalized.Fail("Animated sticker can't be used as-is: $problem")
        }
        if (isGif(input)) return Normalized.Fail("GIFs need the animated creator")
        val bitmap = decode(input) ?: return Normalized.Fail("Unsupported or damaged image")
        return try {
            val out = encodeStatic(bitmap) ?: return Normalized.Fail("Couldn't fit the image under 100 KB")
            Normalized.Ok(out, StickerKind.STATIC, exif, reencoded = true)
        } finally {
            bitmap.recycle()
        }
    }

    fun decode(bytes: ByteArray, maxSide: Int = 1024): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val largest = max(w, h)
            if (largest > maxSide) {
                val scale = maxSide.toFloat() / largest
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        }
    }.getOrNull()

    /** Fits [source] centered on a transparent 512² canvas and compresses to ≤100 KB. */
    fun encodeStatic(source: Bitmap, margin: Int = 0): ByteArray? {
        val size = StickerRules.PIXELS
        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(canvasBitmap)
            val box = (size - 2 * margin).toFloat()
            val scale = box / max(source.width, source.height)
            val w = source.width * scale; val h = source.height * scale
            canvas.drawBitmap(source, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            for (quality in intArrayOf(92, 85, 78, 70, 62, 54, 46, 38, 30, 22)) {
                val out = ByteArrayOutputStream()
                check(canvasBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out))
                if (out.size() <= StickerRules.STATIC_MAX_BYTES) return out.toByteArray()
            }
            return null
        } finally {
            canvasBitmap.recycle()
        }
    }

    fun isGif(data: ByteArray) = data.size >= 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte()
}

/** WhatsApp tray icon: 96×96 PNG ≤50 KB derived from the pack's cover sticker. */
object TrayMaker {
    fun make(stickerBytes: ByteArray): ByteArray? {
        val bitmap = StickerNormalizer.decode(stickerBytes, maxSide = 512) ?: return null
        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, StickerRules.TRAY_PIXELS, StickerRules.TRAY_PIXELS, true)
            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (out.size() <= StickerRules.TRAY_MAX_BYTES) return out.toByteArray()
                val lossy = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, lossy)
                return lossy.toByteArray().takeIf { it.size <= StickerRules.TRAY_MAX_BYTES }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Cached per pack version; stale versions are removed. */
    fun cached(dir: File, packId: String, version: Long, coverBytes: () -> ByteArray?): File? {
        dir.mkdirs()
        val target = File(dir, "$packId-$version.png")
        if (target.isFile) return target
        val bytes = coverBytes()?.let(::make) ?: return null
        dir.listFiles { f -> f.name.startsWith("$packId-") }?.forEach { it.delete() }
        val tmp = File(dir, "$packId-$version.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        return target.takeIf { it.isFile }
    }
}
