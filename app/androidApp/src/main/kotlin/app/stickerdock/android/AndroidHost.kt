package app.stickerdock.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.stickerdock.android.dual.Dual
import app.stickerdock.android.export.Exporters
import app.stickerdock.android.imports.ImportWorker
import app.stickerdock.android.keyboard.KeyboardTestActivity
import app.stickerdock.android.whatsapp.WhatsAppDirectory
import app.stickerdock.android.whatsapp.WhatsAppValidator
import app.stickerdock.host.DualState
import app.stickerdock.host.HostActions
import app.stickerdock.host.ImportProgress
import app.stickerdock.host.Prefs
import app.stickerdock.host.TargetKind
import app.stickerdock.host.WhatsAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidPrefs(context: Context) : Prefs {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String?) = sp.edit().putString(key, value).apply()
}

/** Android implementation of everything the shared UI asks the platform to do. */
class AndroidHost(private val activity: ComponentActivity, private val graph: AppGraph) : HostActions {
    private val directory = WhatsAppDirectory(activity)
    private val _whatsApp = MutableStateFlow(WhatsAppState(loading = true))
    private val _import = MutableStateFlow<ImportProgress?>(null)
    override val whatsApp: StateFlow<WhatsAppState> = _whatsApp
    override val importProgress: StateFlow<ImportProgress?> = _import
    override val prefs: Prefs = AndroidPrefs(activity)
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val appId: String = activity.packageName

    private var pendingAdd: Pair<String, String>? = null

