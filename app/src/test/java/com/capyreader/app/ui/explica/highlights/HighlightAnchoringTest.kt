package com.capyreader.app.ui.explica.highlights

import com.capyreader.app.ui.explica.Highlight
import com.jocmp.mallet.LinearArticle
import com.jocmp.mallet.LinearBlockQuote
import com.jocmp.mallet.LinearImage
import com.jocmp.mallet.LinearListItem
import com.jocmp.mallet.LinearTable
import com.jocmp.mallet.LinearTableCellItem
import com.jocmp.mallet.LinearTableCellItemType
import com.jocmp.mallet.LinearText
import com.jocmp.mallet.LinearTextBlockStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HighlightAnchoringTest {
    private fun highlight(
        text: String,
        id: String = "h1",
        prefix: String = "",
        suffix: String = "",
    ) = Highlight(
        id = id,
        text = text,
        color = "yellow",
        where = "explanation",
        prefix = prefix,
        suffix = suffix,
        created = 1,
    )

    private fun passage(capture: Capture): Captured = (capture as Capture.Passage).captured

    // place

    @Test
    fun place_aTextThatOccursOnce_isPlacedInItsParagraph() {
        val paragraphs = listOf("Moldova joined the talks.", "The council met on Tuesday in Brussels.")

        val placements = HighlightAnchoring.place(paragraphs, listOf(highlight("met on Tuesday")))

        assertEquals(listOf(Placement(highlightId = "h1", paragraph = 1, start = 12, end = 26)), placements)
        assertEquals("met on Tuesday", paragraphs[1].substring(12, 26))
    }

    @Test
    fun place_aTextThatIsNotInTheBlock_isNotPlaced_andNothingFails() {
        val paragraphs = listOf("One short paragraph.", "")
        val highlights = listOf(
            highlight("a passage that is not there", id = "a"),
            highlight("", id = "b"),
            highlight("   \n ", id = "c"),
            highlight("One short paragraph. And a much longer tail than the block has", id = "d"),
        )

        assertEquals(emptyList<Placement>(), HighlightAnchoring.place(paragraphs, highlights))
        assertEquals(emptyList<Placement>(), HighlightAnchoring.place(emptyList(), highlights))
        assertEquals(emptyList<Placement>(), HighlightAnchoring.place(paragraphs, emptyList()))
    }

    @Test
    fun place_whenTheWordsOccurSeveralTimes_theOccurrenceWhoseContextMatchesWins() {
        val paragraphs = listOf(
            "The bank said rates rise.",
            "A river bank flooded after the rain.",
            "The bank said nothing more.",
        )

        val second = HighlightAnchoring.place(
            paragraphs,
            listOf(highlight("bank", prefix = "A river ", suffix = " flooded after the rain.")),
        )
        val third = HighlightAnchoring.place(
            paragraphs,
            listOf(highlight("bank", prefix = "rise. The ", suffix = " said nothing more.")),
        )

        assertEquals(listOf(Placement("h1", paragraph = 1, start = 8, end = 12)), second)
        assertEquals(listOf(Placement("h1", paragraph = 2, start = 4, end = 8)), third)
    }

    @Test
    fun place_theContextOfAServerThatTrimsIt_stillTellsTheOccurrencesApart() {
        val paragraphs = listOf("The bank said rates rise.", "A river bank flooded.")

        // What was sent was "A river " and " flooded."; the server kept the trimmed words.
        val placements = HighlightAnchoring.place(
            paragraphs,
            listOf(highlight("bank", prefix = "A river", suffix = "flooded.")),
        )

        assertEquals(listOf(Placement("h1", paragraph = 1, start = 8, end = 12)), placements)
    }

    @Test
    fun place_whenNothingTellsTheOccurrencesApart_allOfThemArePlaced() {
        val paragraphs = listOf("The bank said rates rise.", "A river bank flooded.", "The bank said nothing more.")

        val placements = HighlightAnchoring.place(paragraphs, listOf(highlight("bank")))

        assertEquals(
            listOf(
                Placement("h1", paragraph = 0, start = 4, end = 8),
                Placement("h1", paragraph = 1, start = 8, end = 12),
                Placement("h1", paragraph = 2, start = 4, end = 8),
            ),
            placements,
        )
    }

    @Test
    fun place_theSameLettersInsideALongerWord_loseToTheWholeWord() {
        val paragraphs = listOf("The nation and ion.")

        val placements = HighlightAnchoring.place(paragraphs, listOf(highlight("ion")))

        assertEquals(listOf(Placement("h1", paragraph = 0, start = 15, end = 18)), placements)
    }

    @Test
    fun place_aPassageThatCrossesParagraphs_isSplitAcrossThem() {
        val paragraphs = listOf("The minister said the vote", "will happen next week.")

        val placements = HighlightAnchoring.place(paragraphs, listOf(highlight("vote will happen")))

        assertEquals(
            listOf(
                Placement("h1", paragraph = 0, start = 22, end = 26),
                Placement("h1", paragraph = 1, start = 0, end = 11),
            ),
            placements,
        )
    }

    @Test
    fun place_ignoresHowWhitespaceIsWritten() {
        val paragraphs = listOf("a  b c d\ne")

        val placements = HighlightAnchoring.place(paragraphs, listOf(highlight("a b c d e")))

        assertEquals(listOf(Placement("h1", paragraph = 0, start = 0, end = 10)), placements)
    }

    @Test
    fun place_keepsTheOrderOfTheHighlights_soANewerOneIsDrawnOverAnOlderOne() {
        val paragraphs = listOf("Alpha beta gamma")

        val placements = HighlightAnchoring.place(
            paragraphs,
            listOf(highlight("beta gamma", id = "older"), highlight("Alpha beta", id = "newer")),
        )

        assertEquals(listOf("older", "newer"), placements.map { it.highlightId })
        assertEquals(listOf(6 to 16, 0 to 10), placements.map { it.start to it.end })
    }

    @Test
    fun place_worksOnRomanianText() {
        val paragraphs = listOf("Guvernul a aprobat ieri hotărârea privind ajutorul pentru țărani.")

        val placements = HighlightAnchoring.place(paragraphs, listOf(highlight("ajutorul pentru țărani")))

        assertEquals(1, placements.size)
        assertEquals("ajutorul pentru țărani", paragraphs[0].substring(placements[0].start, placements[0].end))
    }

    // capture

    @Test
    fun capture_aPassageThatOccursOnce_storesTheTextAroundIt() {
        val paragraphs = listOf("First paragraph is short.", "The quick brown fox jumps over the lazy dog.", "Last one.")

        val captured = passage(HighlightAnchoring.capture(paragraphs, listOf("quick brown fox")))

        assertEquals("quick brown fox", captured.text)
        assertEquals("First paragraph is short. The ", captured.prefix)
        assertEquals(" jumps over the lazy dog. Last one.", captured.suffix)
    }

    @Test
    fun capture_cutsTheContextAtSixtyCharactersOnEachSide() {
        val paragraphs = listOf("a".repeat(100) + " TARGET " + "b".repeat(100))

        val captured = passage(HighlightAnchoring.capture(paragraphs, listOf("TARGET")))

        assertEquals(HighlightAnchoring.MAX_CONTEXT, captured.prefix.length)
        assertEquals(HighlightAnchoring.MAX_CONTEXT, captured.suffix.length)
        assertEquals("a".repeat(59) + " ", captured.prefix)
        assertEquals(" " + "b".repeat(59), captured.suffix)
    }

    @Test
    fun capture_doesNotCutASurrogatePairInHalf() {
        val before = listOf("😀" + "a".repeat(58) + " TARGET")
        val after = listOf("TARGET " + "a".repeat(58) + "😀")

        val prefix = passage(HighlightAnchoring.capture(before, listOf("TARGET"))).prefix
        val suffix = passage(HighlightAnchoring.capture(after, listOf("TARGET"))).suffix

        assertEquals("a".repeat(58) + " ", prefix)
        assertEquals(" " + "a".repeat(58), suffix)
    }

    @Test
    fun capture_aPassageThatOccursSeveralTimes_storesNoContext() {
        val paragraphs = listOf("The bank said rates rise.", "A river bank flooded.")

        val captured = passage(HighlightAnchoring.capture(paragraphs, listOf("bank")))

        // The selection gives the words, not where they were: nothing says which bank it was.
        assertEquals(Captured(text = "bank", prefix = "", suffix = ""), captured)
    }

    @Test
    fun capture_aPassageThatIsNotInTheBlock_storesNoContext() {
        val paragraphs = listOf("The body of the story.")

        val captured = passage(HighlightAnchoring.capture(paragraphs, listOf("The title of the story")))

        assertEquals(Captured(text = "The title of the story", prefix = "", suffix = ""), captured)
    }

    @Test
    fun capture_dropsListMarkersAndBlankPieces_andWritesThePassageOnOneLine() {
        val paragraphs = listOf("Intro.", "First item of the list", "Second item of the list", "Outro.")

        val captured = passage(
            HighlightAnchoring.capture(
                paragraphs,
                listOf("of the list", "•", "Second item of the", "  ", "2.", ""),
            )
        )

        assertEquals("of the list Second item of the", captured.text)
        assertEquals(
            listOf(Placement("h1", 1, 11, 22), Placement("h1", 2, 0, 18)),
            HighlightAnchoring.place(paragraphs, listOf(highlight(captured.text, prefix = captured.prefix, suffix = captured.suffix))),
        )
    }

    @Test
    fun capture_whatIsSelectedAcrossParagraphs_isPlacedBackAcrossThem() {
        val paragraphs = listOf("The minister said the vote", "will happen next week.")

        val captured = passage(HighlightAnchoring.capture(paragraphs, listOf("vote", "will happen")))

        assertEquals("vote will happen", captured.text)
        assertEquals(
            listOf(Placement("h1", 0, 22, 26), Placement("h1", 1, 0, 11)),
            HighlightAnchoring.place(paragraphs, listOf(highlight(captured.text, prefix = captured.prefix, suffix = captured.suffix))),
        )
    }

    @Test
    fun capture_nothingReadable_isEmpty() {
        val paragraphs = listOf("Some text.")

        assertEquals(Capture.Empty, HighlightAnchoring.capture(paragraphs, emptyList()))
        assertEquals(Capture.Empty, HighlightAnchoring.capture(paragraphs, listOf("", "  \n ")))
        assertEquals(Capture.Empty, HighlightAnchoring.capture(paragraphs, listOf("•", "12.", " • ")))
    }

    @Test
    fun capture_limitsThePassageToAThousandCharacters() {
        val paragraphs = listOf("x")
        val exactly = "w".repeat(HighlightAnchoring.MAX_PASSAGE)

        assertEquals(Capture.TooLong, HighlightAnchoring.capture(paragraphs, listOf(exactly + "w")))
        assertEquals(exactly, passage(HighlightAnchoring.capture(paragraphs, listOf(exactly))).text)
    }

    @Test
    fun capture_trimsThePassage_andCollapsesItsWhitespace() {
        val paragraphs = listOf("See  the\nmap of Chisinau today.")

        val captured = passage(HighlightAnchoring.capture(paragraphs, listOf("  the\nmap of ")))

        assertEquals("the map of", captured.text)
        assertEquals("See ", captured.prefix.take(4))
        assertTrue(captured.suffix.startsWith(" Chisinau"))
    }

    // capture and place together, on blocks made at random

    private val vocabulary = listOf("the", "bank", "river", "said", "rates", "rise", "ion", "nation", "a", "of", "în", "țară")

    private fun randomBlock(random: Random): List<String> {
        return List(random.nextInt(1, 6)) {
            List(random.nextInt(1, 9)) { vocabulary.random(random) }.joinToString(" ")
        }
    }

    /** What the selection container hands over for [range] of the joined text: one piece for each paragraph it touches. */
    private fun selectedPieces(paragraphs: List<String>, range: IntRange): List<String> {
        val starts = paragraphs.runningFold(0) { offset, paragraph -> offset + paragraph.length + 1 }

        return paragraphs.mapIndexedNotNull { index, paragraph ->
            val from = maxOf(range.first, starts[index])
            val to = minOf(range.last + 1, starts[index] + paragraph.length)

            if (from < to) paragraph.substring(from - starts[index], to - starts[index]) else null
        }
    }

    /** The placements a highlight of exactly [range] of the joined text should have. */
    private fun expectedPlacements(paragraphs: List<String>, range: IntRange): List<Placement> {
        val starts = paragraphs.runningFold(0) { offset, paragraph -> offset + paragraph.length + 1 }

        return paragraphs.mapIndexedNotNull { index, paragraph ->
            val from = maxOf(range.first, starts[index])
            val to = minOf(range.last + 1, starts[index] + paragraph.length)

            if (from < to) Placement("h1", index, from - starts[index], to - starts[index]) else null
        }
    }

    @Test
    fun aSelectionOfWholeWords_isFoundAgain_wherever_itWasMade() {
        val random = Random(20260930)

        repeat(3_000) { round ->
            val paragraphs = randomBlock(random)
            val flat = paragraphs.joinToString("\n")
            val words = Regex("\\S+").findAll(flat).toList()
            val first = random.nextInt(words.size)
            val last = random.nextInt(first, words.size)
            val range = words[first].range.first..words[last].range.last

            val captured = passage(HighlightAnchoring.capture(paragraphs, selectedPieces(paragraphs, range)))
            val placements = HighlightAnchoring.place(
                paragraphs,
                listOf(highlight(captured.text, prefix = captured.prefix, suffix = captured.suffix)),
            )
            val expected = expectedPlacements(paragraphs, range)

            // The words can occur elsewhere too, and then the stored context is empty and every occurrence is drawn.
            assertTrue("round $round: $expected not in $placements for $paragraphs", placements.containsAll(expected))

            if (captured.prefix.isNotEmpty() || captured.suffix.isNotEmpty()) {
                assertEquals("round $round: $paragraphs", expected, placements)
            }
        }
    }

    @Test
    fun aSelectionThatStartsOrEndsInsideAWord_isFoundExactlyWhenItsWordsOccurOnce() {
        val random = Random(1_001)
        var unique = 0

        repeat(3_000) { round ->
            val paragraphs = randomBlock(random)
            val flat = paragraphs.joinToString("\n")
            val start = random.nextInt(flat.length)
            val end = random.nextInt(start, flat.length)
            val raw = flat.substring(start, end + 1)

            if (raw.isBlank()) {
                return@repeat
            }

            // The container gives the selected characters as they are, spaces at the edges included.
            val selected = selectedPieces(paragraphs, start..end)
            val trimmedStart = start + (raw.length - raw.trimStart().length)
            val trimmedEnd = end - (raw.length - raw.trimEnd().length)

            val captured = passage(HighlightAnchoring.capture(paragraphs, selected))

            if (captured.prefix.isEmpty() && captured.suffix.isEmpty()) {
                return@repeat
            }

            unique++
            val placements = HighlightAnchoring.place(
                paragraphs,
                listOf(highlight(captured.text, prefix = captured.prefix, suffix = captured.suffix)),
            )

            assertEquals(
                "round $round: $paragraphs, selected ${start..end}",
                expectedPlacements(paragraphs, trimmedStart..trimmedEnd),
                placements,
            )
        }

        // The vocabulary is small on purpose, so most selections are repeated, but the unique ones are not rare.
        assertTrue("only $unique unique selections", unique > 100)
    }

    // paragraphs

    private fun text(value: String) = LinearText(
        ids = emptySet(),
        text = value,
        annotations = emptyList(),
        blockStyle = LinearTextBlockStyle.TEXT,
    )

    private fun cell(value: String) = LinearTableCellItem(
        type = LinearTableCellItemType.DATA,
        colSpan = 1,
        rowSpan = 1,
        content = listOf(text(value)),
    )

    @Test
    fun paragraphs_areTheTextsInReadingOrder_nestedOnesIncluded_withoutImages() {
        val table = LinearTable.build(ids = emptySet(), leftToRight = true) {
            newRow()
            add(cell("A1"))
            add(cell("B1"))
            newRow()
            add(cell("A2"))
            add(cell("B2"))
        }
        val article = LinearArticle(
            elements = listOf(
                text("Intro"),
                LinearBlockQuote(ids = emptySet(), cite = null, content = listOf(text("Quoted"))),
                LinearListItem(ids = emptySet(), orderedIndex = 1, content = listOf(text("Item one"), text("Nested"))),
                LinearImage(ids = emptySet(), sources = emptyList(), caption = text("A caption"), link = null),
                table,
                text("Outro"),
            ),
        )

        assertEquals(
            listOf("Intro", "Quoted", "Item one", "Nested", "A1", "B1", "A2", "B2", "Outro"),
            article.paragraphs().map { it.text },
        )
    }
}
