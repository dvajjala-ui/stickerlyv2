package app.stickerdock.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import app.stickerdock.android.dual.CompanionScreen
import app.stickerdock.android.dual.Dual
import app.stickerdock.android.imports.ImportWorker
import app.stickerdock.discover.DiscoverClient
import app.stickerdock.ui.StickerDockRoot

class MainActivity : ComponentActivity() {
    private lateinit var host: AndroidHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AppGraph.get(this)
        host = AndroidHost(this, graph)
        if (savedInstanceState == null) handleShare(intent)
        val discover = DiscoverClient.create(BuildConfig.DISCOVER_URL, BuildConfig.DISCOVER_ANON_KEY)
        setContent {
            if (Dual.isCompanion(this)) CompanionScreen(host)
            else StickerDockRoot(host, graph.library, discover)
        }
    }

    override fun onResume() {
        super.onResume()
        host.refreshWhatsApp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Images, ZIPs and .wastickers shared or opened from other apps. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val (archives, images) = uris.partition { uri ->
            val type = contentResolver.getType(uri).orEmpty()
            type.contains("zip") || type.contains("wastickers") || uri.toString().endsWith(".wastickers") ||
                uri.toString().endsWith(".zip") || type == "application/octet-stream"
        }
        if (archives.isNotEmpty()) ImportWorker.archives(this, archives)
        if (images.isNotEmpty()) ImportWorker.shared(this, images)
        host.showMessage("Adding ${uris.size} item(s) to your library")
    }
}
