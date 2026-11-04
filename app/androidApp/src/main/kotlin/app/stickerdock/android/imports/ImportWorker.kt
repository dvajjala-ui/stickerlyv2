package app.stickerdock.android.imports

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stickerdock.android.AppGraph

/** Runs folder and archive imports in a foreground job that survives leaving the app. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        runCatching { setForeground(foreground("Importing stickers…")) }
        return try {
            val summary = when (inputData.getString(KEY_MODE)) {
                MODE_FOLDER -> {
                    val tree = Uri.parse(requireNotNull(inputData.getString(KEY_URI)))
                    val session = inputData.getString(KEY_SESSION)
                        ?: graph.library.activeImports().firstOrNull { it.source == tree.toString() }?.id
                        ?: graph.library.startImport(tree.toString(), MODE_FOLDER)
                    graph.library.setImportState(session, "RUNNING")
                    FolderImporter(applicationContext).run(tree, session, stopped = { isStopped }) { t ->
                        setProgress(progressData(t))
                        runCatching { setForeground(foreground("Importing · ${t.added} added · ${t.scanned} checked")) }
                    }
                }
                MODE_ARCHIVES -> {
                    val uris = inputData.getStringArray(KEY_URIS).orEmpty().map(Uri::parse)
                    var total = ImportSummary(0, 0, 0, 0)
                    uris.forEachIndexed { index, uri ->
                        setProgress(workDataOf(P_LABEL to "Archive ${index + 1} of ${uris.size}", P_SCANNED to index))
                        val s = runCatching { ArchiveImporter(applicationContext).import(uri) }
                            .getOrElse { graph.events.record("import", "archive failed: ${it.message}"); ImportSummary(0, 0, 0, 1) }
                        total = ImportSummary(total.packs + s.packs, total.added + s.added, total.duplicates + s.duplicates, total.rejected + s.rejected)
                    }
                    total
                }
                MODE_SHARED -> SharedImageImporter(applicationContext).import(inputData.getStringArray(KEY_URIS).orEmpty().map(Uri::parse))
                else -> error("Unknown import mode")
            }
            graph.events.record("import", summary.describe())
            Result.success(workDataOf(P_LABEL to summary.describe(), P_ADDED to summary.added, P_DUP to summary.duplicates,
                P_REJECTED to summary.rejected, P_PAUSED to summary.paused))
        } catch (e: Exception) {
            graph.events.record("import", "failed: ${e.message}")
            Result.failure(workDataOf(P_LABEL to (e.message ?: "Import failed")))
        }
    }

    private fun progressData(t: ImportTick) = workDataOf(P_LABEL to "Importing", P_SCANNED to t.scanned, P_ADDED to t.added,
        P_DUP to t.duplicates, P_REJECTED to t.rejected, P_CURRENT to t.current)

    private fun foreground(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Imports", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("StickerDock")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE = "stickerdock-import"
        const val KEY_MODE = "mode"; const val KEY_URI = "uri"; const val KEY_URIS = "uris"; const val KEY_SESSION = "session"
        const val MODE_FOLDER = "FOLDER"; const val MODE_ARCHIVES = "ARCHIVES"; const val MODE_SHARED = "SHARED"
        const val P_LABEL = "label"; const val P_SCANNED = "scanned"; const val P_ADDED = "added"; const val P_DUP = "dup"
        const val P_REJECTED = "rejected"; const val P_CURRENT = "current"; const val P_PAUSED = "paused"
        private const val CHANNEL = "imports"
        private const val NOTIFICATION_ID = 41

        fun enqueue(context: Context, data: Data) {
            val request = OneTimeWorkRequestBuilder<ImportWorker>().setInputData(data).addTag(UNIQUE).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun folder(context: Context, tree: Uri) = enqueue(context, workDataOf(KEY_MODE to MODE_FOLDER, KEY_URI to tree.toString()))
        fun archives(context: Context, uris: List<Uri>) =
            enqueue(context, workDataOf(KEY_MODE to MODE_ARCHIVES, KEY_URIS to uris.map(Uri::toString).toTypedArray()))
        fun shared(context: Context, uris: List<Uri>) =
            enqueue(context, workDataOf(KEY_MODE to MODE_SHARED, KEY_URIS to uris.map(Uri::toString).toTypedArray()))
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}
