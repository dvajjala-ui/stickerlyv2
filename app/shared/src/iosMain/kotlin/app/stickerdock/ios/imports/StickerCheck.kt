package app.stickerdock.ios.imports

import app.stickerdock.core.AnimatedWebpFixer
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerPackExif
import app.stickerdock.core.StickerRules
import app.stickerdock.core.Webp

sealed interface Checked {
    /** WhatsApp-ready WebP bytes; [dhash] (first-frame perceptual hash) is computed on first use. */
    class Ok internal constructor(
        val bytes: ByteArray,
        val kind: StickerKind,
        val exif: StickerPackExif?,
        val repaired: Boolean,
        hasher: (ByteArray) -> Long?,
    ) : Checked {
        val dhash: Long? by lazy { hasher(bytes) }
    }

    data class Fail(val reason: String) : Checked
}

/**
 * iPhone counterpart of Android's StickerNormalizer. iOS decodes WebP (iOS 14+) but has no WebP
 * encoder, so this keeps stickers that already meet WhatsApp's rules byte-for-byte, repairs animated
 * WebP containers with the shared [AnimatedWebpFixer] (no re-encoding), and rejects everything that
 * would need re-encoding (PNG/JPEG/GIF/HEIC, wrong-size static WebP) with a reason the user can act on.
 */
class StickerCheck(private val hasher: (ByteArray) -> Long?) {
    fun check(input: ByteArray): Checked {
        if (input.size > MAX_INPUT_BYTES) return Checked.Fail("File is larger than 20 MB")
        if (!Webp.isWebp(input)) return Checked.Fail(if (looksLikeImage(input)) NEEDS_CONVERSION else "Not an image")
        val info = Webp.inspect(input) ?: return Checked.Fail("Damaged WebP file")
        val exif = Webp.packExif(input)
        val kind = if (info.animated) StickerKind.ANIMATED else StickerKind.STATIC
        if (StickerRules.problem(kind, info, input.size) == null) return Checked.Ok(input, kind, exif, repaired = false, hasher)
        if (!info.animated) return Checked.Fail(NEEDS_RESIZE)
        val fixed = AnimatedWebpFixer.fix(input) ?: return Checked.Fail(NEEDS_REENCODE)
        return Checked.Ok(fixed, kind, exif, repaired = true, hasher)
    }

    companion object {
        const val MAX_INPUT_BYTES = 20_000_000
        const val NEEDS_CONVERSION = "PNG, JPEG, GIF and HEIC need converting to WebP, which iPhone StickerDock can't do yet"
        const val NEEDS_RESIZE = "Static WebP must already be 512 × 512 and under 100 KB (resizing isn't on iPhone yet)"
        const val NEEDS_REENCODE = "Animated WebP needs re-encoding to fit WhatsApp (not on iPhone yet)"

        private fun looksLikeImage(b: ByteArray): Boolean {
            if (b.size < 12) return false
            val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
            val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
            val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
            val heif = b.decodeToString(4, 8) == "ftyp"
            return png || jpeg || gif || heif
        }

        /** Width × height of a PNG (IHDR), or null. */
        fun pngSize(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 24 || b[0] != 0x89.toByte() || b.decodeToString(1, 4) != "PNG" || b.decodeToString(12, 16) != "IHDR") return null
            fun be32(i: Int) = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
                ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
            return be32(16) to be32(20)
        }

        /** `.wastickers` tray icons (96 × 96 PNG or WebP) travel next to the stickers but aren't stickers. */
        fun isTrayIcon(b: ByteArray): Boolean {
            val tray = StickerRules.TRAY_PIXELS to StickerRules.TRAY_PIXELS
            if (pngSize(b) == tray) return true
            return Webp.inspect(b)?.let { it.width to it.height } == tray
        }
    }
}
