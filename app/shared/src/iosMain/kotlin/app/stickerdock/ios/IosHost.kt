@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.stickerdock.ios

import app.stickerdock.core.StickerKind
import app.stickerdock.discover.DiscoverSaver
import app.stickerdock.host.DualState
import app.stickerdock.host.HostActions
import app.stickerdock.host.ImportProgress
import app.stickerdock.host.Prefs
import app.stickerdock.host.TargetKind
import app.stickerdock.host.WhatsAppState
import app.stickerdock.host.WhatsAppTarget
import app.stickerdock.ios.export.Archives
import app.stickerdock.ios.imports.ArchiveImport
import app.stickerdock.ios.imports.Checked
import app.stickerdock.ios.imports.FolderImport
import app.stickerdock.ios.imports.FolderSource
import app.stickerdock.ios.imports.IMAGE_EXTENSIONS
import app.stickerdock.ios.imports.ImportSummary
import app.stickerdock.ios.imports.ImportTick
import app.stickerdock.ios.imports.StickerCheck
import app.stickerdock.ios.imports.extensionOf
import app.stickerdock.ios.whatsapp.PackSnapshot
import app.stickerdock.ios.whatsapp.WhatsAppPayload
import app.stickerdock.ios.whatsapp.WhatsAppSender
import app.stickerdock.ios.zip.ZipReader
import app.stickerdock.ios.zip.ZlibInflater
import app.stickerdock.library.AddResult
import app.stickerdock.library.Origin
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIDevice
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.posix.uname
import platform.posix.utsname

/** A short message shown over the app (the iPhone stand-in for Android's Toast). */
internal data class HostMessage(val id: Long, val text: String)

/**
 * iPhone implementation of everything the shared UI asks the platform to do.
 *
 * WhatsApp on iOS has no ContentProvider: one pack at a time is copied to the pasteboard and WhatsApp
 * (or WhatsApp Business — iOS picks) is opened. WhatsApp keeps that copy; later edits are not synced,
 * the pack has to be sent again. The app can't see which packs WhatsApp holds, so nothing is shown as added.
 */
internal class IosHost(private val graph: IosGraph, private val presenter: Presenter) : HostActions {
    private val library get() = graph.library
    private val scope = MainScope()
    private val check = StickerCheck(Images::dhash)

    private val _whatsApp = MutableStateFlow(WhatsAppState(loading = true))
    private val _import = MutableStateFlow<ImportProgress?>(null)
    private val _message = MutableStateFlow<HostMessage?>(null)
    override val whatsApp: StateFlow<WhatsAppState> = _whatsApp
    override val importProgress: StateFlow<ImportProgress?> = _import
    val message: StateFlow<HostMessage?> = _message
    override val prefs: Prefs = graph.prefs
    override val appVersion: String = graph.appVersion
    override val appId: String = graph.appId
    // iOS copies each pack into WhatsApp through the pasteboard; there is no provider shelf to cap.
    override val shelfGuideline: Int? = null

    private val queue = ArrayDeque<ImportJob>()
    private var importing = false
    private var sending = false
    private var backgroundTask = UIBackgroundTaskInvalid

    @Volatile private var stopRequested = false
    @Volatile private var currentLabel = "Importing"

    private class ImportJob(val label: String, val work: () -> ImportSummary)

    init {
        refreshWhatsApp()
        scope.launch { maintenance() }
    }

    // ---------------- WhatsApp ----------------

    override fun refreshWhatsApp() {
        val installed = WhatsAppSender.installed()
        _whatsApp.value = WhatsAppState(
            targets = listOf(
                WhatsAppTarget(
                    id = TARGET_ID, label = "WhatsApp", packageName = "net.whatsapp.WhatsApp", userId = 0, kind = TargetKind.WHATSAPP,
                    ready = installed, setupHint = if (installed) null else "Install WhatsApp or WhatsApp Business from the App Store",
                ),
            ),
            added = emptyMap(),
            keyboardEnabled = false,
            keyboardSelected = false,
            companionMode = false,
            dual = DualState(),
            loading = false,
        )
    }

