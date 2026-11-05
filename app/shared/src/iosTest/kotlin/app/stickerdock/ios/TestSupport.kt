package app.stickerdock.ios

import app.stickerdock.ios.imports.StickerCheck
import app.stickerdock.library.AddResult
import app.stickerdock.library.Library
import app.stickerdock.library.Origin
import app.stickerdock.library.StickerFiles

/** Structurally valid WebP containers without a pixel codec (same shapes as jvmTest's TestWebp). */
internal object TestWebp {
    private fun le(value: Int, count: Int) = ByteArray(count) { (value ushr (8 * it)).toByte() }

    private fun chunk(tag: String, payload: ByteArray): ByteArray {
        val pad = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return tag.encodeToByteArray() + le(payload.size, 4) + payload + pad
    }

    private fun riff(body: ByteArray) = "RIFF".encodeToByteArray() + le(body.size + 4, 4) + "WEBP".encodeToByteArray() + body

    fun vp8(width: Int = 512, height: Int = 512, salt: Int = 0, extraBytes: Int = 64): ByteArray {
        val header = byteArrayOf(0x10, 0x02, 0x00, 0x9d.toByte(), 0x01, 0x2a) + le(width, 2) + le(height, 2)
        return riff(chunk("VP8 ", header + ByteArray(extraBytes) { ((it + salt) * 31).toByte() }))
    }

    fun animated(frames: Int = 3, frameMs: Int = 100, width: Int = 512, height: Int = 512, salt: Int = 0): ByteArray {
        val vp8x = byteArrayOf(0x12, 0, 0, 0) + le(width - 1, 3) + le(height - 1, 3)
        var body = chunk("VP8X", vp8x) + chunk("ANIM", ByteArray(6))
        repeat(frames) { i ->
            val frame = vp8(width, height, salt = salt * 31 + i).let { it.copyOfRange(12, it.size) }
            val header = le(0, 3) + le(0, 3) + le(width - 1, 3) + le(height - 1, 3) + le(frameMs, 3) + byteArrayOf(2)
            body += chunk("ANMF", header + frame)
        }
        return riff(body)
    }

    /** PNG signature + IHDR only — enough for size checks, not decodable. */
    fun pngHeader(width: Int, height: Int): ByteArray {
        fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a) +
            be(13) + "IHDR".encodeToByteArray() + be(width) + be(height) + byteArrayOf(8, 6, 0, 0, 0) + ByteArray(4)
    }
}

internal class MemoryFiles : StickerFiles {
    val data = mutableMapOf<String, ByteArray>()
    override fun write(id: String, bytes: ByteArray) { data[id] = bytes }
    override fun read(id: String) = data[id]
    override fun delete(id: String) { data.remove(id) }
    override fun path(id: String) = "/mem/$id.webp"
}

/** No perceptual hashing in pure tests (fake WebP bitstreams can't be decoded anyway). */
internal val noHash = StickerCheck { null }

internal fun Library.addTestSticker(bytes: ByteArray, label: String = ""): String =
    (addSticker(bytes, Origin.IMPORT, label = label) as AddResult.Added).id
