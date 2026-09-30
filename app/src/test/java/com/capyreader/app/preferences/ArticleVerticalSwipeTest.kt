package com.capyreader.app.preferences

import com.capyreader.app.preferences.ArticleVerticalSwipe.DISABLED
import com.capyreader.app.preferences.ArticleVerticalSwipe.EXPLAIN_WITH_AI
import com.capyreader.app.preferences.ArticleVerticalSwipe.NEXT_ARTICLE
import com.capyreader.app.preferences.ArticleVerticalSwipe.PREVIOUS_ARTICLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The AI gesture is this fork's addition to the reader swipes: it is a choice in Settings, never the default.
class ArticleVerticalSwipeTest {
    @Test
    fun explainWithAi_isOfferedForBothSwipes() {
        assertTrue(EXPLAIN_WITH_AI in ArticleVerticalSwipe.topOptions)
        assertTrue(EXPLAIN_WITH_AI in ArticleVerticalSwipe.bottomOptions)
    }

    @Test
    fun explainWithAi_isNotTheDefault() {
        assertEquals(PREVIOUS_ARTICLE, ArticleVerticalSwipe.topSwipeDefault)
        assertEquals(NEXT_ARTICLE, ArticleVerticalSwipe.bottomSwipeDefault)
    }

    @Test
    fun explainWithAi_isEnabledButDoesNotOpenAnArticle() {
        assertTrue(EXPLAIN_WITH_AI.enabled)
        assertFalse(EXPLAIN_WITH_AI.openArticle)
    }

    @Test
    fun disabled_stillComesFirst() {
        assertEquals(DISABLED, ArticleVerticalSwipe.topOptions.first())
        assertEquals(DISABLED, ArticleVerticalSwipe.bottomOptions.first())
    }
}
