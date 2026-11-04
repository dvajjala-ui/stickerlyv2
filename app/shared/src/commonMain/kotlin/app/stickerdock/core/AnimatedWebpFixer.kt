package app.stickerdock.core

/**
 * Repairs animated WebP files that WhatsApp shows in chats but rejects in third-party packs, without
 * re-encoding a single frame (so no quality loss and no native codec):
 *  - canvas smaller than 512² → frames are centred on a transparent 512² canvas;
 *  - file over 500 KB → if every frame is an independent full-canvas keyframe, every other frame is
 *    dropped and its time given to the previous frame (playback speed is kept);
 *  - total over 10 s → durations are scaled down; frames shorter than 8 ms are raised to 8 ms.
 * Returns null when the file can't be made valid this way (e.g. canvas larger than 512²).
 */
object AnimatedWebpFixer {
    private class Chunk(val tag: String, val payload: ByteArray)

    private class Frame(var x: Int, var y: Int, val w: Int, val h: Int, var duration: Int, val flags: Int, val data: ByteArray) {
        val noBlend get() = flags and 0x02 != 0
    }

    fun fix(bytes: ByteArray, maxBytes: Int = StickerRules.ANIMATED_MAX_BYTES): ByteArray? {
        val chunks = parse(bytes) ?: return null
        val vp8x = chunks.firstOrNull { it.tag == "VP8X" } ?: return null
        if (vp8x.payload.size < 10 || vp8x.payload[0].toInt() and 0x02 == 0) return null
        val canvasW = 1 + Webp.u24(vp8x.payload, 4)
        val canvasH = 1 + Webp.u24(vp8x.payload, 7)
        if (canvasW > StickerRules.PIXELS || canvasH > StickerRules.PIXELS) return null
        val anim = chunks.firstOrNull { it.tag == "ANIM" } ?: return null
        val frames = chunks.filter { it.tag == "ANMF" }.map { c ->
            val p = c.payload
            if (p.size < 16) return null
            Frame(2 * Webp.u24(p, 0), 2 * Webp.u24(p, 3), 1 + Webp.u24(p, 6), 1 + Webp.u24(p, 9), Webp.u24(p, 12),
                p[15].toInt() and 0xff, p.copyOfRange(16, p.size))
        }.toMutableList()
        if (frames.size < 2) return null
        val keyframesOnly = frames.all { it.x == 0 && it.y == 0 && it.w == canvasW && it.h == canvasH && it.noBlend }

        // 1) Centre on a 512² canvas (offsets must be even: they are stored halved).
        val dx = ((StickerRules.PIXELS - canvasW) / 2) and 1.inv()
        val dy = ((StickerRules.PIXELS - canvasH) / 2) and 1.inv()
        frames.forEach { it.x += dx; it.y += dy }

        // 2) Timing: ≥8 ms per frame, ≤10 s overall.
        frames.forEach { if (it.duration < StickerRules.MIN_FRAME_MS) it.duration = StickerRules.MIN_FRAME_MS }
        fitDuration(frames)

        val others = chunks.filter { it.tag !in setOf("VP8X", "ANIM", "ANMF") }
        var out = build(vp8x.payload, anim.payload, frames, others)
        // 3) Size: drop alternate keyframes, merging their time into the kept neighbour.
        while (out.size > maxBytes && keyframesOnly && frames.size > 2) {
            val kept = mutableListOf<Frame>()
            frames.forEachIndexed { i, f -> if (i % 2 == 0) kept += f else kept.last().duration += f.duration }
            frames.clear(); frames += kept
            fitDuration(frames)
            out = build(vp8x.payload, anim.payload, frames, others)
        }
        if (out.size > maxBytes) return null
        val info = Webp.inspect(out) ?: return null
        return out.takeIf { StickerRules.problem(StickerKind.ANIMATED, info, out.size) == null }
    }

    private fun fitDuration(frames: List<Frame>) {
        val total = frames.sumOf { it.duration }
        if (total <= StickerRules.MAX_ANIMATION_MS) return
        val scale = (StickerRules.MAX_ANIMATION_MS - frames.size).toDouble() / total
        frames.forEach { it.duration = maxOf(StickerRules.MIN_FRAME_MS, (it.duration * scale).toInt()) }
    }

    private fun build(vp8x: ByteArray, anim: ByteArray, frames: List<Frame>, others: List<Chunk>): ByteArray {
        val body = ByteWriter(limit = 4_000_000)
        body.write("WEBP".encodeToByteArray())
        val header = vp8x.copyOf()
        header[0] = (header[0].toInt() or 0x12).toByte() // animation + alpha (new margins are transparent)
        writeU24(header, 4, StickerRules.PIXELS - 1)
        writeU24(header, 7, StickerRules.PIXELS - 1)
        body.chunk("VP8X", header)
        others.filter { it.tag == "ICCP" }.forEach { body.chunk(it.tag, it.payload) } // spec order: VP8X, ICCP, ANIM
        val animPayload = anim.copyOf().also { if (it.size >= 4) { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 0 } } // transparent background
        body.chunk("ANIM", animPayload)
        frames.forEach { f ->
            val p = ByteWriter(limit = 4_000_000)
            p.uint(f.x / 2, 3); p.uint(f.y / 2, 3); p.uint(f.w - 1, 3); p.uint(f.h - 1, 3); p.uint(f.duration, 3)
            p.write(f.flags)
            p.write(f.data)
            body.chunk("ANMF", p.toByteArray())
        }
        others.filter { it.tag != "ICCP" }.forEach { body.chunk(it.tag, it.payload) }
        return ByteWriter(limit = 4_000_008).apply { write("RIFF".encodeToByteArray()); uint(body.size(), 4); write(body.toByteArray()) }
            .toByteArray()
    }

    private fun parse(bytes: ByteArray): List<Chunk>? {
        if (!Webp.isWebp(bytes)) return null
        val end = minOf(bytes.size.toLong(), Webp.u32(bytes, 4) + 8).toInt()
        val chunks = mutableListOf<Chunk>()
        var offset = 12
        while (offset + 8 <= end) {
            val tag = Webp.ascii(bytes, offset, 4)
            val length = Webp.u32(bytes, offset + 4)
            val start = offset + 8
            if (length > end - start) return null
            chunks += Chunk(tag, bytes.copyOfRange(start, start + length.toInt()))
            offset = start + length.toInt() + (length.toInt() and 1)
        }
        return chunks
    }

    private fun writeU24(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte(); target[at + 1] = (value ushr 8).toByte(); target[at + 2] = (value ushr 16).toByte()
    }
}
