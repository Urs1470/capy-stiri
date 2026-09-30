package com.capyreader.app.ui.digest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jocmp.capy.logging.CapyLog
import kotlinx.coroutines.CancellationException

/**
 * The morning sync of the digest at [MORNING_SYNC_TIME]: one run of [MorningSync], which queues the next day's and
 * refreshes the account the way the periodic refresh does. A refresh that did not go through is tried again, by
 * WorkManager's own back-off, up to [MAX_ATTEMPTS] times in all.
 */
class MorningSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val sync: MorningSync,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        CapyLog.info("morning_sync_worker:start", mapOf("attempt" to runAttemptCount))

        val outcome = try {
            sync.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CapyLog.error("morning_sync_worker", e)
            MorningSync.Outcome.NOT_REFRESHED
        }

        CapyLog.info("morning_sync_worker:done", mapOf("outcome" to outcome.name))

        return when {
            outcome != MorningSync.Outcome.NOT_REFRESHED -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
