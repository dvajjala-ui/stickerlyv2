package app.stickerdock.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stickerdock.android.dual.Dual
import app.stickerdock.discover.DiscoverClient
import app.stickerdock.discover.DiscoverSaver
import java.util.concurrent.TimeUnit

/** Twice a day: pull new stickers into followed Discover packs and purge week-old deleted stickers. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val purged = graph.library.purgeDeleted(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
        val client = DiscoverClient.create(BuildConfig.DISCOVER_URL, BuildConfig.DISCOVER_ANON_KEY)
        val updated = if (client.configured) runCatching { DiscoverSaver(graph.library, client).refreshFollowed() }.getOrDefault(0) else 0
        if (purged > 0 || updated > 0) graph.events.record("maintenance", "purged $purged deleted stickers, updated $updated followed packs")
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            if (Dual.isCompanion(context)) return
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
