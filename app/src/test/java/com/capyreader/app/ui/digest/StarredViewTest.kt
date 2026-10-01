package com.capyreader.app.ui.digest

import android.app.Application
import com.capyreader.app.notifications.NotificationHelper
import com.capyreader.app.preferences.AppPreferences
import com.capyreader.app.refresher.RefreshInterval
import com.capyreader.app.ui.articles.ArticleScreenViewModel
import com.capyreader.app.ui.articles.ArticleSessionCutoff
import com.jocmp.capy.Account
import com.jocmp.capy.ArticleFilter
import com.jocmp.capy.ArticleStatus
import com.jocmp.capy.Folder
import com.jocmp.capy.accounts.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Starred in the drawer (this fork; Ion, 2026-10-01): All articles with the Starred status, and the lists picked after it
 * keep the status the reader had before it. Inside a list the status bar works as upstream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StarredViewTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var account: Account
    private lateinit var appPreferences: AppPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        account = mockk(relaxed = true) {
            every { folders } returns flowOf(emptyList())
            every { feeds } returns flowOf(emptyList())
            every { taggedFeeds } returns flowOf(emptyList())
            every { savedSearches } returns flowOf(emptyList())
            every { canSaveArticleExternally } returns mockk(relaxed = true) {
                every { get() } returns false
                every { stateIn(any()) } returns MutableStateFlow(false)
                every { changes() } returns flowOf(false)
            }
            every { countAll(any()) } returns flowOf(emptyMap())
            every { countAllBySavedSearch(any()) } returns flowOf(emptyMap())
            every { source } returns Source.MINIFLUX_TOKEN
            coEvery { refresh(any()) } returns Result.success(Unit)
            coEvery { findFolder(any()) } answers { Folder(title = firstArg()) }
            every { preferences } returns mockk(relaxed = true) {
                every { lastRefreshedAt } returns mockk(relaxed = true) { every { get() } returns 0L }
            }
        }
        appPreferences = AppPreferences(RuntimeEnvironment.getApplication()).also {
            it.clearAll()
            it.refreshInterval.set(RefreshInterval.EVERY_TWO_HOURS)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stopKoin()
    }

    private fun TestScope.viewModel(start: ArticleFilter): ArticleScreenViewModel {
        appPreferences.filter.set(start)
        val vm = ArticleScreenViewModel(
            account = account,
            appPreferences = appPreferences,
            application = RuntimeEnvironment.getApplication() as Application,
            notificationHelper = mockk(relaxed = true),
            articleCutoff = ArticleSessionCutoff(),
            ioDispatcher = dispatcher,
            syncFlushInterval = null,
        )
        advanceUntilIdle()
        return vm
    }

    @Test
    fun starredShowsAllTheStarredArticles_andTodayAfterItIsUnreadAgain() = runTest(dispatcher) {
        val vm = viewModel(ArticleFilter.Today(todayStatus = ArticleStatus.UNREAD))

        vm.selectStarred()

        assertEquals(ArticleFilter.Articles(articleStatus = ArticleStatus.STARRED), vm.filter.value)
        assertTrue(vm.isStarredView)
        assertEquals(ArticleStatus.UNREAD, vm.listStatus)

        vm.selectToday()

        assertEquals(ArticleFilter.Today(todayStatus = ArticleStatus.UNREAD), vm.filter.value)
        assertFalse(vm.isStarredView)
    }

    @Test
    fun aFolderAfterStarred_isTheWholeFolder_notItsStarredOnly() = runTest(dispatcher) {
        val vm = viewModel(ArticleFilter.Articles(articleStatus = ArticleStatus.ALL))

        vm.selectStarred()
        vm.selectStarred()  // twice: the status to come back to stays the first one
        vm.selectFolder("4 - Analize geo/pol/econ")
        advanceUntilIdle()

        assertEquals(ArticleFilter.Folders(folderTitle = "4 - Analize geo/pol/econ", folderStatus = ArticleStatus.ALL), vm.filter.value)
    }

    @Test
    fun allArticlesAfterStarred_comesBackWithThePreviousStatus() = runTest(dispatcher) {
        val vm = viewModel(ArticleFilter.Articles(articleStatus = ArticleStatus.UNREAD))

        vm.selectStarred()
        vm.selectArticleFilter()

        assertEquals(ArticleFilter.Articles(articleStatus = ArticleStatus.UNREAD), vm.filter.value)
    }

    @Test
    fun insideAFolder_theStatusBarStillShowsItsStarred_asUpstream() = runTest(dispatcher) {
        val vm = viewModel(ArticleFilter.Folders(folderTitle = "AI", folderStatus = ArticleStatus.UNREAD))

        vm.selectStatus(ArticleStatus.STARRED)

        assertEquals(ArticleFilter.Folders(folderTitle = "AI", folderStatus = ArticleStatus.STARRED), vm.filter.value)
        assertFalse(vm.isStarredView)
        assertEquals(ArticleStatus.STARRED, vm.listStatus)
    }

    @Test
    fun opened_inStarred_theOtherListsComeBackWithAll() = runTest(dispatcher) {
        val vm = viewModel(ArticleFilter.Articles(articleStatus = ArticleStatus.STARRED))

        assertTrue(vm.isStarredView)
        assertEquals(ArticleStatus.ALL, vm.listStatus)

        vm.selectToday()

        assertEquals(ArticleFilter.Today(todayStatus = ArticleStatus.ALL), vm.filter.value)
    }
}
