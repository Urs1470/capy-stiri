package com.capyreader.app.ui.digest

import com.capyreader.app.refresher.FeedRefresher
import com.capyreader.app.refresher.RefreshInterval
import com.jocmp.capy.Account
import com.jocmp.capy.Feed
import com.jocmp.capy.accounts.Source
import com.jocmp.capy.preferences.Preference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZonedDateTime

/**
 * The morning sync itself, without WorkManager: who it is for, what a run does and in which order, and the one time the
 * notification of the day feed is turned on. The account, the refresh and the queue are mocks that write down what they
 * were asked, in order.
 */
class MorningSyncTest {
    private val prefix = "https://news.iupif.org/sectiuni/"

    private fun feed(id: String, url: String, title: String = id, notifications: Boolean = false) = Feed(
        id = id,
        subscriptionID = id,
        title = title,
        feedURL = url,
        enableNotifications = notifications,
    )

    private val day = feed("10", prefix + "00-azi.xml", "00 Azi")
    private val moldova = feed("11", prefix + "republica-moldova.xml", "Republica Moldova")
    private val other = feed("12", "https://hotnews.ro/rss", "HotNews")

    // What happened, in order: "schedule", "toggle:<feed>:<on>", "refresh".
    private val events = mutableListOf<String>()
    private val storedFeeds = MutableStateFlow(listOf(day, moldova, other))
    private var source = Source.MINIFLUX_TOKEN
    private var lastRefreshedAt = 100L
    private var refreshMovesTheClock = true
    private var interval = RefreshInterval.EVERY_TWO_HOURS
    private var remembered = false
    private var onRefresh: () -> Unit = {}

    private val account = mockk<Account> {
        every { this@mockk.source } answers { this@MorningSyncTest.source }
        every { allFeeds } returns storedFeeds
        every { preferences.lastRefreshedAt.get() } answers { lastRefreshedAt }
        coEvery { toggleNotifications(any(), any()) } coAnswers {
            events += "toggle:${firstArg<String>()}:${secondArg<Boolean>()}"
        }
        coEvery { toggleAllFeedNotifications(any()) } coAnswers { events += "toggleAll" }
    }

    private val refresher = mockk<FeedRefresher> {
        coEvery { refresh() } coAnswers {
            events += "refresh"
            onRefresh()
            if (refreshMovesTheClock) lastRefreshedAt += 10
        }
    }

    private val scheduler = mockk<MorningSyncScheduler> {
        every { scheduleNext() } answers {
            events += "schedule"
            ZonedDateTime.now()
        }
        every { cancelAll() } answers { events += "cancel" }
    }

    private val preference = mockk<Preference<Boolean>> {
        every { get() } answers { remembered }
        every { set(any()) } answers {
            remembered = firstArg()
            Unit
        }
    }

    private fun sync(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) = MorningSync(
        account = account,
        refresher = refresher,
        scheduler = scheduler,
        refreshInterval = { interval },
        dayNotificationEnabled = preference,
        feedPrefix = prefix,
        scope = scope,
    )

    // who it is for

    @Test
    fun aMinifluxAccountWithTheDigestsFeeds_hasTheDigest() {
        assertTrue(hasDigest(Source.MINIFLUX_TOKEN, listOf(day, moldova, other), prefix))
        assertTrue(hasDigest(Source.MINIFLUX, listOf(moldova), prefix))
        assertTrue(hasDigest(Source.MINIFLUX_TOKEN, listOf(other, day), prefix))
    }

    @Test
    fun noOtherAccount_hasTheDigest() {
        val digestFeeds = listOf(day, moldova, other)

        listOf(Source.LOCAL, Source.FEEDBIN, Source.FRESHRSS, Source.READER).forEach {
            assertFalse(it.name, hasDigest(it, digestFeeds, prefix))
        }
    }

    @Test
    fun aMinifluxAccountWithoutTheDigestsFeeds_doesNotHaveIt() {
        assertFalse(hasDigest(Source.MINIFLUX_TOKEN, emptyList(), prefix))
        assertFalse(hasDigest(Source.MINIFLUX_TOKEN, listOf(other), prefix))
        assertFalse(hasDigest(Source.MINIFLUX_TOKEN, listOf(feed("1", "https://news.iupif.org/other/x.xml")), prefix))
    }

    @Test
    fun theDayFeed_isTheOneWhoseAddressIsTheDigestsFeedOfTheDay() {
        assertEquals(day, dayFeedOf(listOf(moldova, day, other), prefix))
        assertNull(dayFeedOf(listOf(moldova, other), prefix))
        assertNull(dayFeedOf(emptyList(), prefix))
        // The name alone is not enough: a feed somebody else calls "00 Azi" is not the digest's.
        assertNull(dayFeedOf(listOf(feed("5", "https://example.com/feed.xml", "00 Azi")), prefix))
        assertEquals("00-azi.xml", DAY_FEED_FILE)
    }

    // run

    @Test
    fun aRun_queuesTomorrowFirst_thenTurnsTheDayNotificationOn_thenRefreshes() = runTest {
        val outcome = sync().run()

        assertEquals(MorningSync.Outcome.REFRESHED, outcome)
        assertEquals(listOf("schedule", "toggle:10:true", "refresh"), events)
        assertTrue(remembered)
    }

    @Test
    fun aRun_turnsOnTheNotificationOfTheDayFeedAndNoOtherFeed() = runTest {
        sync().run()

        assertEquals(listOf("schedule", "toggle:10:true", "refresh"), events)
        coVerify(exactly = 0) { account.toggleAllFeedNotifications(any()) }
        coVerify(exactly = 1) { account.toggleNotifications(any(), any()) }
    }

