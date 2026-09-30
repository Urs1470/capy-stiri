package com.capyreader.app.preferences

import com.capyreader.app.R

enum class ArticleVerticalSwipe {
    DISABLED,
    PREVIOUS_ARTICLE,
    NEXT_ARTICLE,
    LOAD_FULL_CONTENT,
    OPEN_ARTICLE_IN_BROWSER,

    // This fork: opens the AI explainer and chat of the story (only where the Explain button shows).
    EXPLAIN_WITH_AI;

    val translationKey: Int
        get() = when (this) {
            DISABLED -> R.string.article_vertical_swipe_disabled
            PREVIOUS_ARTICLE -> R.string.article_vertical_swipe_previous_article
            NEXT_ARTICLE -> R.string.article_vertical_swipe_next_article
            LOAD_FULL_CONTENT -> R.string.article_vertical_swipe_full_content
            OPEN_ARTICLE_IN_BROWSER -> R.string.article_vertical_open_article_in_browser
            EXPLAIN_WITH_AI -> R.string.explica_swipe_option
        }

    val enabled: Boolean
        get() = this != DISABLED

    val openArticle: Boolean
        get() = this == PREVIOUS_ARTICLE || this == NEXT_ARTICLE

    companion object {
        val topOptions = listOf(
            DISABLED,
            LOAD_FULL_CONTENT,
            PREVIOUS_ARTICLE,
            EXPLAIN_WITH_AI,
        )

        val bottomOptions = listOf(
            DISABLED,
            OPEN_ARTICLE_IN_BROWSER,
            NEXT_ARTICLE,
            LOAD_FULL_CONTENT,
            EXPLAIN_WITH_AI,
        )

        val topSwipeDefault = PREVIOUS_ARTICLE

        val bottomSwipeDefault = NEXT_ARTICLE
    }
}
