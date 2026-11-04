package app.stickerdock.android.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import app.stickerdock.android.dual.Dual
import app.stickerdock.host.TargetKind
import app.stickerdock.host.WhatsAppTarget

/** Finds every WhatsApp the library can reach and answers "is this pack added there?". */
class WhatsAppDirectory(private val context: Context) {

    fun targets(): List<WhatsAppTarget> {
        val me = Dual.myUserId()
        val result = mutableListOf<WhatsAppTarget>()
        PACKAGES.forEach { (pkg, kind) ->
            if (installedHere(pkg)) result += WhatsAppTarget("$me:$pkg", label(kind, null), pkg, me, kind, ready = true)
        }
        if (Dual.isCompanion(context)) return result

        val companions = Dual.companionProfiles(context).map(Dual::userId).toSet()
        val granted = Dual.crossUserGranted(context)
        Dual.otherProfiles(context).forEach { profile ->
            val uid = Dual.userId(profile)
            PACKAGES.forEach { (pkg, kind) ->
                val present = installedIn(pkg, profile) || (granted && Dual.whatsAppReachable(context, pkg, uid))
                if (present) {
                    val companion = uid in companions
                    val hint = when {
                        !companion -> "Needs the one-time Dual Messenger setup (install StickerDock in this profile)"
                        else -> null
                    }
                    result += WhatsAppTarget("$uid:$pkg", label(kind, uid), pkg, uid, TargetKind.CLONE,
                        ready = companion, setupHint = hint)
                }
            }
        }
        return result
    }

    /** Null when WhatsApp can't be asked (old WhatsApp, or another profile without permission). */
    fun isAdded(target: WhatsAppTarget, packId: String): Boolean? =
        Dual.isPackAdded(context, target.packageName, target.userId, packId)
            ?: if (target.kind == TargetKind.CLONE) Dual.reportedStatus(context, packId) else null

    fun enableIntent(packId: String, packName: String, packageName: String?) =
        Intent(ACTION_ENABLE).apply {
            if (packageName != null) setPackage(packageName)
            putExtra(EXTRA_ID, packId)
            putExtra(EXTRA_AUTHORITY, "${context.packageName}.stickers")
            putExtra(EXTRA_NAME, packName)
        }

    private fun installedHere(pkg: String) = runCatching {
        context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).enabled
    }.getOrDefault(false)

    private fun installedIn(pkg: String, profile: UserHandle) = runCatching {
        context.getSystemService(LauncherApps::class.java).getActivityList(pkg, profile).isNotEmpty()
    }.getOrDefault(false)

    private fun label(kind: TargetKind, userId: Int?) = when {
        userId != null -> if (kind == TargetKind.BUSINESS) "Business · Dual Messenger" else "WhatsApp · Dual Messenger"
        kind == TargetKind.BUSINESS -> "WhatsApp Business"
        else -> "WhatsApp"
    }

    companion object {
        const val ACTION_ENABLE = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
        const val EXTRA_ID = "sticker_pack_id"
        const val EXTRA_AUTHORITY = "sticker_pack_authority"
        const val EXTRA_NAME = "sticker_pack_name"
        val PACKAGES = listOf("com.whatsapp" to TargetKind.WHATSAPP, "com.whatsapp.w4b" to TargetKind.BUSINESS)
    }
}
