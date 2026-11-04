package app.stickerdock.android.dual

import android.content.ComponentName
import android.content.Context
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import app.stickerdock.android.AppGraph
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Dual Messenger / clone-profile plumbing.
 *
 * Android never lets WhatsApp in another user read a provider installed only in user 0
 * (ContentProviderHelper resolves providers in the caller's user). So the clone gets its own
 * StickerDock copy ("companion"), installed with `pm install-existing --user <id>`, and both
 * copies get the development permission INTERACT_ACROSS_USERS via `pm grant`. The companion then
 * serves the clone's WhatsApp directly from the main library: one library, always in sync.
 */
object Dual {
    const val HUB_USER = 0
    const val CROSS_USER = "android.permission.INTERACT_ACROSS_USERS"

    /** UserHandle.hashCode() is the user id (stable AOSP behaviour, no hidden API needed). */
    fun userId(handle: UserHandle): Int = handle.hashCode()
    fun myUserId(): Int = userId(Process.myUserHandle())

    fun isCompanion(context: Context): Boolean = myUserId() != HUB_USER

    fun crossUserGranted(context: Context) =
        context.checkSelfPermission(CROSS_USER) == PackageManager.PERMISSION_GRANTED

    /** Other profiles of this user (clone, work, private) — empty on most phones. */
    fun otherProfiles(context: Context): List<UserHandle> = runCatching {
        context.getSystemService(UserManager::class.java).userProfiles.filter { userId(it) != myUserId() }
    }.getOrDefault(emptyList())

    /** Profiles where a StickerDock copy is installed and launchable. */
    fun companionProfiles(context: Context): List<UserHandle> = runCatching {
        context.getSystemService(CrossProfileApps::class.java).targetUserProfiles
    }.getOrDefault(emptyList())

    fun launchCompanion(context: Context, profile: UserHandle) {
        val main = ComponentName(context.packageName, "app.stickerdock.android.MainActivity")
        context.getSystemService(CrossProfileApps::class.java).startMainActivity(main, profile)
    }

    /** True/false from WhatsApp's whitelist provider in [userId]; null if it can't be asked. */
    fun isPackAdded(context: Context, whatsAppPackage: String, userId: Int, packId: String): Boolean? {
        val prefix = if (userId == myUserId()) "" else "$userId@"
        val uri = Uri.Builder().scheme("content")
            .encodedAuthority("$prefix$whatsAppPackage.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", "${context.packageName}.stickers")
            .appendQueryParameter("identifier", packId)
            .build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else c.getColumnIndex("result").takeIf { it >= 0 }?.let { c.getInt(it) == 1 }
            }
        }.getOrNull()
    }

    /** Whether [whatsAppPackage] exists in [userId], judged by its whitelist provider answering. */
    fun whatsAppReachable(context: Context, whatsAppPackage: String, userId: Int): Boolean =
        isPackAdded(context, whatsAppPackage, userId, "probe") != null

    // ---- hub-side queue of "add this pack in the clone's WhatsApp" requests ----

    data class Pending(val id: String, val packId: String, val packName: String, val whatsAppPackage: String)

    private fun prefs(context: Context) = context.getSharedPreferences("dual", Context.MODE_PRIVATE)

    fun enqueue(context: Context, packId: String, packName: String, whatsAppPackage: String): Pending {
        val item = Pending(UUID.randomUUID().toString(), packId, packName, whatsAppPackage)
        synchronized(this) {
            val list = pendingJson(context).put(JSONObject().put("id", item.id).put("pack", packId)
                .put("name", packName).put("pkg", whatsAppPackage))
            prefs(context).edit().putString("pending", list.toString()).apply()
        }
        return item
    }

    fun pending(context: Context): List<Pending> = synchronized(this) {
        val array = pendingJson(context)
        List(array.length()) { i ->
            array.getJSONObject(i).let { Pending(it.getString("id"), it.getString("pack"), it.getString("name"), it.getString("pkg")) }
        }
    }

    fun complete(context: Context, id: String) = synchronized(this) {
        val array = pendingJson(context)
        val next = JSONArray()
        for (i in 0 until array.length()) array.getJSONObject(i).takeIf { it.getString("id") != id }?.let(next::put)
        prefs(context).edit().putString("pending", next.toString()).apply()
    }

    /** Last add result the companion reported per pack (used when the hub can't query cross-user). */
    fun reportStatus(context: Context, packId: String, added: Boolean) {
        prefs(context).edit().putBoolean("added:$packId", added).apply()
        AppGraph.get(context).events.record("dual", "companion reports $packId added=$added")
    }

    fun reportedStatus(context: Context, packId: String): Boolean? =
        prefs(context).takeIf { it.contains("added:$packId") }?.getBoolean("added:$packId", false)

    private fun pendingJson(context: Context) =
        runCatching { JSONArray(prefs(context).getString("pending", "[]")) }.getOrDefault(JSONArray())

    /** One-time setup commands for the owner's PC (shown in the app and docs). */
    fun setupCommands(packageName: String, cloneUser: Int?): String {
        val u = cloneUser?.toString() ?: "<clone user id from: adb shell pm list users>"
        return listOf(
            "adb shell pm install-existing --user $u $packageName",
            "adb shell pm grant $packageName $CROSS_USER",
            "adb shell pm grant --user $u $packageName $CROSS_USER",
        ).joinToString("\n")
    }
}
