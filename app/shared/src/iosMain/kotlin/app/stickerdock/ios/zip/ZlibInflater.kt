@file:OptIn(ExperimentalForeignApi::class)

package app.stickerdock.ios.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream_s

/** Raw DEFLATE through the system zlib (`platform.zlib`, shipped with Kotlin/Native for Apple targets). */
object ZlibInflater : RawInflater {
    override fun inflate(input: ByteArray, outputSize: Int): ByteArray {
        require(outputSize >= 0) { "Negative size" }
        if (outputSize == 0) return ByteArray(0)
        if (input.isEmpty()) throw ZipException("Damaged compressed data")
        val output = ByteArray(outputSize)
        memScoped {
            val stream = alloc<z_stream_s>()
            stream.zalloc = null
            stream.zfree = null
            stream.opaque = null
            stream.next_in = null
            stream.avail_in = 0u
            // Negative window bits: raw deflate, as stored in ZIP entries.
            if (inflateInit2(stream.ptr, -MAX_WBITS) != Z_OK) throw ZipException("Couldn't start decompression")
            try {
                val result = input.usePinned { source ->
                    output.usePinned { target ->
                        stream.next_in = source.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_in = input.size.toUInt()
                        stream.next_out = target.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_out = outputSize.toUInt()
                        platform.zlib.inflate(stream.ptr, Z_FINISH)
                    }
                }
                if (result != Z_STREAM_END || stream.total_out != outputSize.toULong()) throw ZipException("Damaged compressed data")
            } finally {
                inflateEnd(stream.ptr)
            }
        }
        return output
    }
}
