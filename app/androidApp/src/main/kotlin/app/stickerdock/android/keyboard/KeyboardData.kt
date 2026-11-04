package app.stickerdock.android.keyboard

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.stickerdock.android.AppGraph
import app.stickerdock.android.dual.Dual
import app.stickerdock.android.media.StickerNormalizer
import app.stickerdock.core.StickerKind
import app.stickerdock.core.Tags
import app.stickerdock.library.StickerFilter
import java.io.File

data class KeyboardSticker(val id: String, val animated: Boolean, val label: String, val model: Any)

data class KeyboardTab(val key: String, val title: String)

/** What the keyboard shows. Main profile reads the library; a companion copy reads it over sync. */
interface KeyboardData {
    fun tabs(): List<KeyboardTab>
    fun stickers(tab: KeyboardTab, typedContext: String): List<KeyboardSticker>
    /** A file this app can share through its FileProvider. */
    fun fileFor(sticker: KeyboardSticker): File?
    fun markUsed(sticker: KeyboardSticker) {}

    companion object {
        const val FOR_YOU = "for_you"
        const val FAVORITES = "favorites"
        const val RECENT = "recent"

        fun create(context: Context): KeyboardData =
            if (Dual.isCompanion(context)) CompanionKeyboardData(context) else LocalKeyboardData(context)

        fun adultEnabled(context: Context) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("adult_enabled", false)

        /** Last few words typed before the cursor become the search for "For you". */
        fun contextQuery(text: String): String =
            text.split(Regex("\\s+")).filter { it.length >= 2 }.takeLast(2).joinToString(" ")
    }
}

class LocalKeyboardData(private val context: Context) : KeyboardData {
    private val graph = AppGraph.get(context)
    private val adult get() = KeyboardData.adultEnabled(context)

    override fun tabs(): List<KeyboardTab> =
        listOf(KeyboardTab(KeyboardData.FOR_YOU, "For you"), KeyboardTab(KeyboardData.FAVORITES, "★"), KeyboardTab(KeyboardData.RECENT, "Recent")) +
            graph.library.packs(includeAdult = adult).filter { it.count > 0 }.map { KeyboardTab("pack:${it.id}", it.name) }

    override fun stickers(tab: KeyboardTab, typedContext: String): List<KeyboardSticker> {
        val lib = graph.library
        val items = when {
            tab.key == KeyboardData.FAVORITES -> lib.stickers(StickerFilter(favoritesOnly = true, includeAdult = adult, limit = 300))
            tab.key == KeyboardData.RECENT -> lib.recent(60, adult)
            tab.key.startsWith("pack:") -> lib.packStickers(tab.key.removePrefix("pack:"))
            else -> {
                val query = KeyboardData.contextQuery(typedContext)
                val matches = if (query.isNotBlank()) {
                    lib.stickers(StickerFilter(query = query, includeAdult = adult, limit = 60)).ifEmpty {
                        query.split(' ').flatMap { lib.stickers(StickerFilter(query = it, includeAdult = adult, limit = 30)) }
                    }
                } else emptyList()
                (matches + lib.recent(30, adult) + lib.stickers(StickerFilter(favoritesOnly = true, includeAdult = adult, limit = 60)) +
                    lib.stickers(StickerFilter(includeAdult = adult, limit = 120))).distinctBy { it.id }
            }
        }
        return items.map { KeyboardSticker(it.id, it.kind == StickerKind.ANIMATED, it.title, graph.files.file(it.id)) }
    }

    override fun fileFor(sticker: KeyboardSticker): File? = graph.files.file(sticker.id).takeIf { it.isFile }
    override fun markUsed(sticker: KeyboardSticker) { graph.library.markUsed(sticker.id) }
}

/** Dual Messenger copy: no local library; reads the main profile's over the sync provider. */
class CompanionKeyboardData(private val context: Context) : KeyboardData {
    private val base = "content://${Dual.HUB_USER}@${context.packageName}.sync"
    private val cache = File(context.cacheDir, "shared").apply { mkdirs() }

    override fun tabs() = listOf(KeyboardTab(KeyboardData.FOR_YOU, "For you"), KeyboardTab(KeyboardData.FAVORITES, "★"))

    override fun stickers(tab: KeyboardTab, typedContext: String): List<KeyboardSticker> {
        val q = if (tab.key == KeyboardData.FOR_YOU) KeyboardData.contextQuery(typedContext) else ""
        val uri = Uri.parse("$base/library").buildUpon().appendQueryParameter("q", q).appendQueryParameter("limit", "200")
            .appendQueryParameter("adult", if (KeyboardData.adultEnabled(context)) "1" else "0").build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (tab.key == KeyboardData.FAVORITES && c.getInt(5) != 1) continue
                        val id = c.getString(0)
                        add(KeyboardSticker(id, c.getString(1) == StickerKind.ANIMATED.name,
                            c.getString(2).ifBlank { Tags.split(c.getString(3)).firstOrNull() ?: "Sticker" }, Uri.parse("$base/file/$id")))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    override fun fileFor(sticker: KeyboardSticker): File? = runCatching {
        val target = File(cache, "${sticker.id}.webp")
        if (!target.isFile) {
            context.contentResolver.openInputStream(Uri.parse("$base/file/${sticker.id}"))?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        target.takeIf { it.isFile }
    }.getOrNull()
}

/** Picks what to commit: WhatsApp's sticker type first, then plain WebP, then PNG (static only). */
object StickerMime {
    const val WHATSAPP_STICKER = "image/webp.wasticker"

    fun choose(accepted: Array<String>, animated: Boolean): String? {
        fun accepts(type: String) = accepted.any { ClipDescription.compareMimeTypes(type, it) }
        return when {
            accepted.any { it.equals(WHATSAPP_STICKER, ignoreCase = true) } -> WHATSAPP_STICKER
            accepts("image/webp") -> "image/webp"
            !animated && accepts("image/png") -> "image/png"
            else -> null
        }
    }

    fun pngCopy(context: Context, source: File): File? {
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, source.nameWithoutExtension + ".png")
        if (out.isFile) return out
        val bitmap = StickerNormalizer.decode(source.readBytes(), 512) ?: return null
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        return out
    }
}
