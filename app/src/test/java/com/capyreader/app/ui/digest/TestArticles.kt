package com.capyreader.app.ui.digest

import com.jocmp.capy.Article
import java.net.URL
import java.time.ZonedDateTime

internal const val TEST_DIGEST_FEED = "https://news.iupif.org/sectiuni/economie.xml"
internal const val TEST_SOURCE_URL = "https://hotnews.ro/buget-2027"

/** A story the way the digest publishes it: context paragraphs, the "Ce înseamnă" quote, the source line. */
internal const val TEST_DIGEST_STORY =
    "<p>Guvernul a aprobat azi bugetul pe 2027.</p>" +
        "<p>Opoziția cere o dezbatere publică.</p>" +
        "<blockquote><p><strong>Ce înseamnă</strong></p><p>Taxele nu cresc anul viitor.</p></blockquote>" +
        "<p><em>Sursă: <a href=\"$TEST_SOURCE_URL\">HotNews</a></em></p>"

internal fun testArticle(
    title: String = "Bugetul pe 2027",
    contentHTML: String = TEST_DIGEST_STORY,
    content: String = contentHTML,
    url: String? = TEST_SOURCE_URL,
    feedURL: String? = TEST_DIGEST_FEED,
    summary: String = "Guvernul a aprobat azi bugetul pe 2027.",
) = Article(
    id = "1",
    feedID = "10",
    title = title,
    author = null,
    contentHTML = contentHTML,
    url = url?.let(::URL),
    summary = summary,
    imageURL = null,
    updatedAt = ZonedDateTime.parse("2026-09-30T08:00:00Z"),
    publishedAt = ZonedDateTime.parse("2026-09-30T08:00:00Z"),
    read = false,
    starred = false,
    feedURL = feedURL,
    content = content,
)
