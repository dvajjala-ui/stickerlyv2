package app.stickerdock.ios

import app.stickerdock.ios.zip.ArraySink
import app.stickerdock.ios.zip.ArraySource
import app.stickerdock.ios.zip.Crc32
import app.stickerdock.ios.zip.ZipException
import app.stickerdock.ios.zip.ZipReader
import app.stickerdock.ios.zip.ZipWriter
import app.stickerdock.ios.zip.ZlibInflater
import app.stickerdock.ios.zip.dosDateTime
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipTest {
    /**
     * Written by java.util.zip.ZipOutputStream (what Android's Exporters use): DEFLATE entries with data
     * descriptors (sizes only in the central directory), an empty entry, a directory, a STORED entry and
     * a UTF-8 name.
     */
    private val javaZip = Base64.decode(
        "UEsDBBQACAgIADstZV0AAAAAAAAAAAAAAAANAAAAbWFuaWZlc3QuanNvbqtWKk7OSM1NVLJSKkktLlHSUcrLL0kF8jJSc3LyFYpLMpOzU4sURnmjPPw8pVoA" +
            "UEsHCH4J9o8vAAAASwIAAFBLAwQUAAgICAA7LWVdAAAAAAAAAAAAAAAADwAAAHN0aWNrZXJzL2Eud2VicGNg5xOVUdYytLB38w2JTsosKK9r7Zk8a+GK9dv2" +
            "Hjl96eaD5+++/mHmEpSQV9M1sXbyDAiPS80prmrs6J82d8nqTTsPHD939c7jVx9//GfjFZFW0jQwt3P1CY5KzMgvq23pnjRzwfJ1W/ccPnXxxv1nb7/8ZuIU" +
            "EJdT1TG2cvTwD4tNyS6qbGjvmzpn8aqNO/YfO3vl9qOXH77/Y+URllLU0DezdfEOikxIzyutae6aOGP+srVbdh86eeH6vadvPv9i5OAXk1XRNrJ0cPcLjUnO" +
            "Kqyob+udMnvRyg3b9x09c/nWwxfvv/1l4RaSVFDXM7Vx9gqMiE/LLalu6pwwfd7SNZt3HTxx/trdJ68//WQgwf8AUEsHCPuxAOATAQAALAEAAFBLAwQUAAgI" +
            "CAA7LWVdAAAAAAAAAAAAAAAACQAAAGVtcHR5LnR4dAMAUEsHCAAAAAACAAAAAAAAAFBLAwQUAAgICAA7LWVdAAAAAAAAAAAAAAAABwAAAGZvbGRlci8DAFBL" +
            "BwgAAAAAAgAAAAAAAABQSwMECgAACAAAOy1lXf95DLUyAAAAMgAAAAoAAABzdG9yZWQuYmluAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIj" +
            "JCUmJygpKissLS4vMDFQSwMEFAAICAgAOy1lXQAAAAAAAAAAAAAAABQAAABuYcOvdmUgw7xuw69jb2RlLnR4dMvPBgBQSwcIR93ceQQAAAACAAAAUEsBAhQA" +
            "FAAICAgAOy1lXX4J9o8vAAAASwIAAA0AAAAAAAAAAAAAAAAAAAAAAG1hbmlmZXN0Lmpzb25QSwECFAAUAAgICAA7LWVd+7EA4BMBAAAsAQAADwAAAAAAAAAA" +
            "AAAAAABqAAAAc3RpY2tlcnMvYS53ZWJwUEsBAhQAFAAICAgAOy1lXQAAAAACAAAAAAAAAAkAAAAAAAAAAAAAAAAAugEAAGVtcHR5LnR4dFBLAQIUABQACAgI" +
            "ADstZV0AAAAAAgAAAAAAAAAHAAAAAAAAAAAAAAAAAPMBAABmb2xkZXIvUEsBAgoACgAACAAAOy1lXf95DLUyAAAAMgAAAAoAAAAAAAAAAAAAAAAAKgIAAHN0" +
            "b3JlZC5iaW5QSwECFAAUAAgICAA7LWVdR93ceQQAAAACAAAAFAAAAAAAAAAAAAAAAACEAgAAbmHDr3ZlIMO8bsOvY29kZS50eHRQSwUGAAAAAAYABgBeAQAA" +
            "ygIAAAAA",
    )

    @Test fun readsDeflatedArchiveFromJavaZipOutputStream() {
        val zip = ZipReader(ArraySource(javaZip), ZlibInflater)
        assertEquals(listOf("manifest.json", "stickers/a.webp", "empty.txt", "folder/", "stored.bin", "naïve ünïcode.txt"), zip.entries.map { it.name })
        val (manifest, webp, empty, folder, stored, unicode) = zip.entries
        assertEquals("{\"schema\":\"test\",\"note\":\"" + "hello sticker ".repeat(40) + "\"}", zip.read(manifest, 10_000).decodeToString())
        assertContentEquals(ByteArray(300) { (it * 7).toByte() }, zip.read(webp, 10_000))
        assertEquals(0, zip.read(empty, 10).size)
        assertTrue(folder.isDirectory)
        assertEquals(ZipReader.STORED, stored.method)
        assertContentEquals(ByteArray(50) { it.toByte() }, zip.read(stored, 100))
        assertEquals("ok", zip.read(unicode, 10).decodeToString())
    }

    @Test fun writesArchivesThatReadBack() {
        val sink = ArraySink()
        val files = mapOf(
            "title.txt" to "Pack".encodeToByteArray(),
            "stickers/01.webp" to ByteArray(70_000) { (it * 13 + 5).toByte() },
            "empty" to ByteArray(0),
            "हिंदी/😀.webp" to byteArrayOf(1, 2, 3),
        )
        ZipWriter(sink, epochMillis = 1_793_853_058_000L).apply { files.forEach { (name, bytes) -> add(name, bytes) }; finish() }
        val zip = ZipReader(ArraySource(sink.toByteArray()), ZlibInflater)
        assertEquals(files.keys.toList(), zip.entries.map { it.name })
        zip.entries.forEach { assertContentEquals(files.getValue(it.name), zip.read(it, 100_000)) }
    }

    @Test fun rejectsDamagedTooLargeAndForeignData() {
        val sink = ArraySink()
        ZipWriter(sink).apply { add("a.bin", ByteArray(64) { it.toByte() }); finish() }
        val bytes = sink.toByteArray()
        val zip = ZipReader(ArraySource(bytes), ZlibInflater)
        assertFailsWith<ZipException> { zip.read(zip.entries.single(), 10) }
        bytes[30 + "a.bin".length + 3] = 99 // flip one byte of the stored data
        assertFailsWith<ZipException> { ZipReader(ArraySource(bytes), ZlibInflater).let { it.read(it.entries.single(), 100) } }
        assertFailsWith<ZipException> { ZipReader(ArraySource(ByteArray(100) { 7 }), ZlibInflater) }
        assertFailsWith<ZipException> { ZipReader(ArraySource(bytes.copyOf(bytes.size - 30)), ZlibInflater) }
    }

    @Test fun crcMatchesKnownValue() {
        assertEquals(0xCBF43926L.toInt(), Crc32.of("123456789".encodeToByteArray()))
    }

    @Test fun dosTimestampsUseUtcCalendar() {
        assertEquals((4 shl 11) or (30 shl 5) or 29 to ((46 shl 9) or (11 shl 5) or 5), dosDateTime(1_793_853_058_000L)) // 2026-11-05T04:30:58Z
        assertEquals((23 shl 11) or (59 shl 5) or 29 to ((44 shl 9) or (2 shl 5) or 29), dosDateTime(1_709_251_199_000L)) // 2024-02-29T23:59:59Z
        assertEquals(0 to ((1 shl 5) or 1), dosDateTime(315_532_799_000L)) // 1979 → 1980-01-01
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
