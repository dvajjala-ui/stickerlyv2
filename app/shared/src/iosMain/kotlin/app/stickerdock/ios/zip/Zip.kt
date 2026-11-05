package app.stickerdock.ios.zip

/*
 * Small ZIP reader/writer for sticker archives. No platform I/O here, so the same code runs in tests.
 *  - Read: STORED and DEFLATE entries (raw inflate is supplied by the platform: zlib on iOS). Sizes and
 *    CRCs come from the central directory, so archives written by streaming writers with data
 *    descriptors (Android's ZipOutputStream, macOS Archive Utility) work. ZIP64 records are understood.
 *    Encrypted entries and other compression methods are rejected with a readable message.
 *  - Write: STORED entries only (WebP and PNG are already compressed), UTF-8 names, at most 65,535
 *    entries and 4 GB, no ZIP64.
 */

/** Random-access bytes: a memory-mapped file on iOS, an array in tests. */
interface ByteSource {
    val size: Long

    /** Exactly [length] bytes starting at [position]. */
    fun read(position: Long, length: Int): ByteArray
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > bytes.size) throw ZipException("Read past the end of the archive")
        val start = position.toInt()
        return bytes.copyOfRange(start, start + length)
    }
}

/** Sequential output: a file on iOS, memory in tests. */
interface ByteSink {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
}

class ArraySink : ByteSink {
    private var buffer = ByteArray(8192)
    var size = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Bad write range" }
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(size + length, buffer.size * 2))
        bytes.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Raw DEFLATE (RFC 1951, no zlib header) decoder. */
fun interface RawInflater {
    /** Inflates [input] to exactly [outputSize] bytes, or throws. */
    fun inflate(input: ByteArray, outputSize: Int): ByteArray
}

class ZipException(message: String) : Exception(message)

object Crc32 {
    private val table = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320L.toInt() else c ushr 1 }
            t[n] = c
        }
    }

    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var c = -1
        for (i in offset until offset + length) c = table[(c xor bytes[i].toInt()) and 0xff] xor (c ushr 8)
        return c.inv()
    }
}

class ZipEntry internal constructor(
    /** Path inside the archive, with '/' separators. */
    val name: String,
    val method: Int,
    val flags: Int,
    val crc: Int,
    val compressedSize: Long,
    val size: Long,
    internal val headerOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith('/')
    val encrypted: Boolean get() = flags and 1 != 0
    override fun toString() = "ZipEntry($name, method=$method, size=$size)"
}

class ZipReader(private val source: ByteSource, private val inflater: RawInflater, maxEntries: Int = 65_535) {
    val entries: List<ZipEntry> = readDirectory(maxEntries)

    /** Decompresses [entry]; throws [ZipException] when it is larger than [maxSize], damaged or unsupported. */
    fun read(entry: ZipEntry, maxSize: Int): ByteArray {
        if (entry.encrypted) throw ZipException("${entry.name} is encrypted")
        if (entry.size > maxSize) throw ZipException("${entry.name} is larger than ${maxSize / 1_000_000} MB")
        val local = source.read(entry.headerOffset, LOCAL_HEADER)
        if (u32(local, 0) != LOCAL_SIG) throw ZipException("Damaged archive (${entry.name})")
        val dataStart = entry.headerOffset + LOCAL_HEADER + u16(local, 26) + u16(local, 28)
        if (dataStart + entry.compressedSize > source.size) throw ZipException("Damaged archive (${entry.name} is cut off)")
        val size = entry.size.toInt()
        val data = when (entry.method) {
            STORED -> {
                if (entry.compressedSize != entry.size) throw ZipException("Damaged archive (${entry.name})")
                source.read(dataStart, size)
            }
            DEFLATED -> {
                // Deflate never expands data by more than a few bytes per 16 KB block.
                if (entry.compressedSize > entry.size + entry.size / 100 + 1024) throw ZipException("Damaged archive (${entry.name})")
                val packed = source.read(dataStart, entry.compressedSize.toInt())
                if (size == 0) ByteArray(0) else inflater.inflate(packed, size)
            }
            else -> throw ZipException("${entry.name} uses an unsupported compression method (${entry.method})")
        }
        if (data.size != size || Crc32.of(data) != entry.crc) throw ZipException("${entry.name} is damaged (checksum mismatch)")
        return data
    }

