package com.capyreader.app.ui.explica.highlights

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.capyreader.app.ui.explica.ExplicaResult
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightAction
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightsApi
import com.capyreader.app.ui.explica.HighlightsViewModel
import com.jocmp.mallet.LinearText
import com.jocmp.mallet.LinearTextBlockStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightSpansTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun paragraph(text: String) = LinearText(
        ids = emptySet(),
        text = text,
        annotations = emptyList(),
        blockStyle = LinearTextBlockStyle.TEXT,
    )

    private fun highlight(
        id: String,
        text: String,
        color: String = "yellow",
        prefix: String = "",
        suffix: String = "",
    ) = Highlight(id = id, text = text, color = color, where = "explanation", prefix = prefix, suffix = suffix)

    private fun resolve(
        paragraphs: List<LinearText>,
        highlights: List<Highlight>,
        dark: Boolean = false,
        onTap: (String) -> Unit = {},
    ) = HighlightSpans.resolve(paragraphs, highlights, dark, onTap)

    // resolve

    @Test
    fun resolve_givesEachParagraphItsOwnHighlights() {
        val first = paragraph("Alpha beta")
        val second = paragraph("Gamma delta")

        val spans = resolve(
            listOf(first, second),
            listOf(highlight("h1", "delta", color = "green"), highlight("h2", "Alpha", color = "pink")),
        )

        assertEquals(
            listOf(HighlightSpan("h2", 0, 5, HighlightColor.PINK)),
            spans.forParagraph(first)!!.spans,
        )
        assertEquals(
            listOf(HighlightSpan("h1", 6, 11, HighlightColor.GREEN)),
            spans.forParagraph(second)!!.spans,
        )
    }

    @Test
    fun resolve_looksParagraphsUpByIdentity_soEqualParagraphsAreNotMixedUp() {
        val before = paragraph("Alpha beta")
        val middle = paragraph("Gamma delta")
        val after = paragraph("Alpha beta")
        assertEquals(before, after)

        val spans = resolve(
            listOf(before, middle, after),
            listOf(highlight("h1", "Alpha beta", prefix = "Gamma delta ")),
        )

        assertNull(spans.forParagraph(before))
        assertNull(spans.forParagraph(middle))
        assertNotNull(spans.forParagraph(after))
    }

    @Test
    fun resolve_aHighlightThatIsNotInTheBlock_leavesEveryParagraphAlone() {
        val only = paragraph("Alpha beta")

        val spans = resolve(listOf(only), listOf(highlight("h1", "something else")))

        assertNull(spans.forParagraph(only))
    }

    @Test
    fun resolve_aParagraphThatWasNotGiven_hasNothing() {
        val spans = resolve(listOf(paragraph("Alpha beta")), listOf(highlight("h1", "Alpha")))

        assertNull(spans.forParagraph(paragraph("Alpha beta")))
    }

    // styled

    @Test
    fun styled_paintsTheBackgroundOfTheColorOverTheRange_andKeepsWhatWasThere() {
        val text = buildAnnotatedString {
            append("Hello linked world")
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 5)
            addLink(LinkAnnotation.Url("https://example.org"), 6, 12)
        }
        val paragraph = paragraph("Hello linked world")
        val spans = resolve(listOf(paragraph), listOf(highlight("h1", "linked world", color = "blue")))

        val styled = spans.forParagraph(paragraph)!!.styled(text)

        assertEquals(text.text, styled.text)
        assertTrue(styled.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == 5 })
        assertEquals(1, styled.getLinkAnnotations(0, styled.length).size)

        val backgrounds = styled.spanStyles.filter { it.item.background != Color.Unspecified }
        assertEquals(1, backgrounds.size)
        assertEquals(6, backgrounds.single().start)
        assertEquals(18, backgrounds.single().end)
        assertEquals(Color(HighlightPalette.argb(HighlightColor.BLUE, dark = false)), backgrounds.single().item.background)
    }

    @Test
    fun styled_usesTheColorsOfTheThemeMode() {
        val paragraph = paragraph("Alpha beta")
        val highlights = listOf(highlight("h1", "Alpha", color = "yellow"))

        val light = resolve(listOf(paragraph), highlights, dark = false).forParagraph(paragraph)!!.styled(AnnotatedString("Alpha beta"))
        val dark = resolve(listOf(paragraph), highlights, dark = true).forParagraph(paragraph)!!.styled(AnnotatedString("Alpha beta"))

        assertEquals(Color(HighlightPalette.argb(HighlightColor.YELLOW, dark = false)), light.spanStyles.single().item.background)
        assertEquals(Color(HighlightPalette.argb(HighlightColor.YELLOW, dark = true)), dark.spanStyles.single().item.background)
    }

    @Test
    fun styled_aRangeOutsideTheText_isIgnored_andNothingFails() {
        val paragraph = paragraph("Short")
        val spans = resolve(listOf(paragraph), listOf(highlight("h1", "Short")))
        // The text that is drawn is not always the one that was measured: a shorter one must not crash the reader.
        val shorter = AnnotatedString("Sh")

        val styled = spans.forParagraph(paragraph)!!.styled(shorter)

        assertEquals("Sh", styled.text)
        assertEquals(1, styled.spanStyles.size)
        assertEquals(2, styled.spanStyles.single().end)
        assertEquals(emptyList<Any>(), spans.forParagraph(paragraph)!!.styled(AnnotatedString("")).spanStyles)
    }

    // the highlight under a tap

    @Test
    fun highlightIdNear_isTheNewestOnTop_andAGapAtAnEndCounts() {
        val paragraph = paragraph("Alpha beta gamma")
        val spans = resolve(
            listOf(paragraph),
            listOf(highlight("older", "beta gamma"), highlight("newer", "Alpha beta")),
        ).forParagraph(paragraph)!!

        assertEquals("newer", spans.highlightIdNear(0))
        assertEquals("newer", spans.highlightIdNear(6))
        // Where the newer one ends, the older one goes on: the newer one is on top.
        assertEquals("newer", spans.highlightIdNear(10))
        assertEquals("older", spans.highlightIdNear(11))
        assertEquals("older", spans.highlightIdNear(16))
        assertNull(spans.highlightIdNear(17))
        assertNull(spans.highlightIdNear(-1))
    }

    @Test
    fun aTapOnAHighlight_isReportedWithItsId() {
        val paragraph = paragraph("Alpha beta")
        val tapped = mutableListOf<String>()
        val spans = resolve(listOf(paragraph), listOf(highlight("h1", "beta")), onTap = { tapped += it })

        // The layout part (which character is under a finger) needs a real text layout; with none nothing happens.
        spans.forParagraph(paragraph)!!.tapAt(layout = null, position = Offset(3f, 3f))
        assertTrue(tapped.isEmpty())
        assertSame(spans.forParagraph(paragraph), spans.forParagraph(paragraph))
    }

    // the scope a selection goes through

    private class NoCalls : HighlightsApi {
        override suspend fun listHighlights(entryId: Long) = ExplicaResult.Success(emptyList<Highlight>())

        override suspend fun addHighlight(
            entryId: Long,
            text: String,
            color: HighlightColor,
            where: String,
            prefix: String,
            suffix: String,
        ): ExplicaResult<Highlight> = error("not expected")

        override suspend fun removeHighlight(entryId: Long, id: String): ExplicaResult<Unit> = error("not expected")
    }

    private fun scope(vararg paragraphs: String): Pair<HighlightScopeState, HighlightsViewModel> {
        val viewModel = HighlightsViewModel(api = NoCalls(), entryId = 0)
        val state = mutableStateOf(viewModel.state.value)
        val host = HighlightsHost(viewModel, state)

        return HighlightScopeState("explanation", paragraphs.map { paragraph(it) }, host) to viewModel
    }

    @Test
    fun aSelection_becomesAPendingHighlightWithItsContext_andWaitsForAColor() {
        val (scope, _) = scope("The quick brown fox jumps.")
        var cleared = false

        scope.requestHighlight(listOf("quick brown"), clearSelection = { cleared = true })

        val pending = scope.pending!!
        assertEquals("quick brown", pending.text)
        assertEquals("The ", pending.prefix)
        assertEquals(" fox jumps.", pending.suffix)
        assertTrue(!cleared)

        pending.clearSelection()
        assertTrue(cleared)
    }

    @Test
    fun aSelectionOfNothingReadable_isIgnored() {
        val (scope, viewModel) = scope("Some text.")

        scope.requestHighlight(emptyList(), clearSelection = {})
        scope.requestHighlight(listOf("•", " "), clearSelection = {})

        assertNull(scope.pending)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun aSelectionThatIsTooLong_isNotOffered_andTheReaderIsTold() {
        val (scope, viewModel) = scope("Some text.")

        scope.requestHighlight(listOf("word ".repeat(300)), clearSelection = {})

        assertNull(scope.pending)
        assertEquals(HighlightAction.SELECTION_TOO_LONG, viewModel.state.value.failure?.action)
        assertNull(viewModel.state.value.failure?.kind)
    }

    @Test
    fun aTap_remembersWhichHighlightItWas() {
        val (scope, _) = scope("Some text.")

        scope.tap("h7")

        assertEquals("h7", scope.tappedId)
    }
}
