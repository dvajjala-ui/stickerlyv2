package app.stickerdock.ios.imports

/** Outcome of one import; [reasons] counts why files were skipped. Wording matches Android's ImportSummary. */
data class ImportSummary(
    val packs: Int,
    val added: Int,
    val duplicates: Int,
    val rejected: Int,
    val paused: Boolean = false,
    val reasons: Map<String, Int> = emptyMap(),
) {
    val scanned: Int get() = added + duplicates + rejected

    fun describe(): String = buildString {
        append(if (paused) "Paused: " else "Done: ")
        append("$added added")
        if (duplicates > 0) append(" · $duplicates already in library")
        if (rejected > 0) append(" · $rejected skipped")
        if (packs > 0) append(" · $packs packs")
    }

    /** The most common skip reason, e.g. "12 skipped: PNG, JPEG … need converting". */
    val topReason: String?
        get() = reasons.entries.maxByOrNull { it.value }?.let { (reason, n) -> "$n skipped: $reason" }

    operator fun plus(other: ImportSummary) = ImportSummary(
        packs + other.packs, added + other.added, duplicates + other.duplicates, rejected + other.rejected,
        paused || other.paused,
        (reasons.keys + other.reasons.keys).associateWith { (reasons[it] ?: 0) + (other.reasons[it] ?: 0) },
    )

    companion object {
        val EMPTY = ImportSummary(0, 0, 0, 0)
    }
}

data class ImportTick(val scanned: Int, val added: Int, val duplicates: Int, val rejected: Int, val current: String?)

/** Collects skip reasons while importing. */
internal class Reasons {
    private val counts = linkedMapOf<String, Int>()
    var total = 0
        private set

    fun add(reason: String) {
        counts[reason] = (counts[reason] ?: 0) + 1
        total++
    }

    fun toMap(): Map<String, Int> = counts.toMap()
}

internal val IMAGE_EXTENSIONS = setOf("webp", "png", "jpg", "jpeg", "heic", "heif", "gif")

internal fun extensionOf(name: String) = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
