package com.capyreader.app.ui.explica.highlights

import android.view.ActionMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import com.capyreader.app.ui.articles.reader.ArticleBody
import com.capyreader.app.ui.articles.reader.LocalReaderStyle
import com.capyreader.app.ui.articles.reader.ReaderActions
import com.capyreader.app.ui.articles.reader.ReaderStyle
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightAction
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightsViewModel
import com.capyreader.app.ui.explica.highlights.ComposeScreen.Companion.color
import com.jocmp.mallet.LinearArticle
import com.jocmp.mallet.Mallet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner

/**
 * Runs the real thing on the JVM, on Robolectric's Android 15 (see [ComposeScreen]): the reader's own text elements
 * in a real Compose `SelectionContainer`, inside a [HighlightScope] with a [HighlightSelection], with a real
 * [HighlightsViewModel] over a fake server. Text is selected with `SelectionState.select`, which is what a long
 * press and the drag of the handles end up calling; the selection toolbar is the framework's own floating
 * `ActionMode`; what is drawn is read from the semantics of the screen, and the bottom sheets are read and clicked
 * through theirs.
 *
 * What this cannot show: how it looks, a long press (none started here; a double tap does select a word), and where
 * in a line a finger lands, so a tap is only tried before the first character and after the last one of a line.
 */
@RunWith(RobolectricTestRunner::class)
class HighlightSelectionTest {
    private lateinit var screen: ComposeScreen
    private lateinit var selection: SelectionState
    private lateinit var article: LinearArticle
    private lateinit var viewModel: HighlightsViewModel
    private lateinit var api: RecordingHighlightsApi
    private var scope: HighlightScopeState? = null
    private var spans: HighlightSpans? = null
    private var listOpen by mutableStateOf(false)

    private val councilHtml = "<p>The council met on Tuesday in Brussels.</p><p>Moldova joined the talks.</p>"
    private val council = listOf("The council met on Tuesday in Brussels.", "Moldova joined the talks.")

    /** A highlight at the very start of the first paragraph, which is where a tap can be aimed in a test. */
    private val savedAtTheStart = listOf(
        Highlight(id = "s1", text = "The council", color = "yellow", where = "explanation"),
    )

    /**
     * Shows [html] the way the AI screen does. When [presses] is given, every finger that goes down records whether
     * the text had used the press by the time its parent, the selection container, looked at it.
     */
    private fun show(html: String, saved: List<Highlight> = emptyList(), presses: MutableList<Boolean>? = null) {
        article = Mallet.flatten(html, "https://news.example/").getOrThrow()
        api = RecordingHighlightsApi(saved)
        viewModel = HighlightsViewModel(api = api, entryId = 42)

        screen = ComposeScreen.show {
            val host = rememberHighlightsHost(viewModel)

            selection = rememberSelectionState()

            MaterialTheme {
                CompositionLocalProvider(
                    LocalReaderStyle provides ReaderStyle.default,
                    LocalHighlights provides host,
                ) {
                    HighlightScope(where = "explanation", article = article) {
                        scope = LocalHighlightScope.current
                        spans = LocalHighlightSpans.current

                        HighlightSelection(state = selection) {
                            Box(modifier = presses?.let { observePresses(it) } ?: Modifier) {
                                ArticleBody(article = article, actions = ReaderActions.none)
                            }
                        }
                    }

                    if (listOpen) {
                        HighlightsListSheet(host = host, onDismiss = { listOpen = false })
                    }
                }
            }
        }
    }

    private fun observePresses(into: MutableList<Boolean>): Modifier {
        return Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)

