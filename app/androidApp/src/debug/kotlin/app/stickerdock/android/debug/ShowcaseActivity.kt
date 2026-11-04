package app.stickerdock.android.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.stickerdock.android.AndroidHost
import app.stickerdock.android.AppGraph
import app.stickerdock.android.BuildConfig
import app.stickerdock.discover.DiscoverClient
import app.stickerdock.ui.StickerDockRoot

/**
 * Debug builds only: the real app UI, allowed to draw over the lock screen so screens can be reviewed
 * over adb while the owner's phone stays locked. It never unlocks the device or opens other apps.
 *   adb shell am start -n app.stickerdock.dev/app.stickerdock.android.debug.ShowcaseActivity
 */
class ShowcaseActivity : ComponentActivity() {
    private lateinit var host: AndroidHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        val graph = AppGraph.get(this)
        host = AndroidHost(this, graph)
        setContent { StickerDockRoot(host, graph.library, DiscoverClient.create(BuildConfig.DISCOVER_URL, BuildConfig.DISCOVER_ANON_KEY)) }
    }

    override fun onResume() {
        super.onResume()
        host.refreshWhatsApp()
    }
}