    private val enableLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (packId, targetId) = pendingAdd ?: return@registerForActivityResult
        pendingAdd = null
        val error = result.data?.getStringExtra("validation_error")
        val outcome = when {
            error != null -> "WhatsApp rejected the pack: $error"
            result.resultCode == Activity.RESULT_OK -> "Added to WhatsApp"
            else -> "Not added"
        }
        graph.events.record("whatsapp", "add $packId to $targetId → result=${result.resultCode} $outcome")
        if (error != null || result.resultCode == Activity.RESULT_OK) showMessage(outcome)
        refreshWhatsApp()
    }

    private val folderLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ImportWorker.folder(activity, uri)
            showMessage("Importing in the background — you can keep using the app")
        }
    }

    private val archiveLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { activity.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            ImportWorker.archives(activity, uris)
        }
    }

    private val imageLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { activity.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            ImportWorker.shared(activity, uris)
        }
    }

    private val backupLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Exporters.libraryBackup(activity, uri) } }
            showMessage(result.fold({ "Backup saved ($it stickers)" }, { "Backup failed: ${it.message}" }))
        }
    }

    init {
        activity.lifecycleScope.launch {
            WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow(ImportWorker.UNIQUE).collect { infos ->
                val info = infos.lastOrNull { !it.state.isFinished } ?: infos.lastOrNull()
                _import.value = info?.let(::toProgress)
            }
        }
    }

    private fun toProgress(info: WorkInfo): ImportProgress? {
        val running = !info.state.isFinished
        val data = if (running) info.progress else info.outputData
        if (!running && info.state == WorkInfo.State.CANCELLED) return null
        return ImportProgress(
            label = data.getString(ImportWorker.P_LABEL) ?: if (running) "Importing" else "Import finished",
            scanned = data.getInt(ImportWorker.P_SCANNED, 0),
            added = data.getInt(ImportWorker.P_ADDED, 0),
            duplicates = data.getInt(ImportWorker.P_DUP, 0),
            rejected = data.getInt(ImportWorker.P_REJECTED, 0),
            running = running,
            message = data.getString(ImportWorker.P_CURRENT),
        )
    }

    override fun refreshWhatsApp() {
        activity.lifecycleScope.launch {
            _whatsApp.value = withContext(Dispatchers.IO) { computeState() }
        }
    }

    private fun computeState(): WhatsAppState {
        val targets = directory.targets()
        val packs = graph.library.packs(includeAdult = true) + graph.library.packs(includeAdult = true, vault = true)
        val added = packs.filter { it.whatsAppReady }.associate { pack ->
            pack.id to targets.filter { it.ready && directory.isAdded(it, pack.id) == true }.map { it.id }.toSet()
        }
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == activity.packageName }
        val selected = Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(activity.packageName + "/") == true
        val cloneProfile = Dual.otherProfiles(activity).firstOrNull()
        val dual = DualState(
            cloneUserId = cloneProfile?.let(Dual::userId),
            companionInstalled = Dual.companionProfiles(activity).isNotEmpty(),
            crossUserAccess = Dual.crossUserGranted(activity),
            cloneWhatsApp = targets.any { it.kind == TargetKind.CLONE },
        )
        return WhatsAppState(targets, added, enabled, selected, Dual.isCompanion(activity), dual, loading = false)
    }

    override fun addToWhatsApp(packId: String, targetId: String) {
        val target = _whatsApp.value.targets.firstOrNull { it.id == targetId } ?: return showMessage("WhatsApp not found")
        val pack = graph.library.pack(packId) ?: return
        if (!pack.whatsAppReady) return showMessage("WhatsApp needs 3–30 stickers in a pack")
        graph.library.setShelf(packId, true)
        val report = WhatsAppValidator(activity).validate(packId)
        if (!report.ok) {
            graph.events.record("whatsapp", "pre-flight failed for $packId: ${report.problems.take(3)}")
            return showMessage("This pack needs a fix first: ${report.problems.first()}")
        }
        if (target.kind == TargetKind.CLONE) {
            val profile = Dual.companionProfiles(activity).firstOrNull { Dual.userId(it) == target.userId }
                ?: return showMessage(target.setupHint ?: "Set up Dual Messenger first")
            val pending = Dual.enqueue(activity, packId, pack.name, target.packageName)
            graph.events.record("dual", "queued $packId for user ${target.userId}; opening companion")
            runCatching { Dual.launchCompanion(activity, profile, pending.id) }
                .onFailure {
                    graph.events.record("dual", "companion launch failed: ${it.message}")
                    showMessage("Open StickerDock in Dual Messenger to finish adding “${pack.name}”")
                }
            return
        }
        pendingAdd = packId to targetId
        runCatching { enableLauncher.launch(directory.enableIntent(packId, pack.name, target.packageName)) }
            .onFailure {
                pendingAdd = null
                showMessage("${target.label} couldn't open the add screen. Update WhatsApp and try again.")
                graph.events.record("whatsapp", "launch failed: ${it.message}")
            }
    }

    override fun importPhoneFolder() {
        // Start the system picker inside WhatsApp's sticker folder when it exists.
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers")
        runCatching { folderLauncher.launch(initial) }.onFailure { folderLauncher.launch(null) }
    }

    override fun importArchives() = archiveLauncher.launch(arrayOf("application/zip", "application/x-wastickers",
        "application/x-zip-compressed", "application/octet-stream"))

    override fun importImages() = imageLauncher.launch(arrayOf("image/*"))

    override fun cancelImport() { ImportWorker.cancel(activity) }

    override fun sharePack(packId: String) {
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { Exporters.packArchive(activity, packId) } }.getOrElse {
                return@launch showMessage("Couldn't prepare the pack: ${it.message}")
            }
            val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("application/x-wastickers").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(send, "Share pack"))
        }
    }

    override fun exportBackup() = backupLauncher.launch("StickerDock-backup.zip")

    override fun openKeyboardSettings() = activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    override fun pickKeyboard() = activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    override fun openKeyboardTest() = activity.startActivity(Intent(activity, KeyboardTestActivity::class.java))

    override fun showMessage(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    override fun diagnostics(): String = buildString {
        val state = _whatsApp.value
        appendLine("StickerDock ${BuildConfig.VERSION_NAME} (${activity.packageName})")
        appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Android user ${Dual.myUserId()}${if (state.companionMode) " · companion copy" else " · main library"}")
        appendLine("Cross-user permission: ${state.dual.crossUserAccess}")
        appendLine("Other profiles: ${Dual.otherProfiles(activity).map(Dual::userId)}")
        appendLine("StickerDock copies in profiles: ${Dual.companionProfiles(activity).map(Dual::userId)}")
        appendLine("Keyboard enabled=${state.keyboardEnabled} selected=${state.keyboardSelected}")
        appendLine("Targets:")
        state.targets.forEach { appendLine("  ${it.label} [${it.id}] ready=${it.ready}${it.setupHint?.let { h -> " — $h" } ?: ""}") }
        appendLine("Shelf: ${graph.library.shelfPacks().joinToString { "${it.name}(${it.count}, v${it.dataVersion})" }}")
        appendLine()
        appendLine("Recent events:")
        graph.events.lines().takeLast(80).forEach { appendLine(it) }
    }
}
