package app.stickerdock.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.stickerdock.android.AppGraph
import app.stickerdock.android.media.Normalized
import app.stickerdock.android.media.StickerNormalizer
import app.stickerdock.library.AddResult
import app.stickerdock.library.Origin
import java.io.File

/**
 * Debug builds only. Lets adb drive device checks without touching the system pickers:
 *   adb shell am broadcast -a app.stickerdock.debug.COMMAND -p app.stickerdock.dev --es cmd import --es dir <name>
 *     imports files from <app external media>/<name>/ (push them there with adb first)
 *   --es cmd shelf --es pack <id> --ez on true      put a pack on/off the WhatsApp shelf
 *   --es cmd stats                                   log library counts
 * Results go to logcat tag StickerDock and the in-app Diagnostics log.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            val graph = AppGraph.get(context)
            val log = graph.events
            try {
                when (intent.getStringExtra("cmd")) {
                    "import" -> {
                        val base = context.externalMediaDirs.firstOrNull() ?: error("no media dir")
                        val dir = File(base, intent.getStringExtra("dir") ?: "import")
                        val files = dir.walkTopDown().filter { it.isFile }.toList()
                        val groups = linkedMapOf<String, MutableList<String>>()
                        var added = 0; var dup = 0; var rejected = 0
                        files.forEach { f ->
                            when (val n = StickerNormalizer.normalize(f.readBytes())) {
                                is Normalized.Fail -> rejected++
                                is Normalized.Ok -> {
                                    val group = n.exif?.packName ?: f.parentFile?.name ?: "Imported"
                                    when (val r = graph.library.addSticker(n.bytes, Origin.IMPORT, originRef = group, label = f.name,
                                        emojis = n.exif?.emojis?.joinToString(",").orEmpty())) {
                                        is AddResult.Added -> { added++; groups.getOrPut(group) { mutableListOf() } += r.id }
                                        is AddResult.Duplicate -> dup++
                                        is AddResult.Rejected -> rejected++
                                    }
                                }
                            }
                        }
                        val packs = groups.entries.sumOf { (g, ids) -> graph.library.autoPack(ids, g).size }
                        log.record("debug", "import ${files.size} files from ${dir.name}: $added added, $dup dup, $rejected rejected, $packs packs")
                    }
                    "shelf" -> {
                        val id = requireNotNull(intent.getStringExtra("pack"))
                        graph.library.setShelf(id, intent.getBooleanExtra("on", true))
                        log.record("debug", "shelf $id=${intent.getBooleanExtra("on", true)}")
                    }
                    "stats" -> {
                        val packs = graph.library.packs(includeAdult = true)
                        log.record("debug", "stats stickers=${graph.library.counts()} packs=${packs.size} " +
                            packs.take(12).joinToString { "${it.id.take(8)}:${it.name}(${it.count})" })
                    }
                    else -> log.record("debug", "unknown cmd ${intent.getStringExtra("cmd")}")
                }
            } catch (e: Exception) {
                log.record("debug", "command failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
