@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, UnsafeNumber::class)

package app.stickerdock.ios

import app.stickerdock.ios.zip.ByteSink
import app.stickerdock.ios.zip.ByteSource
import app.stickerdock.ios.zip.ZipException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMakeRange
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.getBytes
import platform.Foundation.timeIntervalSince1970
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

internal fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

internal fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    val out = ByteArray(count)
    if (count > 0) out.usePinned { getBytes(it.addressOf(0), length) }
    return out
}

/** Read-only, memory-mapped view of a file, so large archives are not copied into memory. */
internal class MappedFile(path: String) : ByteSource {
    private val data: NSData = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, error.ptr)
            ?: throw ZipException(error.value?.localizedDescription ?: "Can't open the file")
    }

    override val size: Long = data.length.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > size) throw ZipException("Read past the end of the file")
        val out = ByteArray(length)
        if (length > 0) out.usePinned { data.getBytes(it.addressOf(0), NSMakeRange(position.toULong(), length.toULong())) }
        return out
    }
}

/** Sequential writer to a new file (POSIX stdio). Call [close] when done. */
internal class FileSink(path: String) : ByteSink {
    private val file: CPointer<FILE> = fopen(path, "wb") ?: throw IllegalStateException("Can't create $path")
    private var open = true

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Bad write range" }
        if (length == 0) return
        val written = bytes.usePinned { fwrite(it.addressOf(offset), 1u, length.toULong(), file) }
        check(written == length.toULong()) { "Couldn't write the file (is the phone full?)" }
    }

    fun close() {
        if (!open) return
        open = false
        check(fclose(file) == 0) { "Couldn't finish writing the file" }
    }
}

internal object Dirs {
    /** `<container>/Library/Application Support` — backed up by iCloud/iTunes, never shown in Files. */
    val support: String by lazy {
        val path = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        ensure(path)
        path
    }

    /** A fresh, empty scratch directory under tmp/ (previous contents are removed). */
    fun freshTemp(name: String): String {
        val path = NSTemporaryDirectory().trimEnd('/') + "/" + name
        remove(path)
        ensure(path, protect = false)
        return path
    }

    fun ensure(path: String, protect: Boolean = true) {
        val attributes: Map<Any?, Any?>? =
            if (protect) mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication) else null
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, attributes, null)
    }

    fun remove(path: String) {
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    /**
     * True for copies the system made for us — document picker copies (`tmp/<bundle id>-Inbox`) and
     * "Copy to StickerDock" files (`Documents/Inbox`) — which may be deleted after import. Never true for
     * the user's own files.
     */
    fun isOurInboxCopy(url: NSURL): Boolean {
        val path = url.path ?: return false
        val container = NSHomeDirectory().trimEnd('/').substringAfterLast('/')
        if (container.isEmpty() || "/$container/" !in path) return false
        return "-Inbox/" in path || "/Documents/Inbox/" in path
    }
}