    override fun addToWhatsApp(packId: String, targetId: String) {
        val pack = library.pack(packId) ?: return showMessage("Pack not found")
        if (!pack.whatsAppReady) return showMessage("WhatsApp needs 3–30 stickers in a pack")
        if (!WhatsAppSender.installed()) return showMessage("WhatsApp isn't installed on this iPhone")
        if (sending) return
        sending = true
        scope.launch {
            val prepared = withContext(Dispatchers.Default) {
                runCatching {
                    autoreleasepool {
                        val snapshot = PackSnapshot.read(library, packId, Images::trayPng)
                        WhatsAppPayload.problems(snapshot).firstOrNull()?.let { throw IllegalStateException(it) }
                        WhatsAppPayload.json(snapshot, graph.appStoreLink, graph.playStoreLink).encodeToByteArray()
                    }
                }
            }
            prepared.fold(
                onSuccess = { json ->
                    library.setShelf(packId, true)
                    graph.events.record("whatsapp", "pasteboard ${json.size / 1000} KB for $packId (${pack.count} stickers)")
                    WhatsAppSender.send(json) { opened ->
                        sending = false
                        graph.events.record("whatsapp", "open whatsapp://stickerPack → $opened")
                        showMessage(
                            if (opened) "Opening WhatsApp — confirm “${pack.name}” there. WhatsApp keeps this copy: after you edit the pack, add it again."
                            else "Couldn't open WhatsApp",
                        )
                    }
                },
                onFailure = {
                    sending = false
                    graph.events.record("whatsapp", "pre-flight failed for $packId: ${it.message}")
                    showMessage("This pack needs a fix first: ${it.message}")
                },
            )
        }
    }

    // ---------------- imports ----------------

    override fun importPhoneFolder() {
        val pick = {
            presenter.pickDocuments(listOf(UTTypeFolder), multiple = false, asCopy = false) { urls ->
                urls.firstOrNull()?.let { importFolder(it) }
            }
        }
        if (prefs.getBoolean(KEY_FOLDER_NOTE)) return pick()
        presenter.alert(
            title = "Import a folder",
            message = "iPhone apps can't open WhatsApp's own sticker folder. Pick a folder in Files that holds WebP stickers — " +
                "for example one copied from your Android phone or a computer. Picking the same folder again resumes an unfinished import.",
            action = "Choose folder",
        ) {
            prefs.putBoolean(KEY_FOLDER_NOTE, true)
            pick()
        }
    }

    override fun importArchives() {
        presenter.pickDocuments(archiveTypes(), multiple = true, asCopy = true) { urls ->
            urls.forEachIndexed { index, url ->
                enqueue(ImportJob("Archive ${index + 1} of ${urls.size}") { importArchive(url) })
            }
        }
    }

    override fun importImages() {
        presenter.pickDocuments(listOf(UTTypeImage), multiple = true, asCopy = true) { urls ->
            if (urls.isNotEmpty()) enqueue(ImportJob("Importing ${urls.size} image(s)") { importImages(urls) })
        }
    }

    // The photo creator needs a WebP encoder; iOS has none built in (see docs/IOS.md).
    override fun createFromPhoto() = showMessage("Making stickers from photos is coming to iPhone soon")
    override fun createFromVideo() = showMessage("Making animated stickers from videos is coming to iPhone soon")
    // iOS has one WhatsApp handoff target (the OS picks WhatsApp or Business), so "all" is that one.
    override fun addToAllWhatsApps(packId: String) {
        whatsApp.value.targets.firstOrNull()?.let { addToWhatsApp(packId, it.id) } ?: showMessage("WhatsApp isn't installed")
    }

    override fun cancelImport() {
        stopRequested = true
        queue.clear()
    }

