package com.capyreader.app.ui.articles.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayerControllerTest {
    private val episode = "https://example.invalid/audio/2026-11-09.mp3"

    @Test
    fun opensPlayerOnPlay_firstEpisode() {
        assertTrue(AudioPlayerController.opensPlayerOnPlay(currentUrl = null, newUrl = episode))
    }

    @Test
    fun opensPlayerOnPlay_differentEpisode() {
        assertTrue(
            AudioPlayerController.opensPlayerOnPlay(
                currentUrl = "https://example.invalid/audio/2026-11-08.mp3",
                newUrl = episode,
            )
        )
    }

    @Test
    fun opensPlayerOnPlay_sameEpisodeAfterPause_staysClosed() {
        assertFalse(AudioPlayerController.opensPlayerOnPlay(currentUrl = episode, newUrl = episode))
    }

    @Test
    fun opensPlayerOnPlay_sameEpisodeAfterStop_countsAsNew() {
        // dismiss() clears the current episode, so playing it again has nothing to match
        assertTrue(AudioPlayerController.opensPlayerOnPlay(currentUrl = null, newUrl = episode))
    }
}
