package com.capyreader.app.common

import com.jocmp.capy.Article
import com.jocmp.capy.Enclosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.ZonedDateTime

class AudioLinkTest {
    private val episode = "https://stiri.example.invalid/explica/audio/tok/2026-11-09-full.mp3"

    private fun article(url: String?, enclosures: List<Enclosure> = emptyList()) = Article(
        id = "3012",
        feedID = "48",
        title = "Știri integral — 9 noiembrie 2026",
        author = null,
        contentHTML = "",
        url = url?.let(::URL),
        summary = "Republica Moldova, România, Economie",
        imageURL = null,
        updatedAt = ZonedDateTime.parse("2026-11-09T04:12:00Z"),
        publishedAt = ZonedDateTime.parse("2026-11-09T04:12:00Z"),
        read = false,
        starred = false,
        feedName = "Audio",
        enclosures = enclosures,
    )

    private fun mp3(url: String = episode) = Enclosure(URL(url), "audio/mpeg", 1350L, "")

    @Test
    fun audioFileUrls_byExtension_ignoringQueryAndCase() {
        assertTrue(isAudioFileUrl(episode))
        assertTrue(isAudioFileUrl("https://x.invalid/a/Episode.MP3?token=1#t=30"))
        assertTrue(isAudioFileUrl("https://x.invalid/podcast/ep.m4a"))
        assertFalse(isAudioFileUrl("https://x.invalid/stiri/2026/11/09/articol"))
        assertFalse(isAudioFileUrl("https://x.invalid/mp3"))
        assertFalse(isAudioFileUrl(null))
    }

    @Test
    fun newsEpisode_linkIsItsMp3_opensInTheAppPlayer() {
        // Miniflux feed 48, 2026-11-09: entry url and enclosure url are the same MP3; the title opened Chrome.
        val audio = article(episode, listOf(mp3())).linkedAudio()
        assertEquals(episode, audio?.url)
        assertEquals("3012", audio?.articleID)
        assertEquals("Audio", audio?.feedName)
        assertEquals(1350L, audio?.durationSeconds)
        assertNull(audio?.artworkUrl)
    }

    @Test
    fun listRow_withoutEnclosures_decidesByTheFileExtension() {
        assertEquals(episode, article(episode).linkedAudio()?.url)
    }

    @Test
    fun podcastWithAShowNotesPage_stillOpensThePageInTheBrowser() {
        val page = "https://podcast.example.invalid/episodes/42"
        assertNull(article(page, listOf(mp3("https://cdn.example.invalid/42.mp3"))).linkedAudio())
    }

    @Test
    fun articleWithoutLink_hasNoLinkedAudio() {
        assertNull(article(null, listOf(mp3())).linkedAudio())
    }
}