    private fun readDirectory(maxEntries: Int): List<ZipEntry> {
        val total = source.size
        if (total < EOCD_SIZE) throw ZipException("Not a ZIP archive")
        val tailLength = minOf(total, (EOCD_SIZE + 0xFFFF).toLong()).toInt()
        val tailStart = total - tailLength
        val tail = source.read(tailStart, tailLength)
        var eocd = -1
        for (i in tailLength - EOCD_SIZE downTo 0) {
            if (u32(tail, i) == EOCD_SIG && i + EOCD_SIZE + u16(tail, i + 20) <= tailLength) { eocd = i; break }
        }
        if (eocd < 0) throw ZipException("Not a ZIP archive")
        var count = u16(tail, eocd + 10).toLong()
        var directorySize = u32(tail, eocd + 12)
        var directoryOffset = u32(tail, eocd + 16)
        if (count == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
            // ZIP64: the end-of-central-directory locator sits right before the classic record.
            val locatorAt = tailStart + eocd - ZIP64_LOCATOR_SIZE
            if (locatorAt >= 0) {
                val locator = source.read(locatorAt, ZIP64_LOCATOR_SIZE)
                if (u32(locator, 0) == ZIP64_LOCATOR_SIG) {
                    val recordAt = u64(locator, 8)
                    if (recordAt < 0 || recordAt + ZIP64_EOCD_SIZE > total) throw ZipException("Damaged archive (ZIP64 record)")
                    val record = source.read(recordAt, ZIP64_EOCD_SIZE)
                    if (u32(record, 0) != ZIP64_EOCD_SIG) throw ZipException("Damaged archive (ZIP64 record)")
                    count = u64(record, 32)
                    directorySize = u64(record, 40)
                    directoryOffset = u64(record, 48)
                }
            }
        }
        if (count < 0 || count > maxEntries) throw ZipException("Archive has too many files")
        if (directoryOffset < 0 || directorySize < 0 || directorySize > MAX_DIRECTORY || directoryOffset + directorySize > total) {
            throw ZipException("Damaged archive (central directory)")
        }
        val directory = source.read(directoryOffset, directorySize.toInt())
        val result = ArrayList<ZipEntry>(count.toInt())
        var p = 0
        repeat(count.toInt()) {
            if (p + CENTRAL_HEADER > directory.size || u32(directory, p) != CENTRAL_SIG) throw ZipException("Damaged archive (central directory)")
            val flags = u16(directory, p + 8)
            val method = u16(directory, p + 10)
            val crc = u32(directory, p + 16).toInt()
            var compressed = u32(directory, p + 20)
            var uncompressed = u32(directory, p + 24)
            val nameLength = u16(directory, p + 28)
            val extraLength = u16(directory, p + 30)
            val commentLength = u16(directory, p + 32)
            var headerOffset = u32(directory, p + 42)
            val nameStart = p + CENTRAL_HEADER
            val extraStart = nameStart + nameLength
            val next = extraStart + extraLength + commentLength
            if (next > directory.size) throw ZipException("Damaged archive (central directory)")
            val name = directory.decodeToString(nameStart, extraStart).replace('\\', '/')
            if (uncompressed == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL || headerOffset == 0xFFFFFFFFL) {
                // ZIP64 extended information holds only the fields stored as 0xFFFFFFFF, in this order.
                var e = extraStart
                while (e + 4 <= extraStart + extraLength) {
                    val id = u16(directory, e)
                    val length = u16(directory, e + 2)
                    if (id == 0x0001) {
                        var f = e + 4
                        val end = minOf(f + length, extraStart + extraLength)
                        if (uncompressed == 0xFFFFFFFFL && f + 8 <= end) { uncompressed = u64(directory, f); f += 8 }
                        if (compressed == 0xFFFFFFFFL && f + 8 <= end) { compressed = u64(directory, f); f += 8 }
                        if (headerOffset == 0xFFFFFFFFL && f + 8 <= end) headerOffset = u64(directory, f)
                        break
                    }
                    e += 4 + length
                }
            }
            if (compressed < 0 || uncompressed < 0 || headerOffset < 0 || headerOffset + LOCAL_HEADER > total) {
                throw ZipException("Damaged archive ($name)")
            }
            result += ZipEntry(name, method, flags, crc, compressed, uncompressed, headerOffset)
            p = next
        }
        return result
    }

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        private const val MAX_DIRECTORY = 16_000_000L
    }
}

/** Writes STORED entries; call [finish] once after the last [add]. */
class ZipWriter(private val sink: ByteSink, epochMillis: Long = 0L) {
    private class Record(val name: ByteArray, val crc: Int, val size: Int, val offset: Long)

