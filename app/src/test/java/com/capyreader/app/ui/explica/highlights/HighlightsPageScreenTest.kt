package com.capyreader.app.ui.explica.highlights

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.capyreader.app.ui.explica.FailureKind
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightsPageState
import com.capyreader.app.ui.explica.StoryHighlights
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner

/** The Highlights page as drawn (Robolectric, see [ComposeScreen]): one card per story, its highlights in it, a tap opens it. */
@RunWith(RobolectricTestRunner::class)
class HighlightsPageScreenTest {
    private lateinit var screen: ComposeScreen
    private val opened = mutableListOf<Long>()

    private val stories = listOf(
        StoryHighlights(
            entryId = 5012, title = "Gemini 4 Argon", section = "AI", latest = 1790850000,
            highlights = listOf(
                Highlight(id = "h1", text = "1M output tokens", color = "green", where = "story"),
                Highlight(id = "h2", text = "phased release", color = "blue", where = "answer:0"),
            ),
        ),
        StoryHighlights(
            entryId = 4999, title = "", latest = 1790700000,
            highlights = listOf(Highlight(id = "h3", text = "old one", where = "explanation")),
        ),
    )

    private fun show(state: HighlightsPageState) {
        screen = ComposeScreen.show {
            MaterialTheme {
                HighlightsPage(
                    state = state,
                    onNavigateBack = {},
                    onRefresh = {},
                    onOpen = { opened += it.entryId },
                    onDismissMissing = {},
                )
            }
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        if (::screen.isInitialized) screen.destroy()
    }

    private fun texts(): List<String> = screen.nodes(merged = false).flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    @Test
    fun everyStoryIsACard_withItsTitle_sectionAndCount_andItsHighlightsWithWhereTheyAre() {
        show(HighlightsPageState(stories = stories, loading = false, loaded = true))

        val shown = texts()
        assertTrue(shown.toString(), "Gemini 4 Argon" in shown)
        assertTrue(shown.toString(), shown.any { it.startsWith("AI · ") && it.endsWith(" · 2 highlights") })
        assertTrue(shown.toString(), shown.containsAll(listOf("1M output tokens", "phased release", "old one")))
        assertTrue(shown.toString(), shown.containsAll(listOf("Story", "Answer 1", "Explanation")))
        assertTrue(shown.toString(), "Untitled story" in shown)
        assertTrue(shown.toString(), shown.any { it.endsWith("1 highlight") })
        // the story highlighted last comes first
        assertTrue(shown.indexOf("Gemini 4 Argon") < shown.indexOf("Untitled story"))
    }

    @Test
    fun aTapOnACard_opensItsStory() {
        show(HighlightsPageState(stories = stories, loading = false, loaded = true))

        screen.click { node -> screen.hasText(node, "old one") }

        assertEquals(listOf(4999L), opened)
    }

    @Test
    fun withNothingYet_thePageSaysHowToHighlight() {
        show(HighlightsPageState(stories = emptyList(), loading = false, loaded = true))

        assertTrue(texts().any { it.startsWith("No highlights yet.") })
    }

    @Test
    fun aFailedFirstRead_saysWhy_andOffersToTryAgain() {
        show(HighlightsPageState(stories = emptyList(), loading = false, failure = FailureKind.NETWORK))

        val shown = texts()
        assertTrue(shown.toString(), shown.any { it == "Couldn't load the highlights. No connection." })
        assertTrue(shown.toString(), "Try again" in shown)
    }
}
