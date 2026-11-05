package app.stickerdock.host

import kotlinx.coroutines.flow.StateFlow

enum class TargetKind { WHATSAPP, BUSINESS, CLONE }

/** One WhatsApp installation the library can reach (per Android user/profile). */
data class WhatsAppTarget(
    val id: String,            // "<userId>:<package>"
    val label: String,
    val packageName: String,
    val userId: Int,
    val kind: TargetKind,
    /** True when StickerDock can hand a pack to this installation right now. */
    val ready: Boolean,
    /** Shown when not ready, e.g. the one-time Dual Messenger setup. */
    val setupHint: String? = null,
)

data class DualState(
    val cloneUserId: Int? = null,
    val companionInstalled: Boolean = false,
    val crossUserAccess: Boolean = false,
    val cloneWhatsApp: Boolean = false,
)

data class WhatsAppState(
    val targets: List<WhatsAppTarget> = emptyList(),
    /** packId -> target ids where WhatsApp reports the pack as added. */
    val added: Map<String, Set<String>> = emptyMap(),
    val keyboardEnabled: Boolean = false,
    val keyboardSelected: Boolean = false,
    /** This copy runs inside a clone/work profile and serves that profile's WhatsApp. */
    val companionMode: Boolean = false,
    val dual: DualState = DualState(),
    val loading: Boolean = false,
)

data class ImportProgress(
    val label: String,
    val scanned: Int,
    val added: Int,
    val duplicates: Int,
    val rejected: Int,
    val running: Boolean,
    val message: String? = null,
)

/** Simple persisted preferences (age gate, vault, view options). */
interface Prefs {
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** Platform services the shared UI calls; Android implements them in `AndroidHost`. */
interface HostActions {
    val whatsApp: StateFlow<WhatsAppState>
    val importProgress: StateFlow<ImportProgress?>
    val prefs: Prefs
    val appVersion: String
    /** Installed application id (debug builds add a suffix). */
    val appId: String

    fun refreshWhatsApp()
    fun addToWhatsApp(packId: String, targetId: String)
    fun importPhoneFolder()
    fun importArchives()
    fun importImages()
    /** Opens the photo → sticker creator (background removal, outline, caption). */
    fun createFromPhoto()
    /** Opens the video/GIF → animated sticker creator. */
    fun createFromVideo()
    fun cancelImport()
    fun sharePack(packId: String)
    fun exportBackup()
    fun openKeyboardSettings()
    fun pickKeyboard()
    fun openKeyboardTest()
    fun showMessage(text: String)
    fun diagnostics(): String
}
