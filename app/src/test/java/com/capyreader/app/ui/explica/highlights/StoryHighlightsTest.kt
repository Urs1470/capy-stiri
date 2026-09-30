package com.capyreader.app.ui.explica.highlights

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.capyreader.app.ui.articles.detail.ArticleTopBar
import com.capyreader.app.ui.articles.reader.ArticleReaderContent
import com.capyreader.app.ui.articles.reader.LocalReaderStyle
import com.capyreader.app.ui.articles.reader.ReaderActions
import com.capyreader.app.ui.articles.reader.ReaderStyle
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightsApi
import com.capyreader.app.ui.explica.HighlightsViewModel
import com.capyreader.app.ui.explica.highlights.ComposeScreen.Companion.color
import com.jocmp.capy.Account
import com.jocmp.capy.Article
import com.jocmp.capy.accounts.Source
import com.jocmp.mallet.Mallet
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.ZonedDateTime

/**
 * The highlights of a story in the reader itself: the real `ArticleReaderContent` and `ArticleTopBar` of the app,
 * with Koin giving them a Miniflux account (`Account` is a mock) and a fake server. On Robolectric, see [ComposeScreen].
 */
@RunWith(RobolectricTestRunner::class)
class StoryHighlightsTest {
    private lateinit var screen: ComposeScreen
    private lateinit var api: RecordingHighlightsApi

    private val digestFeed = "https://news.iupif.org/sectiuni/republica-moldova.xml"
    private val body = "<p>The council met on Tuesday in Brussels.</p><p>Moldova joined the talks.</p>"
    private val council = listOf("The council met on Tuesday in Brussels.", "Moldova joined the talks.")

    private val saved = listOf(
        Highlight(id = "s1", text = "met on Tuesday", color = "green", where = "story"),
        Highlight(id = "s2", text = "Moldova joined", color = "pink", where = "explanation"),
    )

    private fun story(feedURL: String?) = Article(
        id = "42",
        feedID = "feed-1",
        title = "Talks in Brussels",
        author = null,
        contentHTML = body,
        url = null,
        summary = "",
        imageURL = null,
        updatedAt = ZonedDateTime.now(),
        publishedAt = ZonedDateTime.now(),
        read = false,
        starred = false,
        feedName = "Republica Moldova",
        feedURL = feedURL,
    )

    /** The reader of [article], with its top bar above it when [topBar] says how it is shown (`canExplain`). */
    private fun show(article: Article, source: Source = Source.MINIFLUX_TOKEN, topBar: Boolean? = null) {
        api = RecordingHighlightsApi(saved)
        val flattened = Mallet.flatten(article.content, "https://news.example/").getOrThrow()

        loadKoinModules(
            module {
                single<Account> { mockk(relaxed = true) { every { this@mockk.source } returns source } }
                single<HighlightsApi> { api }
                viewModel { parameters -> HighlightsViewModel(api = get(), entryId = parameters.get()) }
            }
        )

        screen = ComposeScreen.show {
            MaterialTheme {
                CompositionLocalProvider(LocalReaderStyle provides ReaderStyle.default) {
                    Column {
                        if (topBar != null) {
                            ArticleTopBar(
                                show = true,
                                isScrolled = false,
                                articleId = article.id,
                                canExplain = topBar,
                                onClose = {},
                            )
                        }

                        ArticleReaderContent(
                            article = article,
                            flattened = flattened,
                            actions = ReaderActions.none,
                            onOpenExternalLink = {},
                            currentAudioUrl = null,
                            isAudioPlaying = false,
                            isAudioBuffering = false,
                            onSelectAudio = {},
                            onPauseAudio = {},
                        )
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (::screen.isInitialized) screen.destroy()

        shadowOf(Looper.getMainLooper()).idle()
        stopKoin()
    }

    @Test
    fun aStoryOfTheDigest_paintsItsHighlights_onlyTheOnesOfTheStoryText() {
        show(story(digestFeed))

        screen.assertSoon("not painted") { screen.backgrounds(council[0]).isNotEmpty() }

        assertEquals(listOf(Triple(12, 26, color(HighlightColor.GREEN))), screen.backgrounds(council[0]))
        // The other highlight belongs to the AI explanation of the story.
        assertEquals(emptyList<Triple<Int, Int, Color>>(), screen.backgrounds(council[1]))
        // The header of the story is drawn as it always was.
        assertTrue(screen.drawn().any { it.text == "Talks in Brussels" })
        // The story is read from the server for its own id (the screen asks again each time it comes back).
        assertTrue(api.calls.isNotEmpty() && api.calls.all { it == "list:42" })
    }

    @Test
    fun aStoryOfAnotherFeed_isLeftAlone_andNothingIsAskedOfTheServer() {
        show(story("https://example.org/feed.xml"))

        screen.stepUntil(limit = 300) { false }

        assertEquals(emptyList<Triple<Int, Int, Color>>(), screen.backgrounds(council[0]))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun aStoryOfAMinifluxAccountWithoutAnApiToken_isLeftAlone() {
        show(story(digestFeed), source = Source.MINIFLUX)

        screen.stepUntil(limit = 300) { false }

        assertEquals(emptyList<Triple<Int, Int, Color>>(), screen.backgrounds(council[0]))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun theTopBarOfAStoryThatCanBeExplained_hasTheHighlightsIcon_whichOpensTheListOfTheStory() {
        show(story(digestFeed), topBar = true)
        screen.assertSoon("not painted") { screen.backgrounds(council[0]).isNotEmpty() }

        screen.click { screen.hasDescription(it, "Highlights") }

        screen.assertSoon("no sheet") { screen.hasSheet() }
        screen.assertSoon("no rows") { "met on Tuesday" in screen.sheetTexts() }
        // Both highlights of the story, whatever their place: the list is of the story, not of the text.
        assertEquals(
            listOf("Highlights", "Story", "met on Tuesday", "Explanation", "Moldova joined"),
            screen.sheetTexts(),
        )
    }

    @Test
    fun theTopBarAndTheText_shareTheHighlightsOfTheStory_soARemovalInTheListClearsTheText() {
        show(story(digestFeed), topBar = true)
        screen.assertSoon("not painted") { screen.backgrounds(council[0]).isNotEmpty() }

        screen.click { screen.hasDescription(it, "Highlights") }
        screen.assertSoon("no sheet") { screen.hasSheet() }
        screen.assertSoon("no rows") { "met on Tuesday" in screen.sheetTexts() }

        val all = screen.nodes(merged = true, inDialog = true)
        val row = all.indexOfFirst { screen.hasText(it, "met on Tuesday") }
        val remove = all.drop(row).first { screen.hasDescription(it, "Remove") }
        screen.sheetClick { it.id == remove.id }

        // The text was painted by the view model of the reader, the list removed it from the one of the top bar.
        screen.assertSoon("still painted") { screen.backgrounds(council[0]).isEmpty() }
        screen.assertSoon("not removed") { api.server.none { it.id == "s1" } }
        assertEquals(1, api.calls.count { it == "remove:s1" })
    }

    @Test
    fun theTopBarOfAStoryThatCannotBeExplained_hasNoHighlightsIcon() {
        show(story("https://example.org/feed.xml"), topBar = false)

        assertFalse(screen.nodes(merged = true).any { screen.hasDescription(it, "Highlights") })
    }
}
