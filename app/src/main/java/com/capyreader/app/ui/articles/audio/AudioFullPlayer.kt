package com.capyreader.app.ui.articles.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.capyreader.app.R
import com.capyreader.app.common.AudioEnclosure

private const val CLOCK_TICK_MS = 1_000L

/**
 * The full player behind the mini player: the episode's title, a progress bar to drag,
 * the time, 30-second skips, play/pause, and a way back to the article.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFullPlayer(
    player: Player,
    audio: AudioEnclosure,
    onOpenArticle: () -> Unit,
    onCloseAudio: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = audio.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = audio.feedName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCloseAudio) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.audio_player_close),
                    )
                }
            }

            AudioSeekBar(player = player)

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SeekBackButton(player = player, modifier = Modifier.size(56.dp))
                PlayPauseButton(player = player, modifier = Modifier.size(72.dp))
                SeekForwardButton(player = player, modifier = Modifier.size(56.dp))
            }

            TextButton(onClick = onOpenArticle) {
                Text(text = stringResource(R.string.audio_player_open_article))
            }
        }
    }
}

/**
 * The bar and its two clocks. While a finger is on the bar the left clock shows where
 * it would land; the player only seeks on release, which is `ProgressSlider`'s own
 * behaviour. The clock ticks only while this is on screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun AudioSeekBar(player: Player) {
    val progress = rememberProgressStateWithTickInterval(player, CLOCK_TICK_MS)
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val durationMs = progress.durationMs
    val shownPositionMs = dragFraction
        ?.let { PlaybackProgress.positionAt(it, durationMs) }
        ?: progress.currentPositionMs

    Column {
        ProgressSlider(
            player = player,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = { dragFraction = null },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = PlaybackProgress.format(shownPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = PlaybackProgress.format(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
