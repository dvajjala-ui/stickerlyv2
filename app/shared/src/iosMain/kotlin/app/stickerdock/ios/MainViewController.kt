package app.stickerdock.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.stickerdock.ui.StickerDockRoot
import app.stickerdock.ui.StickerDockTheme
import kotlinx.coroutines.delay
import platform.Foundation.NSURL
import platform.UIKit.UIViewController

/**
 * The whole app for iPhone: the shared Compose UI on top of the iOS host. Swift calls
 * `MainViewControllerKt.MainViewController()` (see iosApp/iosApp/ContentView.swift).
 */
fun MainViewController(): UIViewController {
    val graph = IosGraph.shared
    val presenter = Presenter()
    val host = IosHost(graph, presenter)
    val controller = ComposeUIViewController {
        Box(Modifier.fillMaxSize()) {
            StickerDockRoot(host, graph.library, graph.discover)
            MessageOverlay(host)
        }
    }
    presenter.attach(controller)
    OpenedDocuments.attach(host)
    return controller
}

/**
 * A .zip / .wastickers file opened with StickerDock ("Open in", share sheet, Files). Swift calls
 * `MainViewControllerKt.openDocument(url:)` from `.onOpenURL`. Returns true when the file was accepted.
 */
fun openDocument(url: NSURL): Boolean = OpenedDocuments.open(url)

/** Holds files opened before the UI exists (cold start from "Open in"). Main thread only. */
internal object OpenedDocuments {
    private var host: IosHost? = null
    private val pending = mutableListOf<NSURL>()

    fun attach(target: IosHost) {
        host = target
        val queued = pending.toList()
        pending.clear()
        queued.forEach(target::openDocument)
    }

    fun open(url: NSURL): Boolean {
        if (!url.fileURL) return false
        val target = host
        if (target == null) pending += url else target.openDocument(url)
        return true
    }
}

/** Toast-style message above the bottom navigation; tap to dismiss. */
@Composable
private fun MessageOverlay(host: IosHost) {
    val message by host.message.collectAsState()
    val current = message ?: return
    LaunchedEffect(current.id) {
        delay(if (current.text.length > 90) 6_500L else 3_500L)
        host.dismissMessage(current.id)
    }
    StickerDockTheme {
        Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 112.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.clickable { host.dismissMessage(current.id) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Text(current.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
