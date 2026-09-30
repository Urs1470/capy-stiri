package com.capyreader.app.ui.digest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HtmlToPlainTextTest {
    /** A story the way the digest publishes it: context paragraphs, the "Ce înseamnă" quote, the source line. */
    private val digestStory =
        "<p>Guvernul a aprobat azi bugetul pe 2027. Deficitul ajunge la 5% din PIB.</p>" +
            "<p>Opoziția cere o dezbatere publică înainte de vot.</p>" +
            "<blockquote><p><strong>Ce înseamnă</strong></p>" +
            "<p>Taxele nu cresc anul viitor, dar investițiile scad.</p></blockquote>" +
            "<p><em>Surse: <a href=\"https://hotnews.ro/a\">HotNews</a>, <a href=\"https://bbc.com/b\">BBC</a></em></p>"

    @Test
    fun aDigestStory_keepsItsParagraphs_theQuote_andTheSourceLine() {
        assertEquals(
            """
            Guvernul a aprobat azi bugetul pe 2027. Deficitul ajunge la 5% din PIB.

            Opoziția cere o dezbatere publică înainte de vot.

            Ce înseamnă

            Taxele nu cresc anul viitor, dar investițiile scad.

            Surse: HotNews, BBC
            """.trimIndent(),
            htmlToPlainText(digestStory),
        )
    }

    @Test
    fun aSingleSource_keepsItsLine() {
        val html = "<p>Text.</p><p><em>Sursă: <a href=\"https://ipn.md/a\">IPN</a></em></p>"

        assertEquals("Text.\n\nSursă: IPN", htmlToPlainText(html))
    }

    @Test
    fun noMarkupIsLeft_andALinkKeepsItsWordsNotItsAddress() {
        val text = htmlToPlainText(digestStory)

        assertFalse(text.contains("<"))
        assertFalse(text.contains(">"))
        assertFalse(text.contains("http"))
        assertFalse(text.contains("href"))
    }

    @Test
    fun entitiesAreDecoded_andWhitespaceIsCollapsed() {
        val html = "<p>  Tom &amp; Jerry   spun &quot;da&quot;\n   și &lt;nu&gt;  </p>"

        assertEquals("Tom & Jerry spun \"da\" și <nu>", htmlToPlainText(html))
    }

    @Test
    fun aLineBreakInsideAParagraphStaysALineBreak() {
        assertEquals("Prima linie\nA doua linie", htmlToPlainText("<p>Prima linie<br>A doua linie</p>"))
    }

    @Test
    fun headingsAreBlocksOfTheirOwn() {
        assertEquals(
            "Titlu\n\nText sub titlu.\n\nAl doilea titlu\n\nMai mult text.",
            htmlToPlainText("<h2>Titlu</h2><p>Text sub titlu.</p><h3>Al doilea titlu</h3><p>Mai mult text.</p>"),
        )
    }

    @Test
    fun emphasisIsDropped_butItsWordsStay() {
        assertEquals(
            "Premierul a spus că bugetul e gata.",
            htmlToPlainText("<p>Premierul a spus că <strong>bugetul</strong> e <em>gata</em>.</p>"),
        )
    }

    @Test
    fun aListKeepsItsItemsOnLinesOfTheirOwn() {
        assertEquals(
            "Ce s-a decis:\n\n• prima măsură\n• a doua măsură\n\nUrmează votul.",
            htmlToPlainText("<p>Ce s-a decis:</p><ul><li>prima măsură</li><li>a doua măsură</li></ul><p>Urmează votul.</p>"),
        )
    }

    @Test
    fun anOrderedListIsNumbered() {
        assertEquals(
            "1. unu\n2. doi\n3. trei",
            htmlToPlainText("<ol><li>unu</li><li>doi</li><li>trei</li></ol>"),
        )
    }

    @Test
    fun aQuoteThatIsNotTheDigestsKeepsItsParagraphs() {
        assertEquals(
            "Ministrul a declarat:\n\nPrima frază.\n\nA doua frază.",
            htmlToPlainText("<p>Ministrul a declarat:</p><blockquote><p>Prima frază.</p><p>A doua frază.</p></blockquote>"),
        )
    }

    @Test
    fun aTableIsOneLinePerRow() {
        val html = "<table><tr><th>An</th><th>PIB</th></tr><tr><td>2026</td><td>4%</td></tr></table>"

        assertEquals("An | PIB\n2026 | 4%", htmlToPlainText(html))
    }

    @Test
    fun anImageKeepsItsCaption_andNothingElse() {
        assertEquals(
            "Drona la Chișinău\n\nText.",
            htmlToPlainText("<img src=\"https://ipn.md/d.jpg\" alt=\"Drona la Chișinău\"><p>Text.</p>"),
        )
        assertEquals("Text.", htmlToPlainText("<img src=\"https://ipn.md/d.jpg\"><p>Text.</p>"))
    }

    @Test
    fun scriptsAndStylesAreNotText() {
        assertEquals(
            "Text.",
            htmlToPlainText("<style>p { color: red }</style><p>Text.</p><script>alert(1)</script>"),
        )
    }

    @Test
    fun plainTextWithoutTags_isTheTextItself() {
        assertEquals("Doar text, fără etichete.", htmlToPlainText("  Doar text, fără etichete.  "))
    }

    @Test
    fun blankInput_isNoText() {
        assertEquals("", htmlToPlainText(""))
        assertEquals("", htmlToPlainText("   \n  "))
        assertEquals("", htmlToPlainText("<p>   </p><p></p>"))
        assertEquals("", htmlToPlainText("<div><img src=\"https://ipn.md/d.jpg\"></div>"))
    }
}
