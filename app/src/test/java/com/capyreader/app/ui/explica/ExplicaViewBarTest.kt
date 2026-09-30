package com.capyreader.app.ui.explica

import androidx.compose.material3.MaterialTheme
import com.capyreader.app.ui.articles.reader.ReaderStyle
import com.capyreader.app.ui.explica.highlights.ComposeScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bar under the AI screen (the caption about the daily cap, the chips, the placeholder) and the hint above it, on
 * the real `ExplicaView` on Robolectric (see [ComposeScreen]): what the screen is given to draw, not how it looks.
 * A plain Application: the app's own one starts Koin and would have to be stopped after each test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ExplicaViewBarTest {
    private lateinit var screen: ComposeScreen
    private val asked = mutableListOf<String>()
    private var explained = 0

    private fun show(state: ExplicaState) {
        screen = ComposeScreen.show {
            MaterialTheme {
                ExplicaView(
                    state = state,
                    readerStyle = ReaderStyle.default,
                    onNavigateBack = {},
                    onDraftChange = {},
                    onSend = {},
                    onAsk = { asked += it },
                    onExplain = { explained++ },
                    onRetry = {},
                    onOpenLink = {},
                )
            }
        }
    }

    @After
    fun tearDown() {
        if (::screen.isInitialized) screen.destroy()
    }

    private fun texts() = screen.drawn().map { it.text }

    private fun idle(day: Boolean = false, quota: Quota? = null, turns: List<ExplicaTurn> = emptyList()) = ExplicaState(
        phase = ExplicaPhase.IDLE,
        title = "Talks in Brussels",
        day = day,
        quota = quota,
        turns = turns,
    )

    private val storyChips = listOf("Summary", "Terms", "Why it matters", "Background")
    private val dayChips = listOf("Top stories", "For Moldova", "Economy & markets", "What to watch")
    private val storyHint = "Ask anything about this story, or tap Explain for a written explanation. " +
        "Nothing is generated until you do."
    private val dayHint = "Ask about today's stories, or tap a suggestion. The answer is written from all of today's stories."

    // the caption about the daily cap

    @Test
    fun theCaption_saysHowManyRequestsAreLeft_whenFewAre() {
        show(idle(quota = Quota(used = 18, cap = 60)))

        screen.assertSoon("no caption") { "42 requests left today" in texts() }
    }

    @Test
    fun theCaption_saysRequestInTheSingular() {
        show(idle(quota = Quota(used = 59, cap = 60)))

        screen.assertSoon("no caption") { "1 request left today" in texts() }
    }

    @Test
    fun theCaption_countsDownToZero() {
        show(idle(quota = Quota(used = 60, cap = 60)))

        screen.assertSoon("no caption") { "0 requests left today" in texts() }
    }

    @Test
    fun theCaption_isAbsent_whenMoreThanSixtyAreLeft() {
        show(idle(quota = Quota(used = 0, cap = 200)))

        screen.assertSoon("not drawn") { "Talks in Brussels" in texts() }
        assertTrue(texts().none { it.contains("left today") })
    }

    @Test
    fun theCaption_isAbsent_whenTheServerHasNotReportedAQuota() {
        show(idle(quota = null))

        screen.assertSoon("not drawn") { "Talks in Brussels" in texts() }
        assertTrue(texts().none { it.contains("left today") })
    }

    @Test
    fun theCaption_isShownOnTheDayEntryToo() {
        show(idle(day = true, quota = Quota(used = 30, cap = 60)))

        screen.assertSoon("no caption") { "30 requests left today" in texts() }
    }

    // a story

    @Test
    fun aStory_hasTheExplainChip_itsFourChipsAndItsHint() {
        show(idle())

        screen.assertSoon("no chips") { storyChips.all { it in texts() } }
        val texts = texts()
        assertTrue("Explain" in texts)
        assertTrue(dayChips.none { it in texts })
        assertTrue(storyHint in texts)
        assertTrue("Ask about this story" in texts)
        assertFalse(dayHint in texts)
    }

    @Test
    fun tappingTheExplainChipOfAStory_startsTheExplanation() {
        show(idle())
        screen.assertSoon("no chips") { "Explain" in texts() }

        screen.click { screen.hasText(it, "Explain") }

        assertEquals(1, explained)
        assertTrue(asked.isEmpty())
    }

    // the entry of the day

    @Test
    fun theDayEntry_hasNoExplainChip_itsFourDayChipsAndItsOwnHint() {
        show(idle(day = true))

        screen.assertSoon("no chips") { dayChips.all { it in texts() } }
        val texts = texts()
        assertFalse("Explain" in texts)
        assertTrue(storyChips.none { it in texts })
        assertTrue(dayHint in texts)
        assertTrue("Ask about today's stories" in texts)
        assertFalse(storyHint in texts)
        assertFalse("Ask about this story" in texts)
    }

    @Test
    fun tappingADayChip_asksItsQuestion() {
        show(idle(day = true))
        screen.assertSoon("no chips") { "For Moldova" in texts() }

        screen.click { screen.hasText(it, "For Moldova") }

        assertEquals(listOf("Which of today's stories matter most for Moldova, and why?"), asked)
        assertEquals(0, explained)
    }

    @Test
    fun aDayChipAlreadyAsked_isNotOnTheBarAnyMore() {
        val turns = listOf(
            ExplicaTurn(q = DayQuestion.TOP_STORIES.question, status = STATUS_DONE, htmlApp = "<p>1. Bugetul</p>")
        )

        show(idle(day = true, turns = turns))

        screen.assertSoon("no chips") { "For Moldova" in texts() }
        val texts = texts()
        assertFalse("Top stories" in texts)
        assertTrue(listOf("For Moldova", "Economy & markets", "What to watch").all { it in texts })
        // With a chat there is no hint any more, only the chat.
        assertFalse(dayHint in texts)
    }
}
