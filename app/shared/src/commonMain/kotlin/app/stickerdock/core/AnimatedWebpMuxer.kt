package app.stickerdock.core

/**
 * Builds full-canvas animated WebP files from per-frame static 512² WebP images that a
 * platform encoder produced (Android Bitmap.compress, iOS SDWebImage). Ported from v1.
 */
object AnimatedWebpMuxer {
    fun mux(frames: List<ByteArray>, frameMs: Int, maximumBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray =
        mux(frames, List(frames.size) { frameMs }, maximumBytes)

    /** Per-frame durations (ms); each ≥8, total ≤10 s. */
    fun mux(frames: List<ByteArray>, durations: List<Int>, maximumBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray {
        require(frames.size in 2..120) { "Use 2–120 frames" }
        require(durations.size == frames.size && durations.all { it in StickerRules.MIN_FRAME_MS..65_535 } &&
            durations.sum() <= StickerRules.MAX_ANIMATION_MS) { "Choose a shorter moving clip" }
        val payloads = frames.map(::imageChunks)
        require(payloads.drop(1).any { !it.contentEquals(payloads.first()) }) { "No visible motion in this section" }
        val body = ByteWriter()
        body.write("WEBP".encodeToByteArray())
        body.chunk("VP8X", ByteWriter().apply {
            write(byteArrayOf(0x12, 0, 0, 0)) // alpha + animation
            uint(StickerRules.PIXELS - 1, 3); uint(StickerRules.PIXELS - 1, 3)
        }.toByteArray())
        body.chunk("ANIM", ByteArray(6)) // transparent background, loop forever
        payloads.forEachIndexed { index, image ->
            body.chunk("ANMF", ByteWriter().apply {
                uint(0, 3); uint(0, 3); uint(StickerRules.PIXELS - 1, 3); uint(StickerRules.PIXELS - 1, 3)
                uint(durations[index], 3); write(2) // dispose none, do not blend: each frame replaces the canvas
                write(image)
            }.toByteArray())
            require(body.size() + 8 <= maximumBytes) { "Animation is too detailed. Shorten it or lower the frame rate." }
        }
        val out = ByteWriter().apply { write("RIFF".encodeToByteArray()); uint(body.size(), 4); write(body.toByteArray()) }
            .toByteArray()
        check(Webp.inspect(out)?.frames == frames.size) { "Could not validate animation" }
        return out
    }

    /** Wraps a simple static WebP in a VP8X container (some decoders insist on it). */
    fun extendedStatic(bytes: ByteArray): ByteArray {
        val image = imageChunks(bytes)
        val tag = image.decodeToString(0, 4)
        val alpha = tag == "ALPH" || (tag == "VP8L" && image.size >= 13 && (image[12].toInt() and 0x10) != 0)
        val body = ByteWriter().apply {
            write("WEBP".encodeToByteArray())
            chunk("VP8X", byteArrayOf(if (alpha) 0x10 else 0, 0, 0, 0, 0xff.toByte(), 1, 0, 0xff.toByte(), 1, 0))
            write(image)
        }.toByteArray()
        return ByteWriter().apply { write("RIFF".encodeToByteArray()); uint(body.size, 4); write(body) }.toByteArray()
    }

    /** Extracts ALPH + VP8/VP8L chunks of a single static 512² frame. */
    private fun imageChunks(bytes: ByteArray): ByteArray {
        val info = Webp.inspect(bytes)
        require(info != null && !info.animated && info.width == StickerRules.PIXELS && info.height == StickerRules.PIXELS) {
            "Frames must be static 512px WebP images"
        }
        var offset = 12
        val result = ByteWriter()
        val end = (Webp.u32(bytes, 4) + 8).toInt()
        while (offset + 8 <= end) {
            val tag = Webp.ascii(bytes, offset, 4)
            val length = Webp.u32(bytes, offset + 4).toInt()
            val total = 8 + length + (length and 1)
            if (tag == "ALPH" || tag == "VP8 " || tag == "VP8L") result.write(bytes, offset, minOf(total, end - offset))
            offset += total
        }
        return result.toByteArray()
    }
}
