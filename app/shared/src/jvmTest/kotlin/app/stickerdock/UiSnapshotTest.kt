package app.stickerdock

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.stickerdock.core.StickerKind
import app.stickerdock.db.StickerDatabase
import app.stickerdock.discover.DiscoverClient
import app.stickerdock.host.DualState
import app.stickerdock.host.HostActions
import app.stickerdock.host.ImportProgress
import app.stickerdock.host.Prefs
import app.stickerdock.host.TargetKind
import app.stickerdock.host.WhatsAppState
import app.stickerdock.host.WhatsAppTarget
import app.stickerdock.library.AddResult
import app.stickerdock.library.Library
import app.stickerdock.library.Origin
import app.stickerdock.library.StickerFiles
import app.stickerdock.library.StickerFilter
import app.stickerdock.ui.StickerDockRoot
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.map.Mapper
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlin.test.Test

/**
 * Renders the shared screens to PNG on the JVM (Compose Desktop + Skia), so layouts can be reviewed without a phone.
 * Opt-in: `STICKERDOCK_SNAPSHOTS=1 gradlew :shared:jvmTest --tests '*UiSnapshotTest*'`. Stickers come from
 * `work/dev-feed/stickers`; Discover shots need `tools/dev_discover.py` on :8787. Output: `shared/build/ui-snapshots/`.
 * Desktop fonts differ a little from Android; spacing, wrapping and states are what to look at.
 */
@OptIn(ExperimentalTestApi::class)
class UiSnapshotTest {
    private val out = File("build/ui-snapshots").apply { mkdirs() }

    // Each test gets the runner's one-minute budget, so the tour is split in two.
    @Test fun libraryWhatsAppAndDiscover() = app { _ ->
        settle(); shot("01-library")
        tap("Packs"); settle(); shot("02-packs")
        tap("Cricket"); settle(); shot("03-pack")
        step { onAllNodes(hasText("Back") or hasContentDescription("Back")).onFirst().performClick() }; settle(1)
        tap("WhatsApp"); settle(); shot("04-whatsapp")
        step { scrollList().performScrollToIndex(5) }; settle(1); shot("05-whatsapp-shelf")
        tap("Discover"); settle(seconds = 3); shot("06-discover")
        // Scroll in steps so each page load can finish, then jump to the last item (footer).
        listOf(40, 61, 100).forEach { step { scrollList().performScrollToIndex(it) }; settle(seconds = 3) }
        shot("07-discover-page3")
        for (index in 200 downTo 100) if (step { scrollList().performScrollToIndex(index) }.isSuccess) break
        settle(1); shot("08-discover-end")
    }

    @Test fun sortSettingsAndDuplicates() = app { _ ->
        settle(1)
        tap("Newest"); settle(1); shot("09-sort-menu")
        tap("Most used"); settle(1)
        onAllNodes(hasContentDescription("Settings") and hasClickAction()).onFirst().performClick(); settle(1); shot("10-settings")
        tap("Find duplicates"); settle(); shot("11-duplicates")
        tap("Merge all"); settle(); shot("12-duplicates-merged")
        step { onAllNodes(hasText("Back") or hasContentDescription("Back")).onFirst().performClick() }; settle(1)
        step { onAllNodes(hasText("Back") or hasContentDescription("Back")).onFirst().performClick() }; settle(1)
        // Long-press a sticker to select it, then open the bulk "More" menu.
        step { onAllNodes(hasContentDescription("Monday", substring = true) and hasClickAction()).onFirst().performTouchInput { longClick() } }; settle(1)
        shot("13-selection")
        step { onAllNodes(hasContentDescription("More") and hasClickAction()).onFirst().performClick() }; settle(1)
        shot("14-selection-more")
    }