                    event.changes.filter { it.changedToDownIgnoreConsumed() }.forEach { into += it.isConsumed }
                }
            }
        }
    }

    @After
    fun tearDown() {
        // The application starts Koin for every test; the next test must find it stopped.
        stopKoin()

        if (::selection.isInitialized) selection.clear()
        if (::screen.isInitialized) screen.destroy()
    }

    private fun backgrounds(text: String) = screen.backgrounds(text)

    /** The selectable texts in reading order, list markers included, which is what `select(TextRange)` counts over. */
    private fun rangeOf(selectable: List<String>, passage: String): TextRange {
        val start = selectable.joinToString("").indexOf(passage)

        check(start >= 0) { "$passage is not in $selectable" }

        return TextRange(start, start + passage.length)
    }

    private fun selectAndWaitForTheToolbar(range: TextRange): ActionMode {
        selection.select(range)

        screen.assertSoon("no toolbar") { screen.toolbar() != null }

        return screen.toolbar()!!
    }

    // what the selection hands over

    @Test
    fun selectedTexts_areTheSelectedPartsOfTheParagraphs_listMarkersIncluded() {
        show("<p>First paragraph is here.</p><ul><li>One item</li></ul><ol><li>Numbered</li></ol><p>Last one.</p>")

        selection.selectAll()
        assertEquals(
            listOf("First paragraph is here.", "•", "One item", "1.", "Numbered", "Last one."),
            selection.selectedTexts.map { it.text },
        )

        selection.select(TextRange(6, 30))
        assertEquals(listOf("paragraph is here.", "•", "One i"), selection.selectedTexts.map { it.text })

        selection.clear()
        assertEquals(emptyList<String>(), selection.selectedTexts.map { it.text })
    }

    @Test
    fun theSelectionToolbar_offersHighlightFirst_thenCopyAndSelectAll() {
        show(councilHtml)

        val toolbar = selectAndWaitForTheToolbar(rangeOf(council, "met on Tuesday"))

        // Ion, 2026-10-01: after Copy, Select all and the apps that process text, Highlight was two scrolls away.
        assertEquals(listOf("Highlight", "Copy", "Select all"), screen.toolbarTitles(toolbar))

        selection.clear()
    }

    @Test
    fun highlightFirst_movesOnlyTheHighlightItem_andLeavesADataWithoutItAlone() {
        val copy = TextContextMenuItem(key = "copy", label = "Copy") {}
        val translate = TextContextMenuItem(key = "translate", label = "Translate") {}
        val highlight = TextContextMenuItem(key = HIGHLIGHT_MENU_KEY, label = "Highlight") {}
        val data = TextContextMenuData(listOf(copy, TextContextMenuSeparator, translate, highlight))

        assertEquals(listOf(highlight, copy, TextContextMenuSeparator, translate), highlightFirst(data).components)

        val without = TextContextMenuData(listOf(copy, translate))
        assertSame(without, highlightFirst(without))

        val alreadyFirst = TextContextMenuData(listOf(highlight, copy))
        assertSame(alreadyFirst, highlightFirst(alreadyFirst))
    }

    @Test
    fun choosingHighlight_handsOverTheSelectedWordsWithTheirContext() {
        show(councilHtml)
        val toolbar = selectAndWaitForTheToolbar(rangeOf(council, "met on Tuesday"))

        screen.tap(toolbar, "Highlight")

        val waiting = scope!!.pending!!
        assertEquals("met on Tuesday", waiting.text)
        assertEquals("The council ", waiting.prefix)
        assertEquals(" in Brussels. Moldova joined the talks.", waiting.suffix)

        waiting.clearSelection()
        scope!!.pending = null
    }

    @Test
    fun pickingAColor_paintsTheTextBehindTheWords_andSavesTheHighlight() {
        show(councilHtml)
        val toolbar = selectAndWaitForTheToolbar(rangeOf(council, "met on Tuesday"))
        screen.tap(toolbar, "Highlight")

        scope!!.pick(HighlightColor.GREEN)

        assertEquals(emptyList<String>(), selection.selectedTexts.map { it.text })
        screen.assertSoon("not drawn") { spans?.forParagraph(article.paragraphs()[0]) != null }
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        assertEquals(listOf(Triple(12, 26, color(HighlightColor.GREEN))), backgrounds(council[0]))
        assertEquals(emptyList<Triple<Int, Int, Color>>(), backgrounds(council[1]))
        assertNull(spans?.forParagraph(article.paragraphs()[1]))

        // The save goes after the screen shows it, and the server's answer keeps it there.
        screen.assertSoon("not saved") { api.server.isNotEmpty() }
        assertEquals(
            listOf(
                "list:42",
                "add:42:met on Tuesday:green:explanation:The council | in Brussels. Moldova joined the talks.",
            ),
            api.calls,
        )
        screen.assertSoon("still waiting") { viewModel.state.value.pendingIds.isEmpty() }
        assertEquals(listOf("s1"), viewModel.state.value.items.map { it.id })
    }

    @Test
    fun aSelectionAcrossTwoParagraphs_isOneHighlightPaintedOnBoth() {
        show(councilHtml)
        val toolbar = selectAndWaitForTheToolbar(rangeOf(council, "Brussels.Moldova joined"))

        screen.tap(toolbar, "Highlight")

        assertEquals("Brussels. Moldova joined", scope!!.pending!!.text)

        scope!!.pick(HighlightColor.PINK)

        screen.assertSoon("not painted") { backgrounds(council[1]).isNotEmpty() }
        assertEquals(listOf(30 to 39), backgrounds(council[0]).map { it.first to it.second })
        assertEquals(listOf(0 to 14), backgrounds(council[1]).map { it.first to it.second })
    }

    @Test
    fun listMarkersInASelection_areLeftOutOfTheHighlight() {
        show("<p>Intro line</p><ul><li>First item</li><li>Second item</li></ul>")
        val selectable = listOf("Intro line", "•", "First item", "•", "Second item")
        val toolbar = selectAndWaitForTheToolbar(rangeOf(selectable, "line•First item•Second"))

        assertEquals(
            listOf("line", "•", "First item", "•", "Second"),
            selection.selectedTexts.map { it.text },
        )

        screen.tap(toolbar, "Highlight")

        assertEquals("line First item Second", scope!!.pending!!.text)

        scope!!.pick(HighlightColor.YELLOW)

        screen.assertSoon("not painted") { backgrounds("Second item").isNotEmpty() }
        assertEquals(listOf(6 to 10), backgrounds("Intro line").map { it.first to it.second })
        assertEquals(listOf(0 to 10), backgrounds("First item").map { it.first to it.second })
        assertEquals(listOf(0 to 6), backgrounds("Second item").map { it.first to it.second })
    }

    @Test
    fun aSelectionThatIsTooLong_isRefusedWithAMessage_andNothingIsOffered() {
        val text = "word ".repeat(400).trim()
        show("<p>$text</p>")
        val toolbar = selectAndWaitForTheToolbar(TextRange(0, text.length))

        screen.tap(toolbar, "Highlight")

        assertNull(scope!!.pending)
        assertEquals(HighlightAction.SELECTION_TOO_LONG, viewModel.state.value.failure?.action)

        selection.clear()
    }

    @Test
    fun aSelectionOfNothing_offersNothing() {
        show(councilHtml)

        assertNotNull(scope)
        scope!!.requestHighlight(emptyList()) {}

        assertNull(scope!!.pending)
        assertNull(viewModel.state.value.failure)
    }

    // what is already highlighted

    @Test
    fun aHighlightTheServerAlreadyHas_isPaintedWhenTheScreenOpens() {
        show(
            councilHtml,
            saved = listOf(
                Highlight(
                    id = "s1",
                    text = "Moldova joined",
                    color = "blue",
                    where = "explanation",
                    prefix = "Brussels. ",
                    suffix = " the talks.",
                ),
                Highlight(id = "s2", text = "Brussels", color = "pink", where = "story"),
            ),
        )

        screen.assertSoon("not painted") { backgrounds(council[1]).isNotEmpty() }

        assertEquals(listOf(Triple(0, 14, color(HighlightColor.BLUE))), backgrounds(council[1]))
        // The other one belongs to the story, not to this block.
        assertEquals(emptyList<Triple<Int, Int, Color>>(), backgrounds(council[0]))
    }

    @Test
    fun removingAHighlight_takesTheBackgroundOff() {
        show(councilHtml, saved = listOf(Highlight(id = "s1", text = "met on Tuesday", where = "explanation")))
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        viewModel.remove("s1")

        screen.assertSoon("still painted") { backgrounds(council[0]).isEmpty() }
        screen.assertSoon("not removed") { api.server.isEmpty() }
    }

    @Test
    fun aHighlightMadeAgainInAnotherColor_repaintsIt() {
        show(councilHtml, saved = listOf(Highlight(id = "s1", text = "met on Tuesday", where = "explanation")))
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        viewModel.recolor("s1", HighlightColor.BLUE)

        screen.assertSoon("not repainted") { backgrounds(council[0]).singleOrNull()?.third == color(HighlightColor.BLUE) }
        screen.assertSoon("still waiting") { viewModel.state.value.pendingIds.isEmpty() }
        assertEquals(listOf("list:42", "remove:s1", "add:42:met on Tuesday:blue:explanation:|"), api.calls)
    }

    // a finger on a highlighted passage

    @Test
    fun aTapOnAHighlightedPassage_opensItsActions_andATapOnTheRestOfTheLineDoesNot() {
        show(councilHtml, saved = savedAtTheStart)
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        // After the last character: the gap there is not in the highlight, which ends after "The council".
        screen.press(Offset(30f, 18f))
        assertNull(scope!!.tappedId)

        // Before the first character, which is where the highlight starts.
        screen.press(Offset(10f, 18f))
        assertEquals("s1", scope!!.tappedId)
    }

    @Test
    fun aFingerThatDragsAway_orStaysDownAsLongAsALongPress_isNotATap() {
        show(councilHtml, saved = savedAtTheStart)
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        // A scroll or a drag that leaves the text.
        screen.drag(from = Offset(10f, 18f), to = Offset(10f, 300f))
        assertNull(scope!!.tappedId)

        // A press that lasts as long as a long press belongs to the selection, not to a tap.
        screen.press(Offset(10f, 18f), millis = 700)
        assertNull(scope!!.tappedId)

        screen.press(Offset(10f, 18f), millis = 40)
        assertEquals("s1", scope!!.tappedId)
    }

    @Test
    fun aDoubleTapSelectsAWordOfAHighlightedParagraph_asItDoesOnAPlainOne() {
        show(councilHtml, saved = savedAtTheStart)
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        // A double tap selects a word, the one gesture that started a selection on Robolectric (a long press did not).
        screen.doubleTap(Offset(10f, 70f))
        assertEquals("a plain paragraph", listOf("Moldova"), selection.selectedTexts.map { it.text })
        selection.clear()

        screen.doubleTap(Offset(10f, 18f))
        assertEquals("a highlighted paragraph", listOf("The"), selection.selectedTexts.map { it.text })
    }

    @Test
    fun aPressOnAHighlightedParagraph_isLeftUnconsumed_soTheSelectionContainerCanStartASelectionFromIt() {
        val presses = mutableListOf<Boolean>()
        show(councilHtml, saved = savedAtTheStart, presses = presses)
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        screen.press(Offset(10f, 18f))

        // The paragraph did react to the press (it is a tap on the highlight), and still left it as it came.
        assertEquals("s1", scope!!.tappedId)
        assertEquals(listOf(false), presses)
    }

    // the sheets

    @Test
    fun theColorSheet_showsThePassageAndTheFourColors_andAClickPicksOne() {
        show(councilHtml)
        val toolbar = selectAndWaitForTheToolbar(rangeOf(council, "met on Tuesday"))
        screen.tap(toolbar, "Highlight")
        // The handles go once a color is picked; here they go now, so that the looper can idle while the sheet opens.
        selection.clear()

        screen.assertSoon("no sheet") { screen.hasSheet() }
        screen.assertSoon("no title") { "Highlight as" in screen.sheetTexts() }
        assertEquals(
            listOf("Highlight as", "met on Tuesday", "Idea", "Fact or definition", "Question", "Quote"),
            screen.sheetTexts(),
        )

        screen.sheetClick { screen.hasText(it, "Fact or definition") }

        assertNull(scope!!.pending)
        screen.assertSoon("not saved") { api.server.isNotEmpty() }
        assertEquals(
            "add:42:met on Tuesday:green:explanation:The council | in Brussels. Moldova joined the talks.",
            api.calls.last(),
        )
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }
    }

    private fun openTheActionsOfTheHighlightAtTheStart() {
        show(councilHtml, saved = savedAtTheStart)
        screen.assertSoon("not painted") { backgrounds(council[0]).isNotEmpty() }

        screen.press(Offset(10f, 18f))

        screen.assertSoon("no sheet") { screen.hasSheet() }
        screen.assertSoon("no actions") { "Remove" in screen.sheetTexts() }
    }

    @Test
    fun theActionsSheet_showsThePassage_andCopyPutsItOnTheClipboard() {
        openTheActionsOfTheHighlightAtTheStart()

        assertEquals(listOf("The council", "Color", "Copy", "Remove"), screen.sheetTexts())

        screen.sheetClick { screen.hasText(it, "Copy") }

        screen.assertSoon("not copied") { screen.clipboardText() != null }
        assertEquals("The council", screen.clipboardText())
        assertNull(scope!!.tappedId)
    }

    @Test
    fun theActionsSheet_aClickOnAColorRepaintsTheHighlight() {
        openTheActionsOfTheHighlightAtTheStart()

        screen.sheetClick { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Question" }

        screen.assertSoon("not repainted") { backgrounds(council[0]).singleOrNull()?.third == color(HighlightColor.BLUE) }
        screen.assertSoon("still waiting") { viewModel.state.value.pendingIds.isEmpty() }
        assertEquals(listOf("list:42", "remove:s1", "add:42:The council:blue:explanation:|"), api.calls)
    }

    @Test
    fun theActionsSheet_aClickOnRemoveTakesTheHighlightOff() {
        openTheActionsOfTheHighlightAtTheStart()

        screen.sheetClick { screen.hasText(it, "Remove") }

        screen.assertSoon("still painted") { backgrounds(council[0]).isEmpty() }
        screen.assertSoon("not removed") { api.server.isEmpty() }
    }

    @Test
    fun theListSheet_showsEveryHighlightOfTheStory_withWhereItIs_andRemovesOne() {
        show(
            councilHtml,
            saved = listOf(
                Highlight(id = "s1", text = "met on Tuesday", color = "yellow", where = "explanation"),
                Highlight(id = "s2", text = "Brussels", color = "pink", where = "story"),
                Highlight(id = "s3", text = "joined the talks", color = "blue", where = "answer:1"),
            ),
        )

        listOpen = true
        screen.assertSoon("no sheet") { screen.hasSheet() }
        screen.assertSoon("no rows") { "Brussels" in screen.sheetTexts() }

        assertEquals(
            listOf("Highlights", "Explanation", "met on Tuesday", "Story", "Brussels", "Answer 2", "joined the talks"),
            screen.sheetTexts(),
        )

        val all = screen.nodes(merged = true, inDialog = true)
        val row = all.indexOfFirst { screen.hasText(it, "Brussels") }
        val remove = all.drop(row).first { screen.hasDescription(it, "Remove") }

        screen.sheetClick { it.id == remove.id }

        screen.assertSoon("still listed") { viewModel.state.value.items.none { it.id == "s2" } }
        screen.assertSoon("not removed") { api.server.none { it.id == "s2" } }
    }
}