    /** Files opened with StickerDock from Files, Mail or a WhatsApp chat ("Open in StickerDock"). */
    fun openDocument(url: NSURL) {
        enqueue(ImportJob("Opening ${url.lastPathComponent ?: "file"}") { importArchive(url) })
    }

    private fun archiveTypes(): List<UTType> = listOfNotNull(UTTypeZIP, UTType.typeWithFilenameExtension("wastickers"))

    private fun importFolder(url: NSURL) {
        enqueue(ImportJob("Importing ${url.lastPathComponent ?: "folder"}") {
            withSecurityScope(url) {
                val root = url.path ?: error("Can't open this folder")
                val source = FilesFolder(root, url.lastPathComponent ?: "Folder")
                val session = library.activeImports().firstOrNull { it.source == root && it.mode == FolderImport.MODE }?.id
                    ?: library.startImport(root, FolderImport.MODE)
                library.setImportState(session, "RUNNING")
                val summary = FolderImport(library, check).run(source, session, stopped = { stopRequested }) { tick -> progress(tick) }
                if (summary.scanned == 0 && !summary.paused) {
                    error("No WebP stickers found in “${source.name}”. iPhone apps can't open WhatsApp's own sticker folder — pick a folder copied from your Android phone or computer.")
                }
                summary
            }
        })
    }

    private fun importArchive(url: NSURL): ImportSummary = try {
        withSecurityScope(url) {
            val path = url.path ?: error("Can't open this file")
            val zip = ZipReader(MappedFile(path), ZlibInflater, maxEntries = ArchiveImport.MAX_ENTRIES)
            ArchiveImport(library, check).import(zip, url.lastPathComponent ?: "Imported")
        }
    } finally {
        if (Dirs.isOurInboxCopy(url)) Dirs.remove(url.path ?: "")
    }

    private fun importImages(urls: List<NSURL>): ImportSummary {
        val ids = mutableListOf<String>()
        var summary = ImportSummary.EMPTY
        for (url in urls.take(100)) {
            if (stopRequested) break
            val one = try {
                withSecurityScope(url) { importImage(url, ids) }
            } catch (e: Exception) {
                ImportSummary(0, 0, 0, 1, reasons = mapOf((e.message ?: "Unreadable") to 1))
            } finally {
                if (Dirs.isOurInboxCopy(url)) Dirs.remove(url.path ?: "")
            }
            summary += one
        }
        val packs = if (ids.isNotEmpty()) library.autoPack(ids, "Shared with StickerDock", origin = "IMPORT").size else 0
        return summary.copy(packs = packs)
    }

    private fun importImage(url: NSURL, ids: MutableList<String>): ImportSummary = autoreleasepool {
        val bytes = MappedFile(url.path ?: error("Unreadable")).let { file ->
            if (file.size > StickerCheck.MAX_INPUT_BYTES) error("File is larger than 20 MB")
            file.read(0, file.size.toInt())
        }
        when (val n = check.check(bytes)) {
            is Checked.Fail -> ImportSummary(0, 0, 0, 1, reasons = mapOf(n.reason to 1))
            is Checked.Ok -> when (val r = library.addSticker(n.bytes, Origin.SHARE, originRef = "Shared",
                label = url.lastPathComponent.orEmpty(), emojis = n.exif?.emojis?.joinToString(",").orEmpty(), dhash = n.dhash)) {
                is AddResult.Added -> { ids += r.id; ImportSummary(0, 1, 0, 0) }
                is AddResult.Duplicate -> ImportSummary(0, 0, 1, 0)
                is AddResult.Rejected -> ImportSummary(0, 0, 0, 1, reasons = mapOf(r.reason to 1))
            }
        }
    }

