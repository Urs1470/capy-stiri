package com.capyreader.app.common

import com.jocmp.capy.Article

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")

/** True when the link is an audio file itself, not a web page: the news audio feed links each entry to its MP3. */
fun isAudioFileUrl(url: String?): Boolean {
    val path = url?.substringBefore('#')?.substringBefore('?') ?: return false
    val name = path.substringAfterLast('/')
    if (!name.contains('.')) return false
    return name.substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS
}

/**
 * The episode this article's own link points at, so the title, "open externally" and "open in browser" play it
 * in the app instead of handing the MP3 to the browser (Ion, 2026-11-09: the title opened Chrome). Null when the
 * link is a web page, so a podcast's show-notes page still opens in the browser. A list row has no enclosures,
 * so there the file extension decides.
 */
fun Article.linkedAudio(): AudioEnclosure? {
    val link = url?.toString() ?: return null
    val enclosure = enclosures.firstOrNull { it.type.startsWith("audio/") && it.url.toString() == link }
    if (enclosure == null && !isAudioFileUrl(link)) return null
    return AudioEnclosure(
        articleID = id,
        url = enclosure?.url?.toString() ?: link,
        title = title,
        feedName = feedName,
        durationSeconds = enclosure?.itunesDurationSeconds,
        artworkUrl = enclosure?.itunesImage?.ifBlank { null },
    )
}
