package com.capyreader.app.ui.digest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestShareTest {
    private val prefix = "https://news.iupif.org/sectiuni/"
    private val source = TEST_SOURCE_URL

    @Test
    fun aDigestStory_isSharedAsText_titleFirst_thenTheStory_thenTheFirstSource() {
        assertEquals(
            """
            Bugetul pe 2027

            Guvernul a aprobat azi bugetul pe 2027.

            Opoziția cere o dezbatere publică.

            Ce înseamnă

            Taxele nu cresc anul viitor.

            Sursă: HotNews

            https://hotnews.ro/buget-2027
            """.trimIndent(),
            digestShareText(testArticle(), prefix),
        )
    }

    @Test
    fun theTitleIsTheFirstLine() {
        val text = digestShareText(testArticle(title = "  Bugetul pe 2027 "), prefix)

        assertEquals("Bugetul pe 2027", text!!.lines().first())
    }

    @Test
    fun theTextHasNoMarkup() {
        val text = digestShareText(testArticle(), prefix)!!

        assertFalse(text.contains("<"))
        assertFalse(text.contains("href"))
        assertFalse(text.contains("&amp;"))
    }

    @Test
    fun theSourceUrlIsNotAddedWhenTheTextAlreadyHasIt() {
        val html = "<p>Citește mai mult la $source și vezi detaliile.</p>"

        val text = digestShareText(testArticle(contentHTML = html), prefix)!!

        assertEquals("Bugetul pe 2027\n\nCitește mai mult la $source și vezi detaliile.", text)
        assertEquals(1, Regex(Regex.escape(source)).findAll(text).count())
    }

    @Test
    fun theSourceUrlIsNotAddedWhenTheTitleAlreadyHasIt() {
        val text = digestShareText(testArticle(title = "Vezi $source", contentHTML = "<p>Text.</p>"), prefix)!!

        assertEquals("Vezi $source\n\nText.", text)
    }

    @Test
    fun aStoryWithoutAUrl_isSharedWithoutOne() {
        assertEquals(
            "Bugetul pe 2027\n\nText.",
            digestShareText(testArticle(contentHTML = "<p>Text.</p>", url = null), prefix),
        )
    }

    @Test
    fun aStoryWithoutText_isSharedAsTitleAndLink() {
        assertEquals(
            "Bugetul pe 2027\n\n$source",
            digestShareText(testArticle(contentHTML = "", summary = ""), prefix),
        )
    }

    @Test
    fun whenTheContentIsEmpty_theSummaryIsTheText() {
        assertEquals(
            "Bugetul pe 2027\n\nRezumatul știrii.\n\n$source",
            digestShareText(testArticle(contentHTML = "", summary = "Rezumatul știrii."), prefix),
        )
    }

    @Test
    fun theFeedsOwnContentIsShared_notTheFullContentTheReaderLoaded() {
        val fullContent = "<p>Tot articolul sursei, cu sute de cuvinte.</p>"

        val text = digestShareText(testArticle(content = fullContent), prefix)!!

        assertTrue(text.contains("Guvernul a aprobat azi bugetul pe 2027."))
        assertFalse(text.contains("Tot articolul sursei"))
    }

    @Test
    fun anyOtherArticleIsNotSharedAsText() {
        assertNull(digestShareText(testArticle(feedURL = "https://hotnews.ro/rss"), prefix))
        assertNull(digestShareText(testArticle(feedURL = null), prefix))
        assertNull(digestShareText(testArticle(feedURL = "https://news.iupif.org/alt-feed.xml"), prefix))
    }

    @Test
    fun isDigestStory_isTheFeedPrefix() {
        assertTrue(isDigestStory(TEST_DIGEST_FEED, prefix))
        assertFalse(isDigestStory("https://hotnews.ro/rss", prefix))
        assertFalse(isDigestStory(null, prefix))
    }

    @Test
    fun storyText_separatesThePartsWithABlankLine_andLeavesOutEmptyOnes() {
        assertEquals("Titlu\n\nText.\n\nhttps://a.ro/x", storyText("Titlu", "<p>Text.</p>", "https://a.ro/x"))
        assertEquals("Titlu\n\nhttps://a.ro/x", storyText("Titlu", "", "https://a.ro/x"))
        assertEquals("Titlu\n\nText.", storyText("Titlu", "<p>Text.</p>", null))
        assertEquals("Titlu\n\nText.", storyText("Titlu", "<p>Text.</p>", "  "))
        assertEquals("Titlu", storyText("Titlu", "", null))
    }
}
