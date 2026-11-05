package app.stickerdock.android.imports

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.util.Locale

/** One file found under a Storage Access Framework tree. */
data class DocFile(val docId: String, val name: String, val folder: String, val size: Long, val uri: Uri)

/**
 * Walks a SAF tree with one DocumentsContract query per directory (DocumentFile.listFiles plus per-file property
 * calls cost several IPC round-trips per file, which v1 hit on large WhatsApp folders).
 */
object DocumentTree {
    // "was" = WhatsApp's own animated stickers (Lottie in a ZIP), converted on import.
    private val IMAGE_EXTENSIONS = setOf("webp", "png", "jpg", "jpeg", "heic", "heif", "gif", "was")

    fun rootName(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':').ifBlank { "Phone stickers" }

    fun images(context: Context, tree: Uri, maxDepth: Int = 6, maxEntries: Int = 60_000): Sequence<DocFile> = sequence {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque(listOf(Triple(rootId, rootName(tree), 0)))
        var visited = 0
        while (queue.isNotEmpty()) {
            val (docId, folderName, depth) = queue.removeFirst()
            val found = mutableListOf<DocFile>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    if (++visited > maxEntries) error("Folder has more than $maxEntries entries; pick a smaller folder")
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (depth < maxDepth && !name.startsWith(".")) queue.addLast(Triple(id, name, depth + 1))
                    } else if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS || mime.startsWith("image/")) {
                        found += DocFile(id, name, folderName, if (c.isNull(3)) -1 else c.getLong(3),
                            DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
            yieldAll(found)
        }
    }
}
