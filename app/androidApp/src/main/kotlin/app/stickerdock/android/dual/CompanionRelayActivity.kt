package app.stickerdock.android.dual

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import app.stickerdock.android.AppGraph
import app.stickerdock.android.MainActivity

/**
 * Entry point from the main profile into the companion copy.
 *
 * CrossProfileApps does not list clone profiles as targets from the parent (verified on One UI 8.5),
 * but Android forwards ACTION_SEND from the parent into the clone profile. The main copy disables this
 * component for itself, so its "add to Dual Messenger" share intent resolves only to the companion,
 * which then opens and runs the queued add requests against the clone's WhatsApp.
 */
class CompanionRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.get(this).events.record("relay", "opened in user ${Dual.myUserId()} request=${intent.getStringExtra(Intent.EXTRA_TEXT)}")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        const val MIME = "application/vnd.stickerdock.request"

        /** Main copy only: keep the relay off so requests can't loop back to ourselves. */
        fun configure(context: Context) {
            val component = ComponentName(context, CompanionRelayActivity::class.java)
            val wanted = if (Dual.isCompanion(context)) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != wanted) {
                context.packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }

        fun request(pendingId: String) = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_TEXT, pendingId)
    }
}
