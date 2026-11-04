package app.stickerdock.android.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.stickerdock.android.AppGraph
import java.io.FileNotFoundException

/**
 * WhatsApp's third-party sticker contract (github.com/WhatsApp/stickers, Android sample):
 *   content://<authority>/metadata                 all packs
 *   content://<authority>/metadata/<id>            one pack
 *   content://<authority>/stickers/<id>            stickers of a pack
 *   content://<authority>/stickers_asset/<id>/<f>  sticker or tray file
 * Only packs on the WhatsApp shelf with 3–30 stickers are listed.
 */
class WhatsAppStickerProvider : ContentProvider() {
    private lateinit var matcher: UriMatcher
    private val source by lazy { StickerSource.forContext(requireNotNull(context)) }
    private val authority by lazy { "${requireNotNull(context).packageName}.stickers" }

    override fun onCreate(): Boolean {
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            val auth = "${requireNotNull(context).packageName}.stickers"
            addURI(auth, METADATA, ALL)
            addURI(auth, "$METADATA/*", ONE)
            addURI(auth, "$STICKERS/*", STICKERS_OF_PACK)
            addURI(auth, "$STICKERS_ASSET/*/*", ASSET)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        log(uri)
        val cursor = when (matcher.match(uri)) {
            ALL -> packCursor(source.shelf())
            ONE -> packCursor(source.shelf().filter { it.id == uri.lastPathSegment })
            STICKERS_OF_PACK -> MatrixCursor(STICKER_COLUMNS).apply {
                source.stickers(requireNotNull(uri.lastPathSegment)).forEach { s ->
                    addRow(arrayOf<Any>(s.fileName, s.emojis.joinToString(","), s.accessibility))
                }
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun packCursor(packs: List<ShelfPack>) = MatrixCursor(PACK_COLUMNS).apply {
        packs.forEach { p ->
            addRow(arrayOf<Any>(
                p.id, p.name, p.publisher, trayName(p), "", "", "", "", "", "",
                p.dataVersion.toString(), 0, if (p.animated) 1 else 0,
            ))
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        log(uri)
        if (mode != "r") throw FileNotFoundException("Read only")
        if (matcher.match(uri) != ASSET) throw FileNotFoundException("Unknown asset $uri")
        val segments = uri.pathSegments
        val packId = segments[1]
        val name = segments[2]
        val pfd = when {
            name.startsWith("tray") -> source.openTray(packId)
            name.endsWith(".webp") -> source.openSticker(packId, name.removeSuffix(".webp"))
            else -> null
        } ?: throw FileNotFoundException("Missing $name")
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        ONE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        STICKERS_OF_PACK -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        ASSET -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    /** Evidence for device checks: which app read which path, and when. */
    private fun log(uri: Uri) {
        val caller = runCatching { callingPackage }.getOrNull() ?: "?"
        AppGraph.get(requireNotNull(context)).events.record("provider", "$caller ${uri.path}")
    }

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"
        private const val ALL = 1
        private const val ONE = 2
        private const val STICKERS_OF_PACK = 3
        private const val ASSET = 4

        val PACK_COLUMNS = arrayOf(
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
            "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
            "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website", "image_data_version", "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
        )
        val STICKER_COLUMNS = arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")

        /** Versioned tray name so a cover change is never served from WhatsApp's cache. */
        fun trayName(pack: ShelfPack) = "tray_${pack.dataVersion}.png"
    }
}
