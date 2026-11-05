package app.stickerdock.android.tags

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stickerdock.android.AppGraph
import app.stickerdock.android.dual.Dual
import java.util.concurrent.TimeUnit

/**
 * Background pass that reads the words on stickers that came without any (most of a WhatsApp import), so
 * library and keyboard search can find them. Runs after imports and at app start; resumes where it stopped.
 */
class AutoTagWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        val library = graph.library
        var done = 0
        AutoTagger(applicationContext).use { tagger ->
            tagger.requestModels()
            while (!isStopped) {
                val batch = library.autoTagQueue(40)
                if (batch.isEmpty()) break
                for (job in batch) {
                    if (isStopped) break
                    val reading = try {
                        library.files.read(job.id)?.let(tagger::read)
                    } catch (e: AutoTagger.ModelNotReady) {
                        graph.events.record("tags", "text models not ready yet; retrying later ($done read so far)")
                        return Result.retry()
                    } catch (e: Exception) {
                        // One unreadable sticker must not stall the queue: record it as read (emoji words only).
                        graph.events.record("tags", "couldn't read ${job.id.take(8)}: ${e.javaClass.simpleName}")
                        null
                    }
                    library.applyAutoTags(job.id, reading?.allText.orEmpty(), reading?.labels.orEmpty(), reading?.caption)
                    done++
                    if (done % 10 == 0) setProgress(workDataOf(KEY_DONE to done))
                }
            }
        }
        if (done > 0) graph.events.record("tags", "read words on $done sticker(s): ${library.autoTagProgress().let { "${it.done}/${it.total}" }}")
        return Result.success()
    }

    companion object {
        const val UNIQUE = "auto-tags"
        const val KEY_DONE = "done"

        /** Starts (or keeps) the pass; the main library only, never the Dual Messenger companion. */
        fun enqueue(context: Context) {
            if (Dual.isCompanion(context)) return
            val request = OneTimeWorkRequestBuilder<AutoTagWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }
    }
}
