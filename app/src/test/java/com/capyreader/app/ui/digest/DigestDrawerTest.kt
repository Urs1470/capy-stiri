package com.capyreader.app.ui.digest

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.capyreader.app.ui.articles.feeds.FeedList
import com.capyreader.app.ui.articles.feeds.AngleRefreshState
import com.capyreader.app.ui.explica.highlights.ComposeScreen
import com.capyreader.app.ui.theme.CapyTheme
import com.capyreader.app.ui.articles.feeds.edit.EditFeedViewModel
import com.capyreader.app.ui.articles.EditFolderViewModel
import com.jocmp.capy.Account
import com.jocmp.capy.ArticleFilter
import com.jocmp.capy.Feed
import com.jocmp.capy.Folder
import com.jocmp.capy.accounts.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/** The drawer in two groups (this fork): the digest's sections, each a plain row with its icon, and the reading folders. */
@RunWith(RobolectricTestRunner::class)
class DigestDrawerTest {
    private val prefix = "https://news.iupif.org/sectiuni/"
    private var screen: ComposeScreen? = null

    private fun section(title: String, slug: String, count: Long = 3) =
        Folder(title = title, feeds = listOf(Feed(id = slug, subscriptionID = slug, title = title, feedURL = "$prefix$slug.xml", folderName = title)), count = count)

    private fun reading(title: String, vararg urls: String) =
        Folder(title = title, feeds = urls.mapIndexed { i, url -> Feed(id = "$title$i", subscriptionID = "$title$i", title = url, feedURL = url, folderName = title) }, count = 7)

    private val concept = section("Conceptul zilei", "conceptul-zilei", 1)
    private val moldova = section("Republica Moldova", "republica-moldova")
    private val markets = section("Bursă", "bursa")
    private val tech = reading("2 - Tech", "https://feeds2.feedburner.com/IeeeSpectrum")
    private val history = reading("3 - Educație, istorie, știință", "https://aeon.co/feed.rss", "https://acoup.blog/feed/")

    @After
    fun tearDown() {
        stopKoin()
        screen?.destroy()
    }

    @Test
    fun theFoldersSplitIntoTheDigestsSectionsAndTheRest_keepingTheirOrder() {
        val split = splitDigestFolders(listOf(concept, moldova, markets, tech, history), prefix)

        assertEquals(listOf(concept, moldova, markets), split.digest)
        assertEquals(listOf(tech, history), split.reading)
    }

    @Test
    fun aFolderWithAFeedFromElsewhere_orWithoutFeeds_isNotTheDigests() {
        val mixed = Folder(title = "AI", feeds = moldova.feeds + tech.feeds)
        val empty = Folder(title = "Gol")

        assertEquals(listOf(mixed, empty), splitDigestFolders(listOf(mixed, empty), prefix).reading)
    }

    @Test
    fun aReadingFolderIsShownWithoutTheNumberThatOrdersIt() {
        assertEquals("Tech", readingFolderLabel("2 - Tech"))
        assertEquals("Educație, istorie, știință", readingFolderLabel("3 - Educație, istorie, știință"))
        assertEquals("Analize geo/pol/econ", readingFolderLabel("4 - Analize geo/pol/econ"))
        assertEquals("Tehnologii cheie", readingFolderLabel("5 – Tehnologii cheie"))
        assertEquals("Tehnologii cheie", readingFolderLabel("12. Tehnologii cheie"))
        assertEquals("Lectură", readingFolderLabel("Lectură"))
        assertEquals("2024 in review", readingFolderLabel("2024 in review"))
        assertEquals("3 - ", readingFolderLabel("3 - "))
    }

    @Test
    fun everySectionHasItsIcon_andAnUnknownOneTheNewspaper() {
        assertEquals(Icons.Outlined.Lightbulb, sectionIcon("Conceptul zilei"))
        assertEquals(Icons.Outlined.Lightbulb, sectionIcon("00 Azi"))
        assertEquals(Icons.AutoMirrored.Outlined.ShowChart, sectionIcon("Bursă"))
        assertEquals(Icons.Outlined.Public, sectionIcon("Geopolitică"))
        assertEquals(Icons.Outlined.Newspaper, sectionIcon("Sport"))
    }

    private fun show(folders: List<Folder>) {
        // The folder rows' menus get their view models from the account's modules; an account mock stands in for it.
        loadKoinModules(
            module {
                single<Account> { mockk(relaxed = true) }
                viewModel { EditFolderViewModel(account = get(), appPreferences = get()) }
                viewModel { EditFeedViewModel(account = get()) }
            }
        )
        screen = ComposeScreen.show {
            CapyTheme {
                FeedList(
                    source = Source.MINIFLUX_TOKEN,
                    folders = folders,
                    filter = ArticleFilter.default(),
                    statusCount = 0,
                    todayCount = 0,
                    onFilterSelect = {},
                    onSelectToday = {},
                    onSelectSavedSearch = {},
                    refreshState = AngleRefreshState.STOPPED,
                    onRefresh = {},
                    onSelectFolder = { selected += it.title },
                    onSelectFeed = { _, _ -> },
                    onMarkAllRead = {},
                    onFeedAdded = {},
                    onNavigateToSettings = {},
                )
            }
        }
    }

    private val selected = mutableListOf<String>()

    private fun texts(): List<String> = screen!!.nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    @Test
    fun theDrawerShowsTheTwoGroups_theSectionsAsRows_andTheReadingFoldersWithoutNumbers() {
        show(listOf(concept, moldova, markets, tech, history))

        val shown = texts()
        assertTrue(shown.toString(), "Daily digest" in shown && "Reading" in shown)
        assertFalse(shown.toString(), "Folders" in shown)
        assertTrue(shown.indexOf("Daily digest") < shown.indexOf("Conceptul zilei"))
        assertTrue(shown.indexOf("Bursă") < shown.indexOf("Reading"))
        assertTrue(shown.indexOf("Reading") < shown.indexOf("Tech"))
        assertTrue(shown.toString(), "Educație, istorie, știință" in shown)
        assertFalse(shown.toString(), "2 - Tech" in shown)
        // A section is shown once: there's no arrow to open a copy of it.
        assertEquals(1, shown.count { it == "Republica Moldova" })

        screen!!.click { node -> screen!!.hasText(node, "Republica Moldova") }
        screen!!.click { node -> screen!!.hasText(node, "Tech") && node.config.getOrNull(SemanticsActions.OnClick) != null }
        assertEquals(listOf("Republica Moldova", "2 - Tech"), selected)
    }

    @Test
    fun anAccountWithoutTheDigest_keepsTheUsualFoldersGroup_withTheFullNames() {
        show(listOf(tech, history))

        val shown = texts()
        assertTrue(shown.toString(), "Folders" in shown)
        assertFalse(shown.toString(), "Daily digest" in shown || "Reading" in shown)
        assertTrue(shown.toString(), "2 - Tech" in shown)
    }
}
