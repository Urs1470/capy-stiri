package com.capyreader.app.ui.explica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The four colors of Ion's reading system (`lectura`), which the vault exports into notes and Anki cards:
 * yellow is an idea worth keeping, green a fact or definition (it goes to Anki), blue an open question
 * and pink a quote. [wire] is the name the server stores.
 */
enum class HighlightColor(val wire: String) {
    YELLOW("yellow"),
    GREEN("green"),
    BLUE("blue"),
    PINK("pink");

    companion object {
        /** The server only sends the four names; anything else is drawn yellow instead of being lost. */
        fun fromWire(value: String): HighlightColor {
            return entries.firstOrNull { it.wire == value } ?: YELLOW
        }
    }
}

/** Where in a story a highlight lives: the story text, the AI explanation or the answer number `n` of the chat. */
object HighlightWhere {
    const val STORY = "story"
    const val EXPLANATION = "explanation"

    private const val ANSWER_PREFIX = "answer:"

    /** [index] is the position of the turn in the story's `chat`, counted from zero. */
    fun answer(index: Int) = "$ANSWER_PREFIX$index"

    fun answerIndex(where: String): Int? {
        if (!where.startsWith(ANSWER_PREFIX)) {
            return null
        }

        return where.removePrefix(ANSWER_PREFIX).toIntOrNull()
    }
}

/** One highlight, as `api/highlights` sends it; [created] is in unix seconds. */
@Serializable
data class Highlight(
    val id: String,
    val text: String,
    val color: String = HighlightColor.YELLOW.wire,
    val where: String = HighlightWhere.STORY,
    val prefix: String = "",
    val suffix: String = "",
    val created: Long = 0,
) {
    val highlightColor: HighlightColor
        get() = HighlightColor.fromWire(color)

    fun withColor(newColor: HighlightColor) = copy(color = newColor.wire)
}

/** `{"highlights": [...]}`, the answer to `op: list`. */
@Serializable
internal data class HighlightsResponse(
    val highlights: List<Highlight> = emptyList(),
)

/** `{"highlight": {...}}`, the answer to `op: add`. */
@Serializable
internal data class HighlightResponse(
    val highlight: Highlight? = null,
)

/**
 * One story of the Highlights page: its highlights (oldest first) and what the server copied of the story when the first
 * one was made. [entryId] is the Miniflux entry id, which is the article id in the app; [latest] is the newest
 * highlight, in unix seconds. The title, link and section are empty when the story was gone already.
 */
@Serializable
data class StoryHighlights(
    @SerialName("entry_id") val entryId: Long,
    val title: String = "",
    val link: String = "",
    val section: String = "",
    val latest: Long = 0,
    val highlights: List<Highlight> = emptyList(),
)

/** `{"stories": [...]}`, the answer to `op: all`, the story highlighted last first. */
@Serializable
internal data class AllHighlightsResponse(
    val stories: List<StoryHighlights> = emptyList(),
)

/** `{"ok": true}`, the answer to `op: remove`. */
@Serializable
internal data class OkResponse(
    val ok: Boolean = false,
)
