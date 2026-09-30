package com.capyreader.app.ui.digest

import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Queueing the morning sync on WorkManager's test implementation: what is queued, for when, and how it is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MorningSyncSchedulerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val workManager get() = WorkManager.getInstance(context)
    private val chisinau = ZoneId.of("Europe/Chisinau")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    /** A scheduler whose clock stands at [now] and whose phone is in Chisinau (UTC+3 in October). */
    private fun schedulerAt(now: String, zone: ZoneId = chisinau) = MorningSyncScheduler(
        workManager = workManager,
        clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
        zone = { zone },
    )

    private fun named(name: String) = workManager.getWorkInfosForUniqueWork(name).get()

    private fun all() = workManager.getWorkInfosByTag(MorningSyncScheduler.TAG).get()

    @Test
    fun scheduleNext_queuesOneWorkThatWaitsForTheNextTwentyPastSix() {
        // 08:00 in Chisinau on 1 October: the next 06:20 is tomorrow's, 22 h 20 min away.
        val next = schedulerAt("2026-10-01T05:00:00Z").scheduleNext()

        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next.toInstant())
        val info = named("digest_morning_sync_2026-10-02T06:20+03:00").single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(Duration.ofHours(22).plusMinutes(20).toMillis(), info.initialDelayMillis)
        assertTrue(MorningSyncScheduler.TAG in info.tags)
        assertEquals(MorningSyncWorker::class.java.name, info.workerClassName)
        assertEquals(1, all().size)
    }

    @Test
    fun beforeTheTime_itWaitsForTodays() {
        // 05:00 in Chisinau: 1 h 20 min to go.
        schedulerAt("2026-10-01T02:00:00Z").scheduleNext()

        val info = named("digest_morning_sync_2026-10-01T06:20+03:00").single()
        assertEquals(Duration.ofHours(1).plusMinutes(20).toMillis(), info.initialDelayMillis)
        assertEquals(1, all().size)
    }

    @Test
    fun theWorkNeedsAConnection_likeThePeriodicRefresh() {
        schedulerAt("2026-10-01T05:00:00Z").scheduleNext()

        assertEquals(NetworkType.CONNECTED, all().single().constraints.requiredNetworkType)
    }

    @Test
    fun queueingAgainTheSameDay_keepsTheWorkThatWaits() {
        val scheduler = schedulerAt("2026-10-01T05:00:00Z")

        scheduler.scheduleNext()
        val first = all().single().id
        scheduler.scheduleNext()
        schedulerAt("2026-10-01T05:30:00Z").scheduleNext()

        assertEquals(first, all().single().id)
    }

    @Test
    fun aNewDay_queuesItsOwn_andTheOneThatWaitsStays() {
        schedulerAt("2026-10-01T05:00:00Z").scheduleNext()
        // The run of 2 October (06:30 local, late) queues the 3rd.
        schedulerAt("2026-10-02T03:30:00Z").scheduleNext()

        val ids = all().map { it.id }
        assertEquals(2, ids.toSet().size)
        assertEquals(1, named("digest_morning_sync_2026-10-02T06:20+03:00").size)
        assertEquals(1, named("digest_morning_sync_2026-10-03T06:20+03:00").size)
        assertTrue(all().all { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun theDayIsTheDayOnTheClockOfThePhone() {
        // 23:30 UTC on 1 October is 02:30 on the 2nd in Chisinau: the next 06:20 is the 2nd's, 3 h 50 min away.
        schedulerAt("2026-10-01T23:30:00Z").scheduleNext()

        val info = named("digest_morning_sync_2026-10-02T06:20+03:00").single()
        assertEquals(Duration.ofHours(3).plusMinutes(50).toMillis(), info.initialDelayMillis)
        assertEquals(0, named("digest_morning_sync_2026-10-01T06:20+03:00").size)
    }

    @Test
    fun aDifferentZone_changesWhenItWaitsUntil() {
        val next = schedulerAt("2026-10-01T03:00:00Z", ZoneId.of("America/New_York")).scheduleNext()

        assertEquals(Instant.parse("2026-10-01T10:20:00Z"), next.toInstant())
        assertEquals(Duration.ofHours(7).plusMinutes(20).toMillis(), all().single().initialDelayMillis)
    }

    @Test
    fun aTimeOfDayCanBeGiven() {
        val scheduler = MorningSyncScheduler(
            workManager = workManager,
            clock = Clock.fixed(Instant.parse("2026-10-01T05:00:00Z"), ZoneOffset.UTC),
            zone = { chisinau },
            at = LocalTime.of(7, 0),
        )

        val next = scheduler.scheduleNext()

        assertEquals(LocalTime.of(7, 0), next.toLocalTime())
        assertEquals(Duration.ofHours(23).toMillis(), all().single().initialDelayMillis)
    }

    @Test
    fun cancelAll_cancelsTheMorningSyncOfEveryDay_andOnlyThat() {
        schedulerAt("2026-10-01T05:00:00Z").scheduleNext()
        schedulerAt("2026-10-02T03:30:00Z").scheduleNext()
        // Other work that is queued must stay (upstream's own refresh, say): a request with a delay, so it only waits.
        val other = OneTimeWorkRequestBuilder<MorningSyncWorker>()
            .setInitialDelay(Duration.ofDays(1))
            .addTag("other")
            .build()
        workManager.enqueue(other)

        schedulerAt("2026-10-02T04:00:00Z").cancelAll()

        assertEquals(2, all().size)
        assertTrue(all().all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(other.id).get()!!.state)
    }

    @Test
    fun aCancelledDay_canBeQueuedAgain() {
        val scheduler = schedulerAt("2026-10-01T05:00:00Z")
        scheduler.scheduleNext()
        val first = all().single().id

        scheduler.cancelAll()
        assertEquals(WorkInfo.State.CANCELLED, all().single().state)
        scheduler.scheduleNext()

        // The cancelled one is replaced under its name, not kept next to the new one.
        val again = all().single()
        assertEquals(WorkInfo.State.ENQUEUED, again.state)
        assertNotEquals(first, again.id)
    }

    @Test
    fun theNameOfASync_isItsMomentWithItsOffset() {
        val time = ZonedDateTime.of(2026, 10, 2, 6, 20, 0, 0, chisinau)

        assertEquals("digest_morning_sync_2026-10-02T06:20+03:00", MorningSyncScheduler.workNameFor(time))
        assertEquals(
            "digest_morning_sync_2026-10-02T06:20-04:00",
            MorningSyncScheduler.workNameFor(ZonedDateTime.of(2026, 10, 2, 6, 20, 0, 0, ZoneId.of("America/New_York"))),
        )
    }

    @Test
    fun aPhoneThatChangedZone_queuesItsOwnMoment_andTheOldOneStaysQueuedUntilItRuns() {
        // The same moment, 12:00 UTC on the 1st: in Chisinau (UTC+3) the next 06:20 is the 2nd's, and in New York
        // (UTC-4, where it is 08:00) it is the 2nd's too, at another moment.
        schedulerAt("2026-10-01T12:00:00Z").scheduleNext()
        schedulerAt("2026-10-01T12:00:00Z", ZoneId.of("America/New_York")).scheduleNext()

        assertEquals(1, named("digest_morning_sync_2026-10-02T06:20+03:00").size)
        assertEquals(1, named("digest_morning_sync_2026-10-02T06:20-04:00").size)
        assertEquals(2, all().size)
    }
}
