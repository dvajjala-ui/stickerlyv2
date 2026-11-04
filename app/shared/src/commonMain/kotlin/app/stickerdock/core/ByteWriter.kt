package app.stickerdock.core

/** Bounded growable byte buffer with no platform I/O dependency. */
internal class ByteWriter(private val limit: Int = 1_048_576) {
    private var bytes = ByteArray(1024)
    private var used = 0

    fun size() = used
    fun toByteArray() = bytes.copyOf(used)

    fun write(value: Int) {
        reserve(1)
        bytes[used++] = value.toByte()
    }

    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= source.size)
        reserve(length)
        source.copyInto(bytes, used, offset, offset + length)
        used += length
    }

    fun uint(value: Int, count: Int) = repeat(count) { write((value ushr (8 * it)) and 255) }

    fun chunk(tag: String, payload: ByteArray) {
        write(tag.encodeToByteArray()); uint(payload.size, 4); write(payload)
        if (payload.size and 1 != 0) write(0)
    }

    private fun reserve(length: Int) {
        val needed = used.toLong() + length
        require(needed <= limit) { "Image data exceeds ${limit / 1000} KB" }
        if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed.toInt(), minOf(bytes.size * 2, limit)))
    }
}
