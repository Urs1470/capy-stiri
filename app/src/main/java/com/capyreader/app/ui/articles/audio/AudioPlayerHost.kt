package com.capyreader.app.ui.articles.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject

/**
 * Draws the full player once for the whole app, over the list and over the reader alike, while the
 * controller says it is expanded and an episode is loaded. Only call it where an account exists:
 * the controller lives in the account modules.
 */
@Composable
fun AudioPlayerHost(
    onOpenArticle: (articleID: String) -> Unit,
) {
    val controller: AudioPlayerController = koinInject()
    val isExpanded by controller.isExpanded.collectAsState()
    val audio by controller.currentAudio.collectAsState()
    val player by controller.player.collectAsState()

    val current = audio
    val currentPlayer = player

    if (isExpanded && current != null && currentPlayer != null) {
        AudioFullPlayer(
            player = currentPlayer,
            audio = current,
            onOpenArticle = {
                controller.collapse()
                onOpenArticle(current.articleID)
            },
            onCloseAudio = { controller.dismiss() },
            onDismissRequest = { controller.collapse() },
        )
    }
}
