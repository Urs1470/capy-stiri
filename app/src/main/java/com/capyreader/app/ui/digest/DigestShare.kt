package com.capyreader.app.ui.digest

import com.capyreader.app.BuildConfig
import com.jocmp.capy.Article

/** Whether a story comes from the daily digest: its feed is one of the digest's section feeds. */
fun isDigestStory(feedURL: String?, feedPrefix: String = BuildConfig.EXPLICA_FEED_PREFIX): Boolean {
    return feedURL?.startsWith(feedPrefix) == true
}

/**
 * What sharing a story of the digest sends: the story itself, because the digest's own words are the story and
 * the link of its first source is only where it came from. Null for any other article, which is shared as a link,
 * as upstream does. The text comes from the feed's own content, also when the reader has loaded the full content.
 */
fun digestShareText(article: Article, feedPrefix: String = BuildConfig.EXPLICA_FEED_PREFIX): String? {
    if (!isDigestStory(article.feedURL, feedPrefix)) {
        return null
    }

    return storyText(
        title = article.title,
        html = article.defaultContent,
        link = article.url?.toString(),
    )
}

/**
 * The title on the first line, then the text of the story (its paragraphs, the "Ce înseamnă" quote and the source
 * line, without markup), then the link to the first source, unless the text already has it. Parts are separated
 * by a blank line; a part that is empty is left out.
 */
fun storyText(title: String, html: String, link: String?): String {
    val text = paragraphs(title.trim(), htmlToPlainText(html))
    val url = link?.trim().orEmpty()

    return if (text.contains(url)) text else paragraphs(text, url)
}

private fun paragraphs(vararg parts: String): String {
    return parts.filter { it.isNotEmpty() }.joinToString(separator = "\n\n")
}
