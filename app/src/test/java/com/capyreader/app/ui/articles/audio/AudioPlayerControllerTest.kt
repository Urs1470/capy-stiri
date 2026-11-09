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

    @Test
    fun startsOnOpen_otherEpisode_starts() {
        assertTrue(
            AudioPlayerController.startsOnOpen(
                currentUrl = "https://example.invalid/audio/2026-11-08.mp3",
                newUrl = episode,
                connected = true,
            )
        )
    }

    @Test
    fun startsOnOpen_nothingLoaded_starts() {
        assertTrue(AudioPlayerController.startsOnOpen(currentUrl = null, newUrl = episode, connected = false))
    }

    @Test
    fun startsOnOpen_loadedEpisode_onlyShowsTheSheet() {
        // The title of the episode that is already playing or paused opens the sheet without touching playback.
        assertFalse(AudioPlayerController.startsOnOpen(currentUrl = episode, newUrl = episode, connected = true))
    }

    @Test
    fun startsOnOpen_loadedEpisodeButControllerGone_preparesItAgain() {
        assertTrue(AudioPlayerController.startsOnOpen(currentUrl = episode, newUrl = episode, connected = false))
    }
}