    private fun app(script: ComposeUiTest.(Library) -> Unit) {
        if (System.getenv("STICKERDOCK_SNAPSHOTS") != "1") return
        val fixtures = File("../../work/dev-feed/stickers").listFiles { f -> f.extension == "webp" }.orEmpty().sortedBy { it.name }
        require(fixtures.size >= 6) { "Put a few WhatsApp-ready .webp files in work/dev-feed/stickers" }
        // Coil's file:// handling expects Unix paths; read local stickers as bytes instead.
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context).components {
                add(Mapper<String, ByteArray> { data, _ -> if (data.startsWith("file://")) File(data.removePrefix("file://")).readBytes() else null })
            }.build()
        }
        val library = libraryWith(fixtures)
        val discover = DiscoverClient.create(System.getenv("STICKERDOCK_DISCOVER") ?: "http://127.0.0.1:8787", "dev")
        runDesktopComposeUiTest(720, 1560) {
            // Progress indicators animate forever; drive the clock by hand so the test never waits for "idle".
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) { StickerDockRoot(FakeHost(), library, discover, dark = true) }
            }
            script(library)
        }
    }

    private fun ComposeUiTest.scrollList() = onAllNodes(hasScrollAction()).onFirst()

    private fun ComposeUiTest.tap(text: String) = onAllNodes(hasText(text, substring = true) and hasClickAction()).onFirst().performClick()

    /** A scripted step that may not apply (e.g. fewer Discover items than expected): carry on. */
    private fun step(action: () -> Unit) = runCatching(action)

    /** Lets flows, Coil loads and network requests finish (they run on real dispatchers, not the test clock). */
    private fun ComposeUiTest.settle(seconds: Int = 2) {
        repeat(seconds * 5) { Thread.sleep(200); mainClock.advanceTimeBy(200) }
    }

    /** Saves the screen; an open menu or dialog is its own root and is saved next to it as `-popup`. */
    private fun ComposeUiTest.shot(name: String) {
        val roots = onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        (0 until count).forEach { i ->
            val bitmap = roots[i].captureToImage().asSkiaBitmap()
            val suffix = if (i == 0) "" else "-popup$i"
            File(out, "$name$suffix.png").writeBytes(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private fun libraryWith(fixtures: List<File>): Library {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        StickerDatabase.Schema.create(driver)
        val dir = File("build/ui-snapshots/files").apply { deleteRecursively(); mkdirs() }
        val files = object : StickerFiles {
            override fun write(id: String, bytes: ByteArray) = File(dir, "$id.webp").writeBytes(bytes)
            override fun read(id: String) = File(dir, "$id.webp").takeIf { it.isFile }?.readBytes()
            override fun delete(id: String) { File(dir, "$id.webp").delete() }
            override fun path(id: String) = File(dir, "$id.webp").absolutePath
        }
        var clock = 1_700_000_000_000
        val lib = Library(StickerDatabase(driver), files, now = { clock++ }, newId = { UUID.randomUUID().toString() })
        val labels = listOf("Monday phir aa gaya", "Chai pe charcha", "Kya baat hai boss", "Mood kharab mat karo",
            "Aaj toh party banti hai", "Chill maar yaar", "Ye kya ho raha hai", "Office ke dukh")
        fixtures.forEachIndexed { i, f -> lib.addSticker(f.readBytes(), Origin.IMPORT, label = labels[i % labels.size]) as? AddResult.Added }
        val all = lib.stickers(StickerFilter(limit = 1000))
        // Two near-duplicate groups, as if imported before hashing existed.
        all.filter { it.kind == StickerKind.STATIC }.let { statics ->
            statics.take(2).forEach { lib.setHash(it.id, 42L) }
            statics.drop(2).take(3).forEach { lib.setHash(it.id, 4242L) }
        }
        val names = listOf("From WhatsApp", "From WhatsApp · Animated", "Office Ke Dukh", "Hinglish reactions", "Monday mood",
            "Chai time", "Party", "Savage replies", "Good morning", "Bollywood", "Cricket", "Exam season")
        names.forEachIndexed { i, name ->
            val kind = if (i % 2 == 1) StickerKind.ANIMATED else StickerKind.STATIC
            val pool = all.filter { it.kind == kind }
            if (pool.size < 3) return@forEachIndexed
            val pack = lib.createPack(name, kind)
            lib.addToPack(pack, pool.shuffled(kotlin.random.Random(i)).take(minOf(pool.size, 3 + i % 4)).map { it.id })
            lib.setShelf(pack, true)
        }
        return lib
    }

    private class FakeHost : HostActions {
        private val main = WhatsAppTarget("0:com.whatsapp", "WhatsApp", "com.whatsapp", 0, TargetKind.WHATSAPP, ready = true)
        private val clone = WhatsAppTarget("95:com.whatsapp", "WhatsApp · Dual Messenger", "com.whatsapp", 95, TargetKind.CLONE, ready = true)
        override val whatsApp = MutableStateFlow(WhatsAppState(listOf(main, clone), keyboardEnabled = true,
            dual = DualState(cloneUserId = 95, companionInstalled = true, crossUserAccess = true, cloneWhatsApp = true)))
        override val importProgress = MutableStateFlow<ImportProgress?>(null)
        override val prefs = object : Prefs {
            val map = mutableMapOf<String, Any?>()
            override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
            override fun getString(key: String) = map[key] as? String
            override fun putString(key: String, value: String?) { map[key] = value }
        }
        override val appVersion = "snapshot"
        override val appId = "app.stickerdock.dev"
        override fun refreshWhatsApp() {}
        override fun addToWhatsApp(packId: String, targetId: String) {}
        override fun addToAllWhatsApps(packId: String) {}
        override fun importPhoneFolder() {}
        override fun importArchives() {}
        override fun importImages() {}
        override fun createFromPhoto() {}
        override fun createFromVideo() {}
        override fun cancelImport() {}
        override fun sharePack(packId: String) {}
        override fun exportBackup() {}
        override fun openKeyboardSettings() {}
        override fun pickKeyboard() {}
        override fun openKeyboardTest() {}
        override fun showMessage(text: String) {}
        override fun diagnostics() = ""
    }
}
