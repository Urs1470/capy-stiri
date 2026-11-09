package com.capyreader.app.ui.articles

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import com.capyreader.app.common.AudioEnclosure

val LocalArticleActions = compositionLocalOf { ArticleActions() }

@Stable
data class ArticleActions(
    /** Opens the app's full player on an episode, where a list row would otherwise open its MP3 in the browser. */
    val openAudio: (audio: AudioEnclosure) -> Unit = {},
    val markRead: (articleID: String) -> Unit = {},
    val star: (articleID: String) -> Unit = {},
    val markUnread: (articleID: String) -> Unit = {},
    val unstar: (articleID: String) -> Unit = {},
    val saveExternally: (articleID: String, onComplete: (Result<Unit>) -> Unit) -> Unit = { _, _ -> },
    val saveForLater: (url: String, onComplete: (Result<Unit>) -> Unit) -> Unit = { _, _ -> },
    val showSaveForLater: Boolean = false,
)
