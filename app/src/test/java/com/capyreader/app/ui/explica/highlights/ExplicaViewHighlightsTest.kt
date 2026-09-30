package com.capyreader.app.ui.explica.highlights

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.capyreader.app.ui.articles.reader.ReaderStyle
import com.capyreader.app.ui.explica.ExplicaPhase
import com.capyreader.app.ui.explica.ExplicaState
import com.capyreader.app.ui.explica.ExplicaTurn
import com.capyreader.app.ui.explica.ExplicaView
import com.capyreader.app.ui.explica.FailureKind
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightsViewModel
import com.capyreader.app.ui.explica.highlights.ComposeScreen.Companion.color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The highlights on the AI screen of a story: the real `ExplicaView`, on Robolectric (see [ComposeScreen]). */
@RunWith(RobolectricTestRunner::class)
class ExplicaViewHighlightsTest {
    private lateinit var screen: ComposeScreen
    private lateinit var api: RecordingHighlightsApi
    private lateinit var viewModel: HighlightsViewModel

    private val explanation = "The council met on Tuesday in Brussels."
    private val answer = "Moldova joined the talks."

    private val saved = listOf(
        Highlight(id = "s1", text = "met on Tuesday", color = "pink", where = "explanation"),
        Highlight(id = "s2", text = "Moldova joined", color = "blue", where = "answer:0"),
        Highlight(id = "s3", text = "met on Tuesday", color = "green", where = "story"),
        Highlight(id = "s4", text = "Moldova joined", color = "yellow", where = "answer:1"),
    )

    private fun stateOf(phase: ExplicaPhase) = ExplicaState(
        phase = phase,
        title = "Talks in Brussels",
        explanationHtml = "<p>$explanation</p>",
        turns = listOf(ExplicaTurn(q = "Who joined?", status = "done", htmlApp = "<p>$answer</p>")),
    )

    private fun show(state: ExplicaState) {
        api = RecordingHighlightsApi(saved)
        viewModel = HighlightsViewModel(api = api, entryId = 42)

        screen = ComposeScreen.show {
            val host = rememberHighlightsHost(viewModel)

            MaterialTheme {
                CompositionLocalProvider(LocalHighlights provides host) {
                    ExplicaView(
                        state = state,
                        readerStyle = ReaderStyle.default,
                        onNavigateBack = {},
                        onDraftChange = {},
                        onSend = {},
                        onAsk = {},
                        onExplain = {},
                        onRetry = {},
                        onOpenLink = {},
                    )
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
    fun theExplanationAndEachAnswer_paintTheHighlightsOfTheirOwnPlace() {
        show(stateOf(ExplicaPhase.READY))

        screen.assertSoon("not painted") {
            screen.backgrounds(explanation).isNotEmpty() && screen.backgrounds(answer).isNotEmpty()
        }

        // The same words are pink in the explanation and green in the story: the screen shows the explanation's.
        assertEquals(listOf(Triple(12, 26, color(HighlightColor.PINK))), screen.backgrounds(explanation))
        // The first answer is answer:0; answer:1 has no answer on this screen.
        assertEquals(listOf(Triple(0, 14, color(HighlightColor.BLUE))), screen.backgrounds(answer))
    }

    @Test
    fun anExplanationStillBeingWritten_isNotHighlighted() {
        show(stateOf(ExplicaPhase.LOADING))

        screen.stepUntil(limit = 300) { false }

        assertTrue(screen.drawn().any { it.text == explanation })
        assertEquals(emptyList<Triple<Int, Int, Color>>(), screen.backgrounds(explanation))
        // The answer is final, whatever the explanation is doing.
        assertEquals(listOf(Triple(0, 14, color(HighlightColor.BLUE))), screen.backgrounds(answer))
    }

    @Test
    fun theTopBarIcon_opensTheListOfEveryHighlightOfTheStory() {
        show(stateOf(ExplicaPhase.READY))
        screen.assertSoon("not painted") { screen.backgrounds(explanation).isNotEmpty() }

        screen.click { screen.hasDescription(it, "Highlights") }

        screen.assertSoon("no sheet") { screen.hasSheet() }
        screen.assertSoon("no rows") { "Moldova joined" in screen.sheetTexts() }
        assertEquals(
            listOf(
                "Highlights",
                "Explanation", "met on Tuesday",
                "Answer 1", "Moldova joined",
                "Story", "met on Tuesday",
                "Answer 2", "Moldova joined",
            ),
            screen.sheetTexts(),
        )
    }

    @Test
    fun aHighlightTheServerRefuses_goesBack_andTheReaderIsToldWhy() {
        show(stateOf(ExplicaPhase.READY))
        screen.assertSoon("not painted") { screen.backgrounds(explanation).isNotEmpty() }
        api.refuseAdds = FailureKind.NETWORK

        viewModel.add("in Brussels", HighlightColor.YELLOW, "explanation")

        screen.assertSoon("not told") { screen.drawn().any { it.text == "Couldn't save the highlight. No connection." } }
        screen.assertSoon("still there") { viewModel.state.value.items.none { it.text == "in Brussels" } }
        // The ones that were there are still painted.
        assertEquals(listOf(Triple(12, 26, color(HighlightColor.PINK))), screen.backgrounds(explanation))
    }
}
