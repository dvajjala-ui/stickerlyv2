package app.stickerdock.ios

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.stickerdock.db.StickerDatabase
import app.stickerdock.library.Library
import co.touchlab.sqliter.DatabaseConfiguration
import platform.Foundation.NSUUID

/** A fresh in-memory library (named shared-cache database, so the driver's reader and writer see the same data). */
internal fun newTestLibrary(): Pair<Library, MemoryFiles> {
    val driver = NativeSqliteDriver(StickerDatabase.Schema, "test-${NSUUID().UUIDString}", onConfiguration = { config ->
        config.copy(inMemory = true, extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true))
    })
    val files = MemoryFiles()
    var clock = 1_000L
    var seq = 0
    return Library(StickerDatabase(driver), files, now = { clock++ }, newId = { "id-${++seq}" }) to files
}
