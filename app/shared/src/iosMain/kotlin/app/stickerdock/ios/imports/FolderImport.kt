package app.stickerdock.ios.imports

import app.stickerdock.library.AddResult
import app.stickerdock.library.Library
import app.stickerdock.library.Origin

/** A folder the user picked in Files (security-scoped on iOS; a map in tests). */
interface FolderSource {
    /** Display name of the picked folder. */
    val name: String

    /** Relative paths ('/'-separated) of image files, hidden files and folders excluded, sorted. */
    fun images(maxDepth: Int, maxEntries: Int): List<String>

    /** File bytes; throws with a short reason (e.g. "Larger than 5 MB") when it can't be read. */
    fun read(path: String, maxBytes: Int): ByteArray
}

/**
 * Port of Android's FolderImporter: valid stickers kept byte-exact, a SQLite journal (import_session /
 * import_item) so an interrupted import resumes where it stopped when the same folder is picked again,
 * and packs built per original WhatsApp pack name (EXIF) at the end.
 */
class FolderImport(private val library: Library, private val check: StickerCheck) {
    fun run(source: FolderSource, sessionId: String, stopped: () -> Boolean, tick: (ImportTick) -> Unit): ImportSummary {
        val done = library.importedKeys(sessionId)
        val totals = library.importTotals(sessionId)
        var added = totals.added
        var duplicates = totals.duplicates
        var rejected = totals.rejected
        val reasons = Reasons()
        tick(ImportTick(added + duplicates + rejected, added, duplicates, rejected, null))
        for (path in source.images(MAX_DEPTH, MAX_ENTRIES)) {
            if (path in done) continue
            if (stopped()) {
                library.setImportState(sessionId, "PAUSED")
                return ImportSummary(0, added, duplicates, rejected, paused = true, reasons = reasons.toMap())
            }
            val fileName = path.substringAfterLast('/')
            val bytes = try {
                source.read(path, MAX_FILE)
            } catch (e: Exception) {
                val why = e.message?.take(120) ?: "Unreadable"
                library.recordRejected(sessionId, path, why, 0)
                rejected++; reasons.add(why); done += path
                continue
            }
            when (val n = check.check(bytes)) {
                is Checked.Fail -> {
                    library.recordRejected(sessionId, path, n.reason, bytes.size)
                    rejected++; reasons.add(n.reason)
                }
                is Checked.Ok -> {
                    val group = n.exif?.packName.orEmpty()
                    val folder = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { source.name }
                    val result = library.addSticker(n.bytes, Origin.IMPORT, originRef = group.ifBlank { folder }, label = fileName,
                        emojis = n.exif?.emojis?.joinToString(",").orEmpty(), dhash = n.dhash)
                    library.recordImport(sessionId, path, result, group, bytes.size)
                    when (result) {
                        is AddResult.Added -> added++
                        is AddResult.Duplicate -> duplicates++
                        is AddResult.Rejected -> { rejected++; reasons.add(result.reason) }
                    }
                }
            }
            done += path
            if ((added + duplicates + rejected) % 20 == 0) tick(ImportTick(added + duplicates + rejected, added, duplicates, rejected, fileName))
        }
        val fallback = if (source.name.contains("WhatsApp", ignoreCase = true)) "From WhatsApp" else source.name.take(60).ifBlank { "Imported" }
        val packs = library.packImported(library.importedGroups(sessionId), fallback).size
        library.setImportState(sessionId, "DONE", "$added added")
        tick(ImportTick(added + duplicates + rejected, added, duplicates, rejected, null))
        return ImportSummary(packs, added, duplicates, rejected, reasons = reasons.toMap())
    }

    companion object {
        const val MODE = "FOLDER"
        const val MAX_DEPTH = 6
        const val MAX_ENTRIES = 60_000
        const val MAX_FILE = 5_000_000
    }
}
