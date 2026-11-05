@file:OptIn(ExperimentalForeignApi::class)

package app.stickerdock.ios

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.stickerdock.db.StickerDatabase
import app.stickerdock.discover.DiscoverClient
import app.stickerdock.host.Prefs
import app.stickerdock.library.Library
import app.stickerdock.library.StickerFiles
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSBundle
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.writeToFile

/**
 * Process-wide dependencies on iPhone (the counterpart of Android's AppGraph). Created once, on first use.
 * Library database: `Library/Application Support/databases/library.db` (SQLiter's default location).
 */
internal class IosGraph private constructor() {
    val files = IosStickerFiles(Dirs.support + "/stickers")
    val prefs: Prefs = IosPrefs(NSUserDefaults.standardUserDefaults)
    val events = EventLog()

    private val driver = NativeSqliteDriver(StickerDatabase.Schema, "library.db", onConfiguration = { config ->
        config.copy(extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true))
    })

    val library = Library(StickerDatabase(driver), files, now = ::epochMillis, newId = { NSUUID().UUIDString.lowercase() })

    /** Discover is optional: empty Info.plist values (the default) keep the app fully offline. */
    val discover: DiscoverClient = DiscoverClient.create(info("StickerDockDiscoverURL"), info("StickerDockDiscoverKey"))

    val appVersion: String = run {
        val short = info("CFBundleShortVersionString").ifBlank { "2.0" }
        val build = info("CFBundleVersion")
        if (build.isBlank()) short else "$short ($build)"
    }
    val appId: String = NSBundle.mainBundle.bundleIdentifier ?: "app.stickerdock"
    val appStoreLink: String? = info("StickerDockAppStoreLink").ifBlank { null }
    val playStoreLink: String? = info("StickerDockPlayStoreLink").ifBlank { null }

    companion object {
        val shared: IosGraph by lazy { IosGraph() }

        /** Info.plist string; unexpanded build-setting placeholders such as `$(X)` count as empty. */
        fun info(key: String): String {
            val value = (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)?.trim().orEmpty()
            return if (value.startsWith("$(")) "" else value
        }
    }
}

/** One immutable `<id>.webp` per sticker, written atomically and protected until first unlock. */
internal class IosStickerFiles(private val dir: String) : StickerFiles {
    init {
        Dirs.ensure(dir)
    }

    override fun write(id: String, bytes: ByteArray) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val ok = bytes.toNSData().writeToFile(path(id), NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication, error.ptr)
            check(ok) { "Could not store sticker: ${error.value?.localizedDescription ?: "unknown error"}" }
        }
    }

    override fun read(id: String): ByteArray? = NSFileManager.defaultManager.contentsAtPath(path(id))?.toByteArray()

    override fun delete(id: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(id), null)
    }

    override fun path(id: String): String = "$dir/${safe(id)}.webp"

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }.also { require(it.isNotEmpty()) }
}

internal class IosPrefs(private val defaults: NSUserDefaults) : Prefs {
    private fun scoped(name: String) = "settings.$name"

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(scoped(key)) == null) default else defaults.boolForKey(scoped(key))

    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, forKey = scoped(key))

    override fun getString(key: String): String? = defaults.stringForKey(scoped(key))

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(scoped(key)) else defaults.setObject(value, forKey = scoped(key))
    }
}

/** Recent integration events for the diagnostics screen (memory only, newest last). */
internal class EventLog {
    private val entries = MutableStateFlow<List<String>>(emptyList())

    fun record(tag: String, message: String) {
        val seconds = (epochMillis() / 1000) % 86_400
        val stamp = "${two(seconds / 3600)}:${two(seconds % 3600 / 60)}:${two(seconds % 60)}Z"
        entries.update { (it + "$stamp [$tag] ${message.take(300)}").takeLast(200) }
    }

    fun lines(): List<String> = entries.value

    private fun two(value: Long) = value.toString().padStart(2, '0')
}
