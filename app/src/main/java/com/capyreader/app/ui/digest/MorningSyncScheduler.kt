package com.capyreader.app.ui.digest

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Queues the morning sync of the digest (see [MorningSync]): one piece of work that waits until [MORNING_SYNC_TIME]
 * and is queued again, for the next day, by the run that it starts. Periodic work can't be used, because it drifts
 * from the time of day it began at and doesn't follow a change of summer time.
 *
 * Every moment has its own unique name, so queueing is harmless to repeat: a sync that is already queued for that moment
 * is kept as it is (`KEEP`), which also lets a run queue the next day without cancelling itself, as replacing its own
 * name would.
 *
 * The time comes from [clock] and [zone] (read on every call, so a phone that changes time zone is followed).
 */
class MorningSyncScheduler(
    private val workManager: WorkManager,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val at: LocalTime = MORNING_SYNC_TIME,
) {
    /** Queues the next morning sync unless it is queued already; returns the time it waits for. */
    fun scheduleNext(): ZonedDateTime {
        val now = clock.instant()
        val next = nextMorningSync(now, zone(), at)

        val request = OneTimeWorkRequestBuilder<MorningSyncWorker>()
            .setInitialDelay(Duration.between(now, next))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()

        workManager.enqueueUniqueWork(workNameFor(next), ExistingWorkPolicy.KEEP, request)

        return next
    }

    /** Cancels every queued morning sync, whatever day it waits for. */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    companion object {
        const val TAG = "digest_morning_sync"

        private const val NAME_PREFIX = "digest_morning_sync_"

        /**
         * The unique name of the sync that waits for [time]: the moment itself, with its offset ("2026-10-02T06:20+03:00").
         * The same moment is the same work, and a phone that changed time zone since it queued one asks for a different
         * moment, so the sync that waits for the old one is not in the way of the next.
         */
        fun workNameFor(time: ZonedDateTime) = NAME_PREFIX + time.toOffsetDateTime()
    }
}
