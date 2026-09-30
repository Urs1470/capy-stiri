package com.capyreader.app.ui.digest

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the work does with the outcome of a run: done, try again soon, or give up. WorkManager's own test helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MorningSyncWorkerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sync = mockk<MorningSync>()

    private fun worker(attempt: Int = 0): MorningSyncWorker {
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

        return TestListenableWorkerBuilder<MorningSyncWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(attempt)
            .build()
    }

    private fun outcome(outcome: MorningSync.Outcome) {
        coEvery { sync.run() } returns outcome
    }

    @Test
    fun aRefreshedAccount_isDone() = runTest {
        outcome(MorningSync.Outcome.REFRESHED)

        assertEquals(Result.success(), worker().doWork())
        coVerify(exactly = 1) { sync.run() }
    }

    @Test
    fun whenThereIsNoDigestAccountAnyMore_itIsDoneToo_withoutAnotherTry() = runTest {
        outcome(MorningSync.Outcome.SKIPPED)

        assertEquals(Result.success(), worker().doWork())
        assertEquals(Result.success(), worker(attempt = 2).doWork())
    }

    @Test
    fun aRefreshThatDidNotGoThrough_isTriedAgainSoon_untilTheLastAttempt() = runTest {
        outcome(MorningSync.Outcome.NOT_REFRESHED)

        assertEquals(3, MorningSyncWorker.MAX_ATTEMPTS)
        assertEquals(Result.retry(), worker(attempt = 0).doWork())
        assertEquals(Result.retry(), worker(attempt = 1).doWork())
        assertEquals(Result.failure(), worker(attempt = 2).doWork())
    }

    @Test
    fun aRunThatThrows_isTreatedLikeARefreshThatDidNotGoThrough() = runTest {
        coEvery { sync.run() } coAnswers { throw IllegalStateException("database is locked") }

        assertEquals(Result.retry(), worker(attempt = 0).doWork())
        assertEquals(Result.failure(), worker(attempt = 2).doWork())
    }

    @Test
    fun aCancelledRun_isNotSwallowed() = runTest {
        coEvery { sync.run() } coAnswers { throw CancellationException("the work was stopped") }

        var thrown: Throwable? = null
        try {
            worker().doWork()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue(thrown is CancellationException)
    }
}
