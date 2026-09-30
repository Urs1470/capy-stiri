package com.capyreader.app.ui.explica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightsModelsTest {
    @Test
    fun anAnswerPlace_isTheIndexOfTheTurnInTheChat() {
        assertEquals("answer:0", HighlightWhere.answer(0))
        assertEquals("answer:12", HighlightWhere.answer(12))
        assertEquals(0, HighlightWhere.answerIndex("answer:0"))
        assertEquals(12, HighlightWhere.answerIndex(HighlightWhere.answer(12)))
    }

    @Test
    fun anythingElse_isNotAnAnswerPlace() {
        assertNull(HighlightWhere.answerIndex(HighlightWhere.STORY))
        assertNull(HighlightWhere.answerIndex(HighlightWhere.EXPLANATION))
        assertNull(HighlightWhere.answerIndex("answer:"))
        assertNull(HighlightWhere.answerIndex("answer:first"))
        assertNull(HighlightWhere.answerIndex("the answer:1"))
    }

    @Test
    fun aColor_keepsItsNameOnTheWire_andAnUnknownOneIsYellow() {
        HighlightColor.entries.forEach { color ->
            assertEquals(color, HighlightColor.fromWire(color.wire))
        }

        assertEquals(HighlightColor.YELLOW, HighlightColor.fromWire(""))
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromWire("Green"))
    }

    @Test
    fun withColor_changesOnlyTheColor() {
        val highlight = Highlight(
            id = "h1",
            text = "a passage",
            color = "yellow",
            where = "answer:2",
            prefix = "before ",
            suffix = " after",
            created = 5,
        )

        assertEquals(highlight.copy(color = "pink"), highlight.withColor(HighlightColor.PINK))
        assertEquals(HighlightColor.PINK, highlight.withColor(HighlightColor.PINK).highlightColor)
    }
}
