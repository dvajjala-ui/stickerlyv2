package app.stickerdock.android.debug

import app.stickerdock.android.media.addNormalized
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.stickerdock.android.AppGraph
import app.stickerdock.android.dual.Dual
import app.stickerdock.android.whatsapp.RemoteStickerSource
import app.stickerdock.android.whatsapp.WhatsAppDirectory
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
        // Release the broadcast at once: long commands would otherwise hit the background-broadcast ANR
        // timeout (seen on One UI 8.5 together with Samsung's Freecess freezer). Keep the app's showcase
        // screen in the foreground while long commands run so the process isn't frozen.
        goAsync().finish()
        val pending = object { fun finish() {} }
        Thread {
            val graph = AppGraph.get(context)
            val log = graph.events
            try {
                when (intent.getStringExtra("cmd")) {
                    "import" -> {
                        val base = if (intent.getStringExtra("root") == "internal") context.filesDir else context.externalMediaDirs.firstOrNull() ?: error("no media dir")
                        val dir = File(base, intent.getStringExtra("dir") ?: "import")
                        val files = dir.walkTopDown().filter { it.isFile }.toList()
                        val groups = linkedMapOf<String, MutableList<String>>()
                        val reasons = mutableMapOf<String, Int>()
                        var added = 0; var dup = 0; var rejected = 0
                        val started = System.currentTimeMillis()
                        files.forEach { f ->
                            when (val n = StickerNormalizer.normalize(f.readBytes())) {
                                is Normalized.Fail -> { rejected++; reasons.merge(n.reason.take(70), 1, Int::plus) }
                                is Normalized.Ok -> {
                                    val group = n.exif?.packName ?: ""
                                    when (val r = graph.library.addNormalized(n, Origin.IMPORT, originRef = group.ifBlank { null }, label = f.name,
                                        emojis = n.exif?.emojis?.joinToString(",").orEmpty())) {
                                        is AddResult.Added -> { added++; groups.getOrPut(group) { mutableListOf() } += r.id }
                                        is AddResult.Duplicate -> dup++
                                        is AddResult.Rejected -> { rejected++; reasons.merge(r.reason.take(70), 1, Int::plus) }
                                    }
                                }
                            }
                        }
                        val packs = graph.library.packImported(groups, "From WhatsApp").size
                        log.record("debug", "import ${files.size} files from ${dir.name} in ${System.currentTimeMillis() - started} ms: " +
                            "$added added, $dup dup, $rejected rejected, $packs packs; reasons=$reasons")
                    }
                    "shelf" -> {
                        val id = requireNotNull(intent.getStringExtra("pack"))
                        graph.library.setShelf(id, intent.getBooleanExtra("on", true))
                        log.record("debug", "shelf $id=${intent.getBooleanExtra("on", true)}")
                    }
                    "probe" -> {
                        val me = Dual.myUserId()
                        val lines = mutableListOf("user=$me companion=${Dual.isCompanion(context)} crossUser=${Dual.crossUserGranted(context)}",
                            "otherProfiles=${Dual.otherProfiles(context).map(Dual::userId)} companions=${Dual.companionProfiles(context).map(Dual::userId)}")
                        lines += "targets=" + WhatsAppDirectory(context).targets().joinToString { "${it.label}[${it.id}] ready=${it.ready}" }
                        if (Dual.isCompanion(context)) {
                            val hub = Uri.parse("content://${Dual.HUB_USER}@${context.packageName}.sync")
                            lines += "hub ping=" + runCatching { context.contentResolver.call(hub, "ping", null, null)?.getBoolean("ok") }
                                .fold({ it.toString() }, { "ERR ${it.javaClass.simpleName}: ${it.message}" })
                            lines += "hub shelf=" + runCatching { RemoteStickerSource(context).shelf().map { "${it.name}(${it.count})" } }
                                .fold({ it.toString() }, { "ERR ${it.message}" })
                            lines += "hub library=" + runCatching {
                                context.contentResolver.query(Uri.parse("$hub/library?limit=400"), null, null, null, null)?.use { it.count }
                            }.fold({ it.toString() }, { "ERR ${it.message}" })
                            lines += "local whatsapp whitelist probe=" + Dual.isPackAdded(context, "com.whatsapp", me, "probe")
                        } else {
                            Dual.otherProfiles(context).forEach { p ->
                                val uid = Dual.userId(p)
                                lines += "user $uid whatsapp reachable=" + Dual.whatsAppReachable(context, "com.whatsapp", uid)
                            }
                        }
                        lines.forEach { log.record("probe", it) }
                    }
                    "companion_import" -> {
                        // Companion only: send files from its own external media dir to the main library.
                        val base = if (intent.getStringExtra("root") == "internal") context.filesDir else context.externalMediaDirs.firstOrNull() ?: error("no media dir")
                        val dir = File(base, intent.getStringExtra("dir") ?: "import")
                        val hub = Uri.parse("content://${Dual.HUB_USER}@${context.packageName}.sync")
                        val resolver = context.contentResolver
                        val session = resolver.call(hub, "begin_import", "debug:${dir.name}", null)?.getString("session") ?: error("hub not reachable")
                        var sent = 0; var failed = 0
                        dir.walkTopDown().filter { it.isFile && it.length() < 900_000 }.forEach { f ->
                            val values = ContentValues().apply {
                                put("session", session); put("key", f.name); put("bytes", f.readBytes()); put("folder", "Dual Messenger")
                            }
                            runCatching { resolver.insert(Uri.parse("$hub/incoming"), values) }.onSuccess { sent++ }.onFailure { failed++ }
                        }
                        val result = resolver.call(hub, "finish_import", session, null)
                        log.record("debug", "companion_import sent=$sent failed=$failed → added=${result?.getInt("added")} dup=${result?.getInt("duplicates")} rejected=${result?.getInt("rejected")} packs=${result?.getInt("packs")}")
                    }
                    "shelf_some" -> {
                        // Put the N largest WhatsApp-ready packs on the shelf (for provider checks).
                        val n = intent.getIntExtra("n", 3)
                        graph.library.packs(includeAdult = true).filter { it.whatsAppReady }.sortedByDescending { it.count }.take(n)
                            .forEach { graph.library.setShelf(it.id, true); log.record("debug", "shelved ${it.id} ${it.name}(${it.count})") }
                    }
                    "provider_selftest" -> {
                        val reports = app.stickerdock.android.whatsapp.WhatsAppValidator(context).validateAll()
                        if (reports.isEmpty()) log.record("selftest", "user ${Dual.myUserId()}: provider lists no packs")
                        reports.forEach { r ->
                            log.record("selftest", "user ${Dual.myUserId()}: ${r.name} (${r.stickers}) ${if (r.ok) "OK" else "PROBLEMS ${r.problems.take(4)}"}")
                        }
                    }
                    "wipe" -> {
                        val lib = graph.library
                        listOf(false, true).forEach { vault ->
                            lib.packs(includeAdult = true, vault = vault).forEach { lib.deletePack(it.id) }
                            val ids = lib.stickers(app.stickerdock.library.StickerFilter(includeAdult = true, vault = vault, limit = 100_000)).map { it.id }
                            lib.deleteStickers(ids)
                        }
                        log.record("debug", "wipe purged ${lib.purgeDeleted(Long.MAX_VALUE)} stickers")
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
