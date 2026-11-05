package app.stickerdock.discover

import app.stickerdock.core.Rating
import app.stickerdock.core.StickerKind
import app.stickerdock.core.StickerRules
import app.stickerdock.library.AddResult
import app.stickerdock.library.Library
import app.stickerdock.library.Origin
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A published sticker in the public feed (backend view `discover_stickers`). */
@Serializable
data class RemoteSticker(
    val id: String,
    val kind: String = "static",
    val rating: String = "general",
    val category: String? = null,
    val caption: String? = null,
    val tags: List<String> = emptyList(),
    val emojis: List<String> = emptyList(),
    @SerialName("file_path") val filePath: String,
    val sha256: String? = null,
    val credit: JsonObject? = null,
    @SerialName("ai_generated") val aiGenerated: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
) {
    val animated get() = kind.equals("animated", true)
    val adult get() = rating.equals("adult", true)
}

/** A published pack (backend view `discover_packs`). */
@Serializable
data class RemotePack(
    val id: String,
    val title: String,
    val description: String? = null,
    val kind: String = "static",
    val rating: String = "general",
    val category: String? = null,
    val version: Long = 1,
    @SerialName("sticker_count") val stickerCount: Int = 0,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val animated get() = kind.equals("animated", true)
    val adult get() = rating.equals("adult", true)
}

@Serializable
private data class SearchArgs(val q: String, @SerialName("include_adult") val includeAdult: Boolean, @SerialName("max_rows") val maxRows: Int)

@Serializable
private data class PackArgs(@SerialName("p_pack") val pack: String)

@Serializable
private data class ReportArgs(
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String,
    val reason: String,
    val details: String,
)

/**
 * Read-only client for the public Discover feed (Supabase PostgREST + public storage).
 * Everything is optional: with no URL configured the app works fully offline.
 */
class DiscoverClient private constructor(private val base: String, private val anonKey: String, private val http: HttpClient?) {
    val configured: Boolean get() = http != null

    fun imageUrl(path: String) = "$base/storage/v1/object/public/stickers/$path"

    suspend fun feed(includeAdult: Boolean, category: String? = null, offset: Int = 0, limit: Int = 60): List<RemoteSticker> =
        client().get("$base/rest/v1/discover_stickers") {
            parameter("select", "*")
            parameter("order", "published_at.desc")
            parameter("limit", limit); parameter("offset", offset)
            if (!includeAdult) parameter("rating", "eq.general")
            if (category != null) parameter("category", "eq.$category")
        }.body()

    suspend fun packs(includeAdult: Boolean, limit: Int = 40): List<RemotePack> =
        client().get("$base/rest/v1/discover_packs") {
            parameter("select", "*"); parameter("order", "updated_at.desc"); parameter("limit", limit)
            if (!includeAdult) parameter("rating", "eq.general")
        }.body()

    suspend fun pack(id: String): RemotePack? =
        client().get("$base/rest/v1/discover_packs") { parameter("select", "*"); parameter("id", "eq.$id") }
            .body<List<RemotePack>>().firstOrNull()

    suspend fun packStickers(packId: String): List<RemoteSticker> =
        client().post("$base/rest/v1/rpc/discover_pack_stickers") { contentType(ContentType.Application.Json); setBody(PackArgs(packId)) }.body()

    suspend fun search(query: String, includeAdult: Boolean, limit: Int = 60): List<RemoteSticker> =
        client().post("$base/rest/v1/rpc/search_stickers") {
            contentType(ContentType.Application.Json); setBody(SearchArgs(query, includeAdult, limit))
        }.body()

    suspend fun report(targetType: String, targetId: String, reason: String, details: String) {
        val response = client().post("$base/rest/v1/rpc/report_content") {
            contentType(ContentType.Application.Json); setBody(ReportArgs(targetType, targetId, reason, details.take(500)))
        }
        check(response.status.isSuccess()) { "Report failed (${response.status.value})" }
    }

    suspend fun download(path: String): ByteArray {
        val response = client().get(imageUrl(path))
        check(response.status.isSuccess()) { "Download failed (${response.status.value})" }
        return response.readRawBytes().also { require(it.size <= StickerRules.ANIMATED_MAX_BYTES) { "Sticker too large" } }
    }

    private fun client() = http ?: error("Discover isn't configured in this build")

    companion object {
        fun create(url: String, anonKey: String): DiscoverClient {
            val base = url.trim().trimEnd('/')
            // https only, except a loopback dev server reached through `adb reverse` (debug builds allow it).
            val local = base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:")
            if (base.isEmpty() || anonKey.isBlank() || !(base.startsWith("https://") || local)) return DiscoverClient("", "", null)
            val http = HttpClient {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000 }
                defaultRequest { header("apikey", anonKey); header("Authorization", "Bearer $anonKey") }
            }
            return DiscoverClient(base, anonKey, http)
        }
    }
}

