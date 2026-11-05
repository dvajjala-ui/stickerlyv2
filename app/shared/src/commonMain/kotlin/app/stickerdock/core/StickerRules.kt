package app.stickerdock.core

/**
 * WhatsApp third-party sticker constraints (github.com/WhatsApp/stickers Android/iOS READMEs).
 * Every export path validates against these before handing a pack to WhatsApp.
 */
object StickerRules {
    const val PIXELS = 512
    const val STATIC_MAX_BYTES = 100_000
    const val ANIMATED_MAX_BYTES = 500_000
    const val MIN_PACK = 3
    const val MAX_PACK = 30
    const val MIN_FRAME_MS = 8
    const val MAX_ANIMATION_MS = 10_000
    const val TRAY_PIXELS = 96
    const val TRAY_MAX_BYTES = 50_000
    const val MAX_EMOJIS = 3
    const val MAX_NAME = 128
    const val STATIC_ACCESSIBILITY_MAX = 125
    const val ANIMATED_ACCESSIBILITY_MAX = 255

    /**
     * WhatsApp's Android README says an app offers 1–10 packs. Whether WhatsApp enforces it is untested
     * (TEST_MATRIX P3), so StickerDock warns past it instead of blocking.
     */
    const val SHELF_GUIDELINE = 10

    /** Characters WhatsApp accepts in a pack identifier. */
    private val identifierPattern = Regex("^[a-zA-Z0-9_.\\- ]{1,128}$")

    fun validIdentifier(id: String) = identifierPattern.matches(id)

    fun maxBytes(kind: StickerKind) = if (kind == StickerKind.ANIMATED) ANIMATED_MAX_BYTES else STATIC_MAX_BYTES

    /** Returns a human-readable problem, or null when [info]/[size] fit a WhatsApp sticker of [kind]. */
    fun problem(kind: StickerKind, info: WebpInfo?, size: Int): String? = when {
        info == null -> "Not a valid WebP image"
        info.width != PIXELS || info.height != PIXELS -> "Must be 512 × 512 (found ${info.width} × ${info.height})"
        size > maxBytes(kind) -> "Too large: ${size / 1000} KB (limit ${maxBytes(kind) / 1000} KB)"
        kind == StickerKind.STATIC && info.animated -> "Animated sticker in a static pack"
        kind == StickerKind.ANIMATED && !info.animated -> "Static sticker in an animated pack"
        kind == StickerKind.ANIMATED && info.minFrameMs < MIN_FRAME_MS -> "Frames must last at least 8 ms"
        kind == StickerKind.ANIMATED && info.durationMs > MAX_ANIMATION_MS -> "Animation must be 10 seconds or shorter"
        else -> null
    }
}

enum class StickerKind { STATIC, ANIMATED }

/** GENERAL is everyone; ADULT is the opt-in 18+ humor lane. Vault is a separate private flag. */
enum class Rating { GENERAL, ADULT }