    private fun enqueue(job: ImportJob) {
        queue.addLast(job)
        if (importing) return showMessage("Queued: ${job.label}")
        importing = true
        stopRequested = false
        beginBackgroundTask()
        scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    execute(queue.removeFirst())
                }
            } finally {
                importing = false
                endBackgroundTask()
            }
        }
    }

    private suspend fun execute(job: ImportJob) {
        currentLabel = job.label
        _import.value = ImportProgress(job.label, 0, 0, 0, 0, running = true)
        val result = withContext(Dispatchers.Default) { runCatching { job.work() } }
        result.fold(
            onSuccess = { s ->
                graph.events.record("import", "${job.label}: ${s.describe()}${s.topReason?.let { " ($it)" } ?: ""}")
                val label = if (s.paused) s.describe() + " — pick the same folder again to continue" else s.describe()
                _import.value = ImportProgress(label, s.scanned, s.added, s.duplicates, s.rejected, running = false, message = s.topReason)
            },
            onFailure = { e ->
                graph.events.record("import", "${job.label} failed: ${e.message}")
                _import.value = ImportProgress(e.message ?: "Import failed", 0, 0, 0, 0, running = false)
            },
        )
    }

    private fun progress(t: ImportTick) {
        _import.value = ImportProgress(currentLabel, t.scanned, t.added, t.duplicates, t.rejected, running = true, message = t.current)
    }

    /** Keeps an import alive for a while when the user leaves the app; on expiry the import pauses cleanly. */
    private fun beginBackgroundTask() {
        if (backgroundTask != UIBackgroundTaskInvalid) return
        backgroundTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("StickerDock import") {
            stopRequested = true
            endBackgroundTask()
        }
    }

    private fun endBackgroundTask() {
        val task = backgroundTask
        if (task == UIBackgroundTaskInvalid) return
        backgroundTask = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(task)
    }

    // ---------------- sharing and backup ----------------

    override fun sharePack(packId: String) {
        scope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    autoreleasepool {
                        val pack = library.pack(packId) ?: error("Pack not found")
                        val tray = PackSnapshot.tray(library, packId, Images::trayPng)
                        val path = Dirs.freshTemp("share") + "/" + Archives.fileName(pack.name) + ".wastickers"
                        writeFile(path) { sink -> Archives.writePack(library, packId, tray, sink, epochMillis()) }
                        path
                    }
                }
            }
            file.fold(
                onSuccess = { presenter.share(NSURL.fileURLWithPath(it)) },
                onFailure = { showMessage("Couldn't prepare the pack: ${it.message}") },
            )
        }
    }

    override fun exportBackup() {
        showMessage("Preparing backup…")
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    autoreleasepool {
                        val path = Dirs.freshTemp("backup") + "/StickerDock-backup.zip"
                        var count = 0
                        writeFile(path) { sink -> count = Archives.writeBackup(library, sink, epochMillis()) }
                        path to count
                    }
                }
            }
            result.fold(
                onSuccess = { (path, count) ->
                    presenter.exportFile(NSURL.fileURLWithPath(path)) { saved ->
                        Dirs.remove(path)
                        graph.events.record("backup", if (saved) "saved $count stickers" else "not saved")
                        showMessage(if (saved) "Backup saved ($count stickers)" else "Backup not saved")
                    }
                },
                onFailure = { showMessage("Backup failed: ${it.message}") },
            )
        }
    }

    private fun writeFile(path: String, write: (FileSink) -> Unit) {
        val sink = FileSink(path)
        try {
            write(sink)
        } catch (e: Throwable) {
            runCatching { sink.close() }
            Dirs.remove(path)
            throw e
        }
        sink.close()
    }

    // ---------------- keyboard (planned) ----------------

    override fun openKeyboardSettings() = showMessage(KEYBOARD_PLANNED)

    override fun pickKeyboard() = showMessage(KEYBOARD_PLANNED)

    override fun openKeyboardTest() = showMessage(KEYBOARD_PLANNED)

    // ---------------- misc ----------------

    override fun showMessage(text: String) {
        _message.update { HostMessage((it?.id ?: 0L) + 1, text) }
    }

    fun dismissMessage(id: Long) {
        _message.update { if (it?.id == id) null else it }
    }

    override fun diagnostics(): String = buildString {
        val device = UIDevice.currentDevice
        appendLine("StickerDock $appVersion ($appId) · iPhone")
        appendLine("Device ${device.model} (${machine()}), ${device.systemName} ${device.systemVersion}")
        appendLine("WhatsApp URL scheme: ${if (WhatsAppSender.installed()) "available" else "not available"}")
        appendLine("WhatsApp on iPhone: one pack per send via the pasteboard; WhatsApp keeps a snapshot (no live updates, no account choice)")
        appendLine("Keyboard: planned for iPhone (not in this build)")
        val counts = library.counts()
        appendLine("Library: ${counts.values.sum()} stickers (static ${counts[StickerKind.STATIC] ?: 0}, animated ${counts[StickerKind.ANIMATED] ?: 0})")
        val packs = library.packs(includeAdult = true)
        appendLine("Packs: ${packs.size}, ready for WhatsApp ${packs.count { it.whatsAppReady }}")
        appendLine("Discover: ${if (graph.discover.configured) "configured" else "offline"}")
        appendLine("Storage: ${Dirs.support}")
        appendLine()
        appendLine("Recent events:")
        graph.events.lines().takeLast(80).forEach { appendLine(it) }
    }

    private fun machine(): String = memScoped {
        val info = alloc<utsname>()
        if (uname(info.ptr) == 0) info.machine.toKString() else "unknown"
    }

    /** Purges week-old deleted stickers; refreshes followed Discover packs at most twice a day (Android: MaintenanceWorker). */
    private suspend fun maintenance() {
        withContext(Dispatchers.Default) {
            val now = epochMillis()
            runCatching { library.purgeDeleted(now - 7L * 24 * 3600 * 1000) }
                .onSuccess { if (it > 0) graph.events.record("maintenance", "purged $it deleted stickers") }
            val last = prefs.getString(KEY_DISCOVER_REFRESH)?.toLongOrNull() ?: 0L
            if (graph.discover.configured && now - last > 12L * 3600 * 1000) {
                runCatching { DiscoverSaver(library, graph.discover).refreshFollowed() }
                    .onSuccess { updated ->
                        prefs.putString(KEY_DISCOVER_REFRESH, now.toString())
                        if (updated > 0) graph.events.record("maintenance", "updated $updated followed packs")
                    }
            }
        }
    }

    companion object {
        const val TARGET_ID = "ios:whatsapp"
        private const val KEY_FOLDER_NOTE = "ios_folder_note_seen"
        private const val KEY_DISCOVER_REFRESH = "ios_discover_refreshed_at"
        private const val KEYBOARD_PLANNED =
            "The StickerDock keyboard for iPhone is planned, but not in this build yet. Use Add to WhatsApp on a pack."
    }
}

/** Runs [block] with access to a security-scoped URL from the document picker or "Open in". */
internal fun <T> withSecurityScope(url: NSURL, block: () -> T): T {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        return block()
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

/** A folder in Files: enumerated with NSFileManager; hidden items (and iCloud placeholders) are skipped. */
internal class FilesFolder(private val root: String, override val name: String) : FolderSource {
    override fun images(maxDepth: Int, maxEntries: Int): List<String> {
        val all = NSFileManager.defaultManager.subpathsOfDirectoryAtPath(root, null).orEmpty().filterIsInstance<String>()
        return all.asSequence()
            .filter { path -> path.split('/').let { parts -> parts.size <= maxDepth + 1 && parts.none { it.startsWith(".") } } }
            .filter { extensionOf(it) in IMAGE_EXTENSIONS }
            .sorted()
            .take(maxEntries)
            .toList()
    }

    override fun read(path: String, maxBytes: Int): ByteArray = autoreleasepool {
        val file = try {
            MappedFile("$root/$path")
        } catch (e: Exception) {
            throw IllegalStateException("Unreadable")
        }
        if (file.size > maxBytes) throw IllegalStateException("Larger than ${maxBytes / 1_000_000} MB")
        file.read(0, file.size.toInt())
    }
}