    @Test
    fun theNotificationIsTurnedOnOnce_andTheReadersLaterChoiceStays() = runTest {
        val sync = sync()

        sync.run()
        assertEquals(1, events.count { it.startsWith("toggle") })

        // The reader turns it off in Settings, Notifications; the flag is off in the database again.
        storedFeeds.value = listOf(day.copy(enableNotifications = false), moldova, other)
        repeat(3) { sync.run() }

        assertEquals(1, events.count { it.startsWith("toggle") })
        assertEquals(4, events.count { it == "refresh" })
    }

    @Test
    fun aFeedThatIsAlreadyOn_isNotToggled_butTheOnceIsUsedUp() = runTest {
        storedFeeds.value = listOf(day.copy(enableNotifications = true), moldova, other)

        sync().run()

        assertEquals(listOf("schedule", "refresh"), events)
        assertTrue(remembered)
    }

    @Test
    fun whenTheOnceWasUsedUpBefore_nothingIsTurnedOn() = runTest {
        remembered = true

        sync().run()

        assertEquals(listOf("schedule", "refresh"), events)
    }

    @Test
    fun aDayFeedThatOnlyTheRefreshBringsIn_isTurnedOnAfterIt_forTheNextDays() = runTest {
        storedFeeds.value = listOf(moldova, other)
        onRefresh = { storedFeeds.value = listOf(day, moldova, other) }

        val outcome = sync().run()

        assertEquals(MorningSync.Outcome.REFRESHED, outcome)
        // After the refresh, which already made its notifications: what this refresh brought in is not announced.
        assertEquals(listOf("schedule", "refresh", "toggle:10:true"), events)
        assertTrue(remembered)
        coVerify(exactly = 1) { refresher.refresh() }
    }

    @Test
    fun withoutADayFeed_nothingIsTurnedOn_andTheNextRunLooksAgain() = runTest {
        storedFeeds.value = listOf(moldova, other)
        val sync = sync()

        sync.run()
        assertEquals(listOf("schedule", "refresh"), events)
        assertFalse(remembered)

        storedFeeds.value = listOf(day, moldova, other)
        sync.run()
        assertEquals(1, events.count { it.startsWith("toggle") })
        assertTrue(remembered)
    }

    @Test
    fun aRunWithNoDigestAccount_doesNothingAndQueuesNothing() = runTest {
        source = Source.LOCAL
        assertEquals(MorningSync.Outcome.SKIPPED, sync().run())

        source = Source.MINIFLUX_TOKEN
        storedFeeds.value = listOf(other)
        assertEquals(MorningSync.Outcome.SKIPPED, sync().run())

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun whenTheReaderRefreshesManuallyOnly_aRunDoesNothing() = runTest {
        interval = RefreshInterval.MANUALLY_ONLY

        assertEquals(MorningSync.Outcome.SKIPPED, sync().run())

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun everyIntervalThatRefreshesByItself_isDue() = runTest {
        RefreshInterval.entries.filter { it.isPeriodic }.forEach {
            interval = it
            events.clear()
            remembered = true

            assertEquals(it.name, MorningSync.Outcome.REFRESHED, sync().run())
            assertEquals(it.name, listOf("schedule", "refresh"), events)
        }
    }

    @Test
    fun bothMinifluxSignIns_areDue() = runTest {
        remembered = true

        listOf(Source.MINIFLUX, Source.MINIFLUX_TOKEN).forEach {
            source = it
            events.clear()

            assertEquals(it.name, MorningSync.Outcome.REFRESHED, sync().run())
        }
    }

    @Test
    fun aRefreshThatDidNotGoThrough_isReported_afterTomorrowWasQueued() = runTest {
        refreshMovesTheClock = false

        val outcome = sync().run()

        assertEquals(MorningSync.Outcome.NOT_REFRESHED, outcome)
        assertEquals("schedule", events.first())
        assertEquals(1, events.count { it == "schedule" })
    }

    @Test
    fun aRefreshThatThrows_leavesTomorrowQueued() = runTest {
        coEvery { refresher.refresh() } coAnswers {
            events += "refresh"
            throw IllegalStateException("database is locked")
        }

        try {
            sync().run()
            fail("the exception should reach the worker")
        } catch (e: IllegalStateException) {
            assertEquals("database is locked", e.message)
        }

        assertEquals(listOf("schedule", "toggle:10:true", "refresh"), events)
    }

    // queueing

    @Test
    fun atEveryOpen_aDigestAccountQueuesTheNextSync() = runTest {
        sync().scheduleOrCancel()

        assertEquals(listOf("schedule"), events)
    }

    @Test
    fun atEveryOpen_anyoneElseHasTheQueueCancelled() = runTest {
        source = Source.FEEDBIN
        sync().scheduleOrCancel()

        source = Source.MINIFLUX_TOKEN
        storedFeeds.value = listOf(other)
        sync().scheduleOrCancel()

        interval = RefreshInterval.MANUALLY_ONLY
        storedFeeds.value = listOf(day, moldova)
        sync().scheduleOrCancel()

        assertEquals(listOf("cancel", "cancel", "cancel"), events)
    }

    @Test
    fun keepScheduled_doesTheSameOffTheCallingThread() {
        sync().keepScheduled()

        assertEquals(listOf("schedule"), events)

        events.clear()
        source = Source.LOCAL
        sync().keepScheduled()

        assertEquals(listOf("cancel"), events)
    }

    @Test
    fun keepScheduled_survivesADatabaseThatFails() {
        every { account.allFeeds } returns flow { throw IllegalStateException("database is locked") }

        sync().keepScheduled()

        verify(exactly = 0) { scheduler.scheduleNext() }
        verify(exactly = 0) { scheduler.cancelAll() }
    }
}
