package app.stickerdock.android.dual

import app.stickerdock.android.media.addNormalized
import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import app.stickerdock.android.AppGraph
import app.stickerdock.android.media.Normalized
import app.stickerdock.android.media.StickerNormalizer
import app.stickerdock.android.whatsapp.LocalStickerSource
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.library.Origin
import app.stickerdock.library.StickerFilter
import java.io.FileNotFoundException

/**
 * Main-profile library feed for StickerDock's own companion copies (Dual Messenger etc.).
 * Guarded by a signature permission; cross-user callers additionally need INTERACT_ACROSS_USERS.
 *
 *   shelf                        packs on the WhatsApp shelf (3–30 stickers)
 *   pack/<id>/stickers           stickers of a shelf pack
 *   library?q=&limit=&adult=     stickers for the companion keyboard
 *   pending (query/delete)       "add pack X in the clone's WhatsApp" requests
 *   status (insert)              companion reports an add result
 *   sticker/<pack>/<id>, file/<id>, tray/<pack>   files
 */
class SyncProvider : ContentProvider() {
    private lateinit var matcher: UriMatcher
    private val graph by lazy { AppGraph.get(requireNotNull(context)) }

    override fun onCreate(): Boolean {
        val auth = "${requireNotNull(context).packageName}.sync"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(auth, "shelf", SHELF)
            addURI(auth, "pack/*/stickers", PACK_STICKERS)
            addURI(auth, "library", LIBRARY)
            addURI(auth, "pending", PENDING)
            addURI(auth, "pending/*", PENDING_ONE)
            addURI(auth, "status", STATUS)
            addURI(auth, "incoming", INCOMING)
            addURI(auth, "sticker/*/*", STICKER)
            addURI(auth, "file/*", FILE)
            addURI(auth, "tray/*", TRAY)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (Dual.isCompanion(requireNotNull(context))) throw IllegalStateException("Companion copies don't serve sync")
        graph.events.record("sync", "${callingPackage ?: "?"} ${uri.path}")
        val library = graph.library
        val cursor = when (matcher.match(uri)) {
            SHELF -> MatrixCursor(arrayOf("id", "name", "publisher", "animated", "data_version", "count")).apply {
                library.shelfPacks().forEach {
                    addRow(arrayOf<Any>(it.id, it.name, it.publisher, if (it.kind == StickerKind.ANIMATED) 1 else 0, it.dataVersion, it.count))
                }
            }
            PACK_STICKERS -> MatrixCursor(arrayOf("id", "emojis", "accessibility")).apply {
                val packId = uri.pathSegments[1]
                val pack = library.shelfPacks().firstOrNull { it.id == packId }
                if (pack != null) {
                    val limit = if (pack.kind == StickerKind.ANIMATED) StickerRules.ANIMATED_ACCESSIBILITY_MAX else StickerRules.STATIC_ACCESSIBILITY_MAX
                    library.packStickers(packId).forEach {
                        addRow(arrayOf<Any>(it.id, it.whatsAppEmojis.joinToString(","), it.label.ifBlank { it.tags }.take(limit)))
                    }
                }
            }
            LIBRARY -> MatrixCursor(arrayOf("id", "kind", "label", "tags", "emojis", "favorite")).apply {
                val filter = StickerFilter(
                    query = uri.getQueryParameter("q").orEmpty(),
                    includeAdult = uri.getQueryParameter("adult") == "1",
                    limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 400) ?: 120,
                )
                library.stickers(filter).forEach {
                    // Names only: WhatsApp file names ("STK-…-WA0008") would show up in the companion's keyboard.
                    addRow(arrayOf<Any>(it.id, it.kind.name, app.stickerdock.core.AutoTags.displayName(it.label).ifBlank { it.autoCaption }, it.tags, it.emojis, if (it.favorite) 1 else 0))
                }
            }
            PENDING -> MatrixCursor(arrayOf("id", "pack_id", "pack_name", "package")).apply {
                Dual.pending(requireNotNull(context)).forEach { addRow(arrayOf<Any>(it.id, it.packId, it.packName, it.whatsAppPackage)) }
            }
            else -> throw IllegalArgumentException("Unknown URI $uri")
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val ctx = requireNotNull(context)
        val file = when (matcher.match(uri)) {
            STICKER -> {
                val (packId, stickerId) = uri.pathSegments[1] to uri.pathSegments[2]
                if (!graph.library.isInPack(packId, stickerId)) throw FileNotFoundException("Not in pack")
                graph.files.file(stickerId)
            }
            FILE -> graph.files.file(uri.pathSegments[1])
            TRAY -> LocalStickerSource.trayFile(ctx, uri.pathSegments[1])
            else -> null
        }?.takeIf { it.isFile } ?: throw FileNotFoundException("Missing $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        values ?: throw IllegalArgumentException("No values")
        return when (matcher.match(uri)) {
            STATUS -> {
                val packId = values.getAsString("pack_id") ?: return null
                Dual.reportStatus(requireNotNull(context), packId, values.getAsInteger("added") == 1)
                uri
            }
            INCOMING -> {
                // A companion sends one sticker file from its profile's WhatsApp folder into this library.
                val session = requireNotNull(values.getAsString("session")) { "session required" }
                val key = requireNotNull(values.getAsString("key")) { "key required" }
                val bytes = requireNotNull(values.getAsByteArray("bytes")) { "bytes required" }
                val group = values.getAsString("folder") ?: "Dual Messenger"
                val library = graph.library
                if (key in library.importedKeys(session)) return Uri.withAppendedPath(uri, "skipped")
                when (val n = StickerNormalizer.normalize(bytes)) {
                    is Normalized.Fail -> library.recordRejected(session, key, n.reason, bytes.size)
                    is Normalized.Ok -> {
                        val name = n.exif?.packName.orEmpty()
                        val result = library.addNormalized(n, Origin.IMPORT, originRef = name.ifBlank { group },
                            label = key.substringAfterLast('/'), emojis = n.exif?.emojis?.joinToString(",").orEmpty())
                        library.recordImport(session, key, result, name, bytes.size)
                    }
                }
                Uri.withAppendedPath(uri, "ok")
            }
            else -> throw IllegalArgumentException("Unknown URI $uri")
        }
    }

    /** begin_import → session id; finish_import(session) → packs built per original pack name. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "whoami") return Bundle().apply {
            putInt("user", Dual.myUserId()); putBoolean("companion", Dual.isCompanion(requireNotNull(context)))
        }
        if (Dual.isCompanion(requireNotNull(context))) return null
        val library = graph.library
        return when (method) {
            "begin_import" -> Bundle().apply { putString("session", library.startImport(arg ?: "dual", "COMPANION")) }
            "finish_import" -> {
                val session = requireNotNull(arg)
                val packs = library.packImported(library.importedGroups(session), "From Dual Messenger").size
                library.setImportState(session, "DONE")
                val totals = library.importTotals(session)
                graph.events.record("sync", "companion import done: ${totals.added} added, ${totals.duplicates} dup, ${totals.rejected} rejected, $packs packs")
                if (totals.added > 0) app.stickerdock.android.tags.AutoTagWorker.enqueue(requireNotNull(context))
                Bundle().apply {
                    putInt("added", totals.added); putInt("duplicates", totals.duplicates)
                    putInt("rejected", totals.rejected); putInt("packs", packs)
                }
            }
            "ping" -> Bundle().apply { putBoolean("ok", true) }
            else -> null
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (matcher.match(uri) != PENDING_ONE) return 0
        Dual.complete(requireNotNull(context), uri.lastPathSegment.orEmpty())
        return 1
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        STICKER, FILE -> "image/webp"
        TRAY -> "image/png"
        else -> null
    }

    private companion object {
        const val SHELF = 1; const val PACK_STICKERS = 2; const val LIBRARY = 3; const val PENDING = 4
        const val PENDING_ONE = 5; const val STATUS = 6; const val STICKER = 7; const val FILE = 8; const val TRAY = 9
        const val INCOMING = 10
    }
}
