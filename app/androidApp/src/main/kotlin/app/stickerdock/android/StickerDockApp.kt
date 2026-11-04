package app.stickerdock.android

import android.app.Application
import android.content.Context
import android.net.Uri
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.stickerdock.android.diagnostics.EventLog
import app.stickerdock.db.StickerDatabase
import app.stickerdock.library.Library
import app.stickerdock.library.PackChangeListener
import app.stickerdock.library.StickerFiles
import androidx.sqlite.db.SupportSQLiteDatabase
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import java.io.File
import java.util.UUID

class StickerDockApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        app.stickerdock.android.dual.CompanionRelayActivity.configure(this)
        MaintenanceWorker.schedule(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(AnimatedImageDecoder.Factory()) }
            .build()
}

/**
 * Process-wide dependencies. ContentProviders start before Application.onCreate, so everything
 * is created lazily from whichever component asks first.
 */
class AppGraph private constructor(val context: Context) {
    val files: AndroidStickerFiles = AndroidStickerFiles(context)

    private val driver by lazy {
        AndroidSqliteDriver(
            schema = StickerDatabase.Schema,
            context = context,
            name = "library.db",
            callback = object : AndroidSqliteDriver.Callback(StickerDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.setForeignKeyConstraintsEnabled(true)
                    db.enableWriteAheadLogging()
                }
            },
        )
    }

    val library: Library by lazy {
        Library(StickerDatabase(driver), files, System::currentTimeMillis, { UUID.randomUUID().toString() },
            ProviderNotifier(context))
    }

    val stickerAuthority: String get() = "${context.packageName}.stickers"
    val syncAuthority: String get() = "${context.packageName}.sync"
    val fileAuthority: String get() = "${context.packageName}.files"

    val events = EventLog(context)

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }
    }
}

/** One immutable WebP per sticker id under filesDir/stickers. */
class AndroidStickerFiles(context: Context) : StickerFiles {
    val dir = File(context.filesDir, "stickers").apply { mkdirs() }
    fun file(id: String) = File(dir, "${safe(id)}.webp")

    override fun write(id: String, bytes: ByteArray) {
        val target = file(id)
        val tmp = File(dir, "${safe(id)}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "Could not store sticker" } }
    }

    override fun read(id: String): ByteArray? = file(id).takeIf { it.isFile }?.readBytes()
    override fun delete(id: String) { file(id).delete() }
    override fun path(id: String): String = file(id).absolutePath

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }.also { require(it.isNotEmpty()) }
}

/** Tells WhatsApp (and our Dual Messenger companion) that pack metadata changed. */
class ProviderNotifier(private val context: Context) : PackChangeListener {
    override fun onPacksChanged(packIds: Collection<String>) {
        val resolver = context.contentResolver
        val stickers = "content://${context.packageName}.stickers"
        val sync = "content://${context.packageName}.sync"
        resolver.notifyChange(Uri.parse("$stickers/metadata"), null)
        resolver.notifyChange(Uri.parse("$sync/shelf"), null)
        packIds.forEach { id ->
            resolver.notifyChange(Uri.parse("$stickers/metadata/$id"), null)
            resolver.notifyChange(Uri.parse("$stickers/stickers/$id"), null)
        }
    }
}
