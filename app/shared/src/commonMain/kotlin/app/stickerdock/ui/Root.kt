package app.stickerdock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.stickerdock.discover.DiscoverClient
import app.stickerdock.discover.DiscoverSaver
import app.stickerdock.host.HostActions
import app.stickerdock.library.Library
import app.stickerdock.library.StickerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable data class PackRoute(val id: String)
@Serializable object SettingsRoute
@Serializable object DuplicatesRoute

/** UI-wide state and services. */
class AppModel(val host: HostActions, val library: Library, val discover: DiscoverClient) {
    val saver = DiscoverSaver(library, discover)
    var adultEnabled by mutableStateOf(host.prefs.getBoolean(KEY_ADULT))
        private set
    var vaultOpen by mutableStateOf(false)

    fun model(item: StickerItem): Any = localModel(library.files.path(item.id))

    fun setAdult(enabled: Boolean) {
        host.prefs.putBoolean(KEY_ADULT, enabled)
        adultEnabled = enabled
    }

    companion object {
        const val KEY_ADULT = "adult_enabled"
    }
}

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Shows [message]; when [undo] is given, offers an Undo action. */
fun CoroutineScope.notify(snackbar: SnackbarHostState, message: String, undo: (() -> Unit)? = null) = launch {
    val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) "Undo" else null, withDismissAction = undo == null)
    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) undo?.invoke()
}

@Composable
fun StickerDockRoot(host: HostActions, library: Library, discover: DiscoverClient, dark: Boolean = isSystemInDarkTheme()) {
    StickerDockTheme(dark) {
        val app = remember { AppModel(host, library, discover) }
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> {
                    HomeScreen(app, onOpenPack = { nav.navigate(PackRoute(it)) }, onOpenSettings = { nav.navigate(SettingsRoute) })
                }
                composable<PackRoute> { entry ->
                    PackScreen(app, entry.toRoute<PackRoute>().id, onBack = { nav.popBackStack() })
                }
                composable<SettingsRoute> {
                    SettingsScreen(app, onBack = { nav.popBackStack() }, onOpenDuplicates = { nav.navigate(DuplicatesRoute) })
                }
                composable<DuplicatesRoute> { DuplicatesScreen(app, onBack = { nav.popBackStack() }) }
            }
        }
    }
}

private enum class HomeTab(val label: String) { LIBRARY("My stickers"), DISCOVER("Discover"), WHATSAPP("WhatsApp") }

@Composable
private fun HomeScreen(app: AppModel, onOpenPack: (String) -> Unit, onOpenSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(when (item) {
                                HomeTab.LIBRARY -> Icons.Filled.Home
                                HomeTab.DISCOVER -> Icons.Filled.Search
                                HomeTab.WHATSAPP -> Icons.Filled.Send
                            }, contentDescription = null)
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            HomeTab.LIBRARY -> LibraryScreen(app, modifier, onOpenPack, onOpenSettings, scope)
            HomeTab.DISCOVER -> DiscoverScreen(app, modifier, onOpenPack, scope)
            HomeTab.WHATSAPP -> WhatsAppScreen(app, modifier, onOpenPack, onOpenSettings)
        }
    }
}