/** Saves Discover stickers and packs into the local library. */
class DiscoverSaver(private val library: Library, private val client: DiscoverClient) {

    suspend fun saveSticker(sticker: RemoteSticker): AddResult {
        val bytes = client.download(sticker.filePath)
        val result = library.addSticker(bytes, Origin.DISCOVER, originRef = sticker.id, label = sticker.caption.orEmpty(),
            tags = (sticker.tags + listOfNotNull(sticker.category)).joinToString(" "), emojis = sticker.emojis.joinToString(","),
            rating = if (sticker.adult) Rating.ADULT else Rating.GENERAL)
        val id = (result as? AddResult.Added)?.id ?: (result as? AddResult.Duplicate)?.id ?: return result
        library.addToPack(savedPack(if (sticker.animated) StickerKind.ANIMATED else StickerKind.STATIC,
            if (sticker.adult) Rating.ADULT else Rating.GENERAL), listOf(id))
        return result
    }

    /**
     * Pulls new stickers into followed packs whose published version moved on. Each change bumps the local
     * pack version, so WhatsApp reloads it without the user doing anything. Returns packs updated.
     */
    suspend fun refreshFollowed(): Int {
        var updated = 0
        val followed = library.packs(includeAdult = true).filter { it.followUpdates && it.remoteId != null }
        for (local in followed) {
            val remote = runCatching { client.pack(requireNotNull(local.remoteId)) }.getOrNull() ?: continue
            if (remote.version <= (library.remoteVersion(local.id) ?: 0L)) continue
            val ids = client.packStickers(remote.id).mapNotNull { s ->
                val bytes = runCatching { client.download(s.filePath) }.getOrNull() ?: return@mapNotNull null
                when (val r = library.addSticker(bytes, Origin.DISCOVER, originRef = s.id, label = s.caption.orEmpty(),
                    tags = s.tags.joinToString(" "), emojis = s.emojis.joinToString(","),
                    rating = if (s.adult) Rating.ADULT else Rating.GENERAL)) {
                    is AddResult.Added -> r.id
                    is AddResult.Duplicate -> r.id
                    is AddResult.Rejected -> null
                }
            }
            val fresh = ids.filterNot { library.isInPack(local.id, it) }
            if (fresh.isNotEmpty()) library.addToPack(local.id, fresh)
            library.setRemoteVersion(local.id, remote.version)
            updated++
        }
        return updated
    }

    /** Downloads the pack and follows it so new stickers arrive with later versions. */
    suspend fun savePack(pack: RemotePack): String {
        val existing = library.packByRemote(pack.id)
        val kind = if (pack.animated) StickerKind.ANIMATED else StickerKind.STATIC
        val packId = existing?.id ?: library.createPack(pack.title, kind, if (pack.adult) Rating.ADULT else Rating.GENERAL,
            origin = "DISCOVER", remoteId = pack.id, remoteVersion = pack.version, followUpdates = true)
        val ids = client.packStickers(pack.id).mapNotNull { s ->
            val bytes = runCatching { client.download(s.filePath) }.getOrNull() ?: return@mapNotNull null
            when (val r = library.addSticker(bytes, Origin.DISCOVER, originRef = s.id, label = s.caption.orEmpty(),
                tags = s.tags.joinToString(" "), emojis = s.emojis.joinToString(","), rating = if (s.adult) Rating.ADULT else Rating.GENERAL)) {
                is AddResult.Added -> r.id
                is AddResult.Duplicate -> r.id
                is AddResult.Rejected -> null
            }
        }
        library.addToPack(packId, ids)
        library.setRemoteVersion(packId, pack.version)
        return packId
    }

    /** The rolling "Saved stickers" pack with room left (a new volume starts at 30); 18+ saves get their own. */
    private fun savedPack(kind: StickerKind, rating: Rating): String {
        val base = buildString {
            append(if (kind == StickerKind.ANIMATED) "Saved animated" else "Saved stickers")
            if (rating == Rating.ADULT) append(" 18+")
        }
        val candidates = library.packs(includeAdult = true).filter { it.origin == "SAVED" && it.kind == kind && it.rating == rating }
        candidates.firstOrNull { it.count < StickerRules.MAX_PACK }?.let { return it.id }
        val name = if (candidates.isEmpty()) base else "$base ${candidates.size + 1}"
        return library.createPack(name, kind, rating, origin = "SAVED")
    }
}
