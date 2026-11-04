package app.stickerdock.core

/**
 * 64-bit difference hash (dHash) for near-duplicate detection. Platforms decode the sticker's first
 * frame, scale it to 9×8 and pass ARGB pixels; transparency is composited onto white so the same
 * sticker re-encoded or re-saved by WhatsApp hashes the same.
 */
object Dhash {
    const val WIDTH = 9
    const val HEIGHT = 8
    const val NEAR_DUPLICATE = 5

    fun of(argb: IntArray): Long {
        require(argb.size == WIDTH * HEIGHT) { "Expected 9×8 pixels" }
        val lum = DoubleArray(argb.size) { i ->
            val p = argb[i]
            val a = ((p ushr 24) and 0xff) / 255.0
            val r = (p ushr 16) and 0xff; val g = (p ushr 8) and 0xff; val b = p and 0xff
            a * (0.299 * r + 0.587 * g + 0.114 * b) + (1 - a) * 255.0
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH - 1) {
            if (lum[y * WIDTH + x] > lum[y * WIDTH + x + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    fun distance(a: Long, b: Long): Int = (a xor b).countOneBits()

    /**
     * False for near-uniform frames (e.g. an animation that starts blank): their hashes collide across
     * unrelated stickers, so they must not be used for duplicate detection.
     */
    fun informative(argb: IntArray): Boolean {
        val lum = argb.map { p ->
            val a = ((p ushr 24) and 0xff) / 255.0
            a * (0.299 * ((p ushr 16) and 0xff) + 0.587 * ((p ushr 8) and 0xff) + 0.114 * (p and 0xff)) + (1 - a) * 255.0
        }
        val mean = lum.average()
        val variance = lum.sumOf { (it - mean) * (it - mean) } / lum.size
        return variance >= 100.0 // std dev ≥ 10 luminance levels
    }
}
