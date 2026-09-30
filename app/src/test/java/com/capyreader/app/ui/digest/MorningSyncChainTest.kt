package com.capyreader.app.ui.digest

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.capyreader.app.refresher.FeedRefresher
import com.capyreader.app.refresher.RefreshInterval
import com.jocmp.capy.Account
import com.jocmp.capy.Feed
import com.jocmp.capy.accounts.Source
import com.jocmp.capy.preferences.Preference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
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
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The chain of morning syncs through WorkManager itself (its test implementation): a run that WorkManager starts queues
 * the next day's work while it is running, and finishes; the day before it does not cancel itself, which is what
 * queueing again under its own name would do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MorningSyncChainTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val workManager get() = WorkManager.getInstance(context)
    private val chisinau = ZoneId.of("Europe/Chisinau")
    private val prefix = "https://news.iupif.org/sectiuni/"

    /** A clock the test moves by hand. */
    private class MovableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun set(to: Instant) {
            now = to
        }
    }

    private val clock = MovableClock(Instant.parse("2026-10-01T16:00:00Z")) // 19:00 in Chisinau
    private var lastRefreshedAt = 100L
    private var refreshes = 0
    private var remembered = false
    private var interval = RefreshInterval.EVERY_TWO_HOURS

    private val digestFeeds = listOf(
        Feed(id = "10", subscriptionID = "10", title = "00 Azi", feedURL = prefix + "00-azi.xml"),
        Feed(id = "11", subscriptionID = "11", title = "România", feedURL = prefix + "romania.xml"),
    )

    private val account = mockk<Account> {
        every { source } returns Source.MINIFLUX_TOKEN
        every { allFeeds } returns flowOf(digestFeeds)
        every { preferences.lastRefreshedAt.get() } answers { lastRefreshedAt }
        coEvery { toggleNotifications(any(), any()) } returns Unit
    }

    private val refresher = mockk<FeedRefresher> {
        coEvery { refresh() } coAnswers {
            refreshes++
            lastRefreshedAt += 10
        }
    }

    private val preference = mockk<Preference<Boolean>> {
        every { get() } answers { remembered }
        every { set(any()) } answers {
            remembered = firstArg()
            Unit
        }
    }

    private lateinit var scheduler: MorningSyncScheduler
    private lateinit var sync: MorningSync

    @Before
    fun setUp() {
        // WorkManager starts the work through this factory, as the app's does through Koin.
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                return if (workerClassName == MorningSyncWorker::class.java.name) {
                    MorningSyncWorker(appContext, workerParameters, sync)
                } else {
                    null
                }
            }
        }

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(factory).build(),
        )

        scheduler = MorningSyncScheduler(workManager, clock, zone = { chisinau })
        sync = MorningSync(
            account = account,
            refresher = refresher,
            scheduler = scheduler,
            refreshInterval = { interval },
            dayNotificationEnabled = preference,
            feedPrefix = prefix,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun all() = workManager.getWorkInfosByTag(MorningSyncScheduler.TAG).get().sortedBy { it.initialDelayMillis }

    private fun stateOf(info: WorkInfo) = workManager.getWorkInfoById(info.id).get()!!.state

    /** WorkManager runs a CoroutineWorker on a thread of its own: wait for what it does rather than for the call. */
    private fun waitUntil(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000

        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(25)

        assertTrue(what, done())
    }

    private fun waitFor(info: WorkInfo, state: WorkInfo.State) = waitUntil("$state") { stateOf(info) == state }

    /** The time comes: the day's delay has passed and the network is there. */
    private fun startWhenDue(info: WorkInfo) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

        driver.setInitialDelayMet(info.id)
        driver.setAllConstraintsMet(info.id)
    }

    @Test
    fun aRunStartedByWorkManager_queuesTomorrow_andItselfFinishes() {
        // The open of the app at 19:00 queues the sync of the next morning.
        scheduler.scheduleNext()
        val first = all().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(Duration.ofHours(11).plusMinutes(20).toMillis(), first.initialDelayMillis)

        // 06:20:30 the next morning: the work runs, a little late.
        clock.set(Instant.parse("2026-10-02T03:20:30Z"))
        startWhenDue(first)
        waitFor(first, WorkInfo.State.SUCCEEDED)

        // It refreshed, turned the day feed's notification on once, and left tomorrow's work queued.
        assertEquals(1, refreshes)
        assertTrue(remembered)
        val queued = all().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, queued.size)
        assertEquals(
            Duration.ofHours(24).toMillis() - Duration.ofSeconds(30).toMillis(),
            queued.single().initialDelayMillis,
        )
    }

    @Test
    fun theChainGoesOnFromDayToDay() {
        scheduler.scheduleNext()

        var expectedRuns = 0

        repeat(3) { day ->
            val due = all().single { it.state == WorkInfo.State.ENQUEUED }
            clock.set(Instant.parse("2026-10-02T03:20:05Z").plus(Duration.ofDays(day.toLong())))

            startWhenDue(due)
            waitFor(due, WorkInfo.State.SUCCEEDED)

            expectedRuns++
            assertEquals(expectedRuns, refreshes)
        }

        assertEquals(1, all().count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(3, all().count { it.state == WorkInfo.State.SUCCEEDED })
    }

    @Test
    fun aRunThatFindsNoDigestAccount_endsTheChain() {
        scheduler.scheduleNext()
        val first = all().single()
        interval = RefreshInterval.MANUALLY_ONLY

        clock.set(Instant.parse("2026-10-02T03:20:30Z"))
        startWhenDue(first)
        waitFor(first, WorkInfo.State.SUCCEEDED)

        assertEquals(0, refreshes)
        assertEquals(0, all().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun aRefreshThatFails_isTriedAgain_withTomorrowQueuedOnce() {
        coEvery { refresher.refresh() } coAnswers { refreshes++ } // the clock of the last refresh does not move
        scheduler.scheduleNext()
        val first = all().single()

        clock.set(Instant.parse("2026-10-02T03:20:30Z"))
        startWhenDue(first)
        // WorkManager puts it back in the queue with its back-off, instead of finishing it.
        waitUntil("tried once and queued again") {
            val info = workManager.getWorkInfoById(first.id).get()!!

            refreshes == 1 && info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 1
        }

        assertEquals(1, refreshes)
        // The retry is the same work, and tomorrow's is queued once, not once per attempt.
        assertEquals(2, all().size)
    }
}