    private val time: Int
    private val date: Int
    private val records = ArrayList<Record>()
    private val names = HashSet<String>()
    private var offset = 0L
    private var finished = false

    init {
        val (t, d) = dosDateTime(epochMillis)
        time = t
        date = d
    }

    val count: Int get() = records.size

    fun add(name: String, data: ByteArray) {
        check(!finished) { "Archive is already finished" }
        require(name.isNotEmpty() && !name.startsWith("/") && ".." !in name.split('/')) { "Bad entry name: $name" }
        require(names.add(name)) { "Duplicate entry: $name" }
        check(records.size < 0xFFFF) { "Too many files for one archive" }
        val nameBytes = name.encodeToByteArray()
        require(nameBytes.size <= 0xFFFF) { "Entry name too long" }
        val crc = Crc32.of(data)
        val header = LittleEndian(LOCAL_HEADER + nameBytes.size).apply {
            u32(LOCAL_SIG); u16(VERSION); u16(FLAG_UTF8); u16(ZipReader.STORED); u16(time); u16(date)
            u32(crc.toLong() and 0xFFFFFFFFL); u32(data.size.toLong()); u32(data.size.toLong())
            u16(nameBytes.size); u16(0); bytes(nameBytes)
        }.array
        sink.write(header)
        sink.write(data)
        records += Record(nameBytes, crc, data.size, offset)
        offset += header.size + data.size
        check(offset < 0xFFFFFFFFL) { "Archive is larger than 4 GB" }
    }

    fun finish() {
        check(!finished) { "Archive is already finished" }
        finished = true
        val directoryStart = offset
        var directorySize = 0L
        for (r in records) {
            val header = LittleEndian(CENTRAL_HEADER + r.name.size).apply {
                u32(CENTRAL_SIG); u16(VERSION); u16(VERSION); u16(FLAG_UTF8); u16(ZipReader.STORED); u16(time); u16(date)
                u32(r.crc.toLong() and 0xFFFFFFFFL); u32(r.size.toLong()); u32(r.size.toLong())
                u16(r.name.size); u16(0); u16(0); u16(0); u16(0); u32(0); u32(r.offset); bytes(r.name)
            }.array
            sink.write(header)
            directorySize += header.size
        }
        check(directoryStart + directorySize < 0xFFFFFFFFL) { "Archive is larger than 4 GB" }
        sink.write(LittleEndian(EOCD_SIZE).apply {
            u32(EOCD_SIG); u16(0); u16(0); u16(records.size); u16(records.size); u32(directorySize); u32(directoryStart); u16(0)
        }.array)
    }
}

/** MS-DOS (time, date) fields for [epochMillis] in UTC; dates before 1980 become 1980-01-01. */
internal fun dosDateTime(epochMillis: Long): Pair<Int, Int> {
    val seconds = epochMillis.floorDiv(1000L)
    val days = seconds.floorDiv(86_400L)
    val secondOfDay = seconds - days * 86_400L
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
    if (year < 1980) return 0 to ((1 shl 5) or 1)
    val time = ((secondOfDay / 3600).toInt() shl 11) or (((secondOfDay % 3600) / 60).toInt() shl 5) or ((secondOfDay % 60) / 2).toInt()
    val date = ((minOf(year, 2107) - 1980) shl 9) or (month shl 5) or day
    return time to date
}

private class LittleEndian(size: Int) {
    val array = ByteArray(size)
    private var p = 0

    fun u16(value: Int) { array[p++] = value.toByte(); array[p++] = (value ushr 8).toByte() }

    fun u32(value: Long) { repeat(4) { array[p++] = (value ushr (8 * it)).toByte() } }

    fun bytes(value: ByteArray) { value.copyInto(array, p); p += value.size }
}

private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)

private fun u64(b: ByteArray, i: Int): Long = u32(b, i) or (u32(b, i + 4) shl 32)

private const val LOCAL_SIG = 0x04034b50L
private const val CENTRAL_SIG = 0x02014b50L
private const val EOCD_SIG = 0x06054b50L
private const val ZIP64_EOCD_SIG = 0x06064b50L
private const val ZIP64_LOCATOR_SIG = 0x07064b50L
private const val LOCAL_HEADER = 30
private const val CENTRAL_HEADER = 46
private const val EOCD_SIZE = 22
private const val ZIP64_LOCATOR_SIZE = 20
private const val ZIP64_EOCD_SIZE = 56
private const val VERSION = 20
private const val FLAG_UTF8 = 0x0800
