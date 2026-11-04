package app.stickerdock

/** Builds minimal but structurally valid WebP containers for tests (no pixel codec needed). */
object TestWebp {
    private fun le(value: Int, count: Int) = ByteArray(count) { (value ushr (8 * it)).toByte() }

    private fun chunk(tag: String, payload: ByteArray): ByteArray {
        val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return tag.encodeToByteArray() + le(payload.size, 4) + payload + pad
    }

    private fun riff(body: ByteArray) = "RIFF".encodeToByteArray() + le(body.size + 4, 4) + "WEBP".encodeToByteArray() + body

    /** Simple lossy VP8 with a fake bitstream after a valid frame header. [salt] varies the bytes. */
    fun vp8(width: Int = 512, height: Int = 512, salt: Int = 0, extraBytes: Int = 64): ByteArray {
        val header = byteArrayOf(0x10, 0x02, 0x00, 0x9d.toByte(), 0x01, 0x2a) + le(width, 2) + le(height, 2)
        val payload = header + ByteArray(extraBytes) { ((it + salt) * 31).toByte() }
        return riff(chunk("VP8 ", payload))
    }

    /** Simple lossless VP8L header. */
    fun vp8l(width: Int = 512, height: Int = 512, alpha: Boolean = true): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14) or ((if (alpha) 1 else 0) shl 28)
        val payload = byteArrayOf(0x2f) + le(bits, 4) + ByteArray(32) { it.toByte() }
        return riff(chunk("VP8L", payload))
    }

    fun extendedStatic(width: Int = 512, height: Int = 512, exifJson: String? = null): ByteArray {
        val flags = if (exifJson != null) 0x08 else 0
        val vp8x = byteArrayOf(flags.toByte(), 0, 0, 0) + le(width - 1, 3) + le(height - 1, 3)
        val image = vp8(width, height).copyOfRange(12, vp8(width, height).size)
        var body = chunk("VP8X", vp8x) + image
        if (exifJson != null) body += chunk("EXIF", ("II*\u0000" + exifJson).encodeToByteArray())
        return riff(body)
    }

    fun animated(frames: Int = 3, frameMs: Int = 100, width: Int = 512, height: Int = 512, extraBytes: Int = 64): ByteArray {
        val vp8x = byteArrayOf(0x12, 0, 0, 0) + le(width - 1, 3) + le(height - 1, 3)
        var body = chunk("VP8X", vp8x) + chunk("ANIM", ByteArray(6))
        repeat(frames) { i ->
            val frame = vp8(width, height, salt = i, extraBytes = extraBytes).let { it.copyOfRange(12, it.size) }
            val header = le(0, 3) + le(0, 3) + le(width - 1, 3) + le(height - 1, 3) + le(frameMs, 3) + byteArrayOf(2)
            body += chunk("ANMF", header + frame)
        }
        return riff(body)
    }
}
