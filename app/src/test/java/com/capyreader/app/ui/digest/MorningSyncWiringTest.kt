package com.capyreader.app.ui.digest

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.capyreader.app.preferences.AppPreferences
import com.capyreader.app.refresher.FeedRefresher
import com.capyreader.app.refresher.RefreshInterval
import com.capyreader.app.ui.explica.explicaModule
import com.jocmp.capy.Account
import com.jocmp.capy.Feed
import com.jocmp.capy.accounts.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How the morning sync is put together and started: the module gives the pieces (the account's own, the real queue on
 * WorkManager's test implementation), the work is made by the Koin factory WorkManager uses, and the open of the app
 * (`MainActivity.onStart`, through [keepMorningSync]) queues the chain or cancels it. Nothing touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MorningSyncWiringTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val workManager get() = WorkManager.getInstance(context)

    private val prefix = "https://news.iupif.org/sectiuni/"
    private val digestFeeds = listOf(
        Feed(id = "10", subscriptionID = "10", title = "Conceptul zilei", feedURL = prefix + "conceptul-zilei.xml"),
        Feed(id = "11", subscriptionID = "11", title = "România", feedURL = prefix + "romania.xml"),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun account(source: Source = Source.MINIFLUX_TOKEN, feeds: List<Feed> = digestFeeds): Account {
        return mockk {
            every { id } returns "account-1"
            every { this@mockk.source } returns source
            every { allFeeds } returns flowOf(feeds)
            every { preferences.lastRefreshedAt.get() } returns 0L
        }
    }

    private fun appPreferences(interval: RefreshInterval = RefreshInterval.EVERY_TWO_HOURS): AppPreferences {
        return mockk { every { refreshInterval.get() } returns interval }
    }

    private fun koinWith(account: Account, preferences: AppPreferences = appPreferences()) = koinApplication {
        androidContext(context)
        modules(
            explicaModule,
            module {
                single { account }
                single { preferences }
                single { mockk<FeedRefresher>(relaxed = true) }
            },
        )
    }.koin

    private fun queued() = workManager.getWorkInfosByTag(MorningSyncScheduler.TAG).get()

    @Test
    fun theModule_givesOneQueueAndOneMorningSyncPerAccount() {
        val koin = koinWith(account())

        try {
            assertSame(koin.get<MorningSyncScheduler>(), koin.get<MorningSyncScheduler>())
            assertSame(koin.get<MorningSync>(), koin.get<MorningSync>())
        } finally {
            koin.close()
        }
    }

    @Test
    fun theWork_isMadeByTheKoinFactoryWorkManagerUses() {
        val koin = koinWith(account())
        // A real WorkerParameters: the test builder hands one to the factory it is given.
        var parameters: WorkerParameters? = null
        TestListenableWorkerBuilder<MorningSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker {
                    parameters = workerParameters

                    return MorningSyncWorker(appContext, workerParameters, mockk(relaxed = true))
                }
            })
            .build()

        try {
            val worker = koin.getOrNull<ListenableWorker>(named(MorningSyncWorker::class.java.name)) {
                parametersOf(parameters!!)
            }

            assertNotNull("no worker under the class name, which is how KoinWorkerFactory looks it up", worker)
            assertTrue(worker is MorningSyncWorker)
        } finally {
            koin.close()
        }
    }

    @Test
    fun aDigestAccount_isQueuedByTheModulesOwnPieces() = runBlocking {
        val koin = koinWith(account())

        try {
            koin.get<MorningSync>().scheduleOrCancel()

            val info = queued().single()
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
            assertEquals(MorningSyncWorker::class.java.name, info.workerClassName)
        } finally {
            koin.close()
        }
    }

    @Test
    fun anAccountThatIsNotADigestOne_hasTheQueueCancelled() = runBlocking {
        val digest = koinWith(account())
        digest.get<MorningSync>().scheduleOrCancel()
        assertEquals(1, queued().size)
        digest.close()

        val local = koinWith(account(source = Source.LOCAL))
        try {
            local.get<MorningSync>().scheduleOrCancel()

            assertTrue(queued().all { it.state == WorkInfo.State.CANCELLED })
        } finally {
            local.close()
        }
    }

    @Test
    fun theFirstRun_turnsTheDayNotificationOn_andRemembersItForThatAccountInAFileOfItsOwn() = runBlocking {
        val account = account()
        coEvery { account.toggleNotifications(any(), any()) } returns Unit
        val koin = koinWith(account)

        try {
            val sync = koin.get<MorningSync>()
            val marks = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
            assertFalse(marks.contains("day_notification_enabled_account-1"))

            sync.run()

            coVerify(exactly = 1) { account.toggleNotifications("10", true) }
            assertTrue(marks.getBoolean("day_notification_enabled_account-1", false))

            sync.run()

            coVerify(exactly = 1) { account.toggleNotifications(any(), any()) }
            // The next day's sync is queued by each run, and by the two of them only once.
            assertEquals(1, queued().size)
        } finally {
            koin.close()
        }
    }

    // the open of the app

    @Test
    fun atTheOpen_anAccountKeepsTheMorningSyncQueued() {
        val sync = mockk<MorningSync>(relaxed = true)
        startKoin { modules(module { single { sync } }) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.keepMorningSync(hasAccount = true)

        verify(exactly = 1) { sync.keepScheduled() }
    }

    @Test
    fun atTheOpen_noAccountCancelsWhatWasLeftQueued() {
        val sync = mockk<MorningSync>(relaxed = true)
        startKoin { modules(module { single { sync } }) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        MorningSyncScheduler(workManager).scheduleNext()
        assertEquals(WorkInfo.State.ENQUEUED, queued().single().state)

        activity.keepMorningSync(hasAccount = false)

        assertEquals(WorkInfo.State.CANCELLED, queued().single().state)
        verify(exactly = 0) { sync.keepScheduled() }
    }

    @Test
    fun atTheOpen_withTheAccountsModulesNotLoaded_nothingHappensAndNothingBreaks() {
        startKoin { modules(module { }) }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.keepMorningSync(hasAccount = true)

        assertEquals(0, queued().size)
    }

    @Test
    fun theWholeWayFromTheOpen_queuesTheSync_ofADigestAccount() {
        val account = account()
        coEvery { account.toggleNotifications(any(), any()) } returns Unit
        startKoin {
            androidContext(context)
            modules(
                explicaModule,
                module {
                    single { account }
                    single { appPreferences() }
                    single { mockk<FeedRefresher>(relaxed = true) }
                },
            )
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.keepMorningSync(hasAccount = true)

        // The queueing happens off the main thread: wait for it.
        val deadline = System.currentTimeMillis() + 5_000
        while (queued().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals(WorkInfo.State.ENQUEUED, queued().single().state)
    }
}
