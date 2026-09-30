package com.capyreader.app.ui.explica.highlights

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightColor
import com.jocmp.mallet.LinearText
import java.util.IdentityHashMap

/** One highlight on one paragraph, ready to draw: the characters from [start] until [end] get the background of [color]. */
@Immutable
data class HighlightSpan(
    val highlightId: String,
    val start: Int,
    val end: Int,
    val color: HighlightColor,
)

/**
 * The highlights of one paragraph: draws them into its text ([styled]) and finds the one under a tap.
 * The paragraph of the reader ([com.capyreader.app.ui.articles.reader.TextElement]) reads it from
 * [LocalHighlightSpans], which is `null` (nothing is drawn or tapped) everywhere but in a [HighlightScope].
 */
@Stable
class ParagraphHighlights internal constructor(
    val spans: List<HighlightSpan>,
    private val dark: Boolean,
    private val onTap: (highlightId: String) -> Unit,
) {
    /** [text] with a background behind every highlighted passage; a newer highlight paints over an older one. */
    fun styled(text: AnnotatedString): AnnotatedString {
        return buildAnnotatedString {
            append(text)

            spans.forEach { span ->
                val start = span.start.coerceIn(0, text.length)
                val end = span.end.coerceIn(0, text.length)

                if (start < end) {
                    addStyle(
                        style = SpanStyle(background = Color(HighlightPalette.argb(span.color, dark))),
                        start = start,
                        end = end,
                    )
                }
            }
        }
    }

    /**
     * The highlight at the gap [offset] between two characters; the newest one when several are there. A highlight
     * that starts or ends at the gap counts, because a finger is wider than a character and lands on the nearest gap.
     */
    fun highlightIdNear(offset: Int): String? {
        return spans.lastOrNull { offset >= it.start && offset <= it.end }?.highlightId
    }

    /** Tells the scope which highlight was tapped, if the tap landed on one. */
    fun tapAt(layout: TextLayoutResult?, position: Offset) {
        val result = layout ?: return
        val line = result.getLineForVerticalPosition(position.y)

        // Beside the text, where a line ends short, there is nothing to tap (as for links).
        if (position.x < result.getLineLeft(line) || position.x > result.getLineRight(line)) {
            return
        }

        highlightIdNear(result.getOffsetForPosition(position))?.let(onTap)
    }
}

/** The paragraphs of a block that carry highlights, with what they need to draw and to react to a tap. */
@Stable
class HighlightSpans private constructor(
    private val byParagraph: IdentityHashMap<LinearText, ParagraphHighlights>,
) {
    fun forParagraph(text: LinearText): ParagraphHighlights? = byParagraph[text]

    companion object {
        /**
         * Finds [highlights] in [paragraphs] ([HighlightAnchoring.place]) and groups them by paragraph. The lookup is
         * by identity: two paragraphs with the same words are two elements, and only the right one is highlighted.
         */
        fun resolve(
            paragraphs: List<LinearText>,
            highlights: List<Highlight>,
            dark: Boolean,
            onTap: (highlightId: String) -> Unit,
        ): HighlightSpans {
            val colors = highlights.associate { it.id to it.highlightColor }
            val placements = HighlightAnchoring.place(paragraphs.map { it.text }, highlights)
            val byParagraph = IdentityHashMap<LinearText, ParagraphHighlights>()

            placements
                .groupBy { it.paragraph }
                .forEach { (index, inParagraph) ->
                    val spans = inParagraph.map { placement ->
                        HighlightSpan(
                            highlightId = placement.highlightId,
                            start = placement.start,
                            end = placement.end,
                            color = colors.getValue(placement.highlightId),
                        )
                    }

                    byParagraph[paragraphs[index]] = ParagraphHighlights(spans, dark, onTap)
                }

            return HighlightSpans(byParagraph)
        }
    }
}

/** `null` outside a [HighlightScope]: the reader draws its text as upstream does. */
val LocalHighlightSpans = compositionLocalOf<HighlightSpans?> { null }

/**
 * Makes a tap on a highlighted passage of a paragraph open its actions; does nothing when [highlights] is `null`.
 *
 * This is not `detectTapGestures`: that consumes the press (`HighlightSelectionTest` checks it), and the selection
 * container takes up a long press only from a press nobody has consumed (its `awaitDown` waits for one), so a
 * paragraph with such a tap could no longer be selected by long press. Here nothing is consumed. A press that a
 * link, a scroll or a selection takes over has a consumed event or leaves the text, and is not a tap; one that
 * lasted as long as a long press is a selection, not a tap.
 */
fun Modifier.highlightTaps(
    highlights: ParagraphHighlights?,
    layout: () -> TextLayoutResult?,
): Modifier {
    if (highlights == null) {
        return this
    }

    return pointerInput(highlights) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture

            if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                highlights.tapAt(layout(), up.position)
            }
        }
    }
}
