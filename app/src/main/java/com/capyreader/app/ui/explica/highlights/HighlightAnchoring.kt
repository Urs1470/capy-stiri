package com.capyreader.app.ui.explica.highlights

import com.capyreader.app.ui.explica.Highlight
import com.jocmp.mallet.LinearArticle
import com.jocmp.mallet.LinearAudio
import com.jocmp.mallet.LinearBlockQuote
import com.jocmp.mallet.LinearElement
import com.jocmp.mallet.LinearImage
import com.jocmp.mallet.LinearListItem
import com.jocmp.mallet.LinearTable
import com.jocmp.mallet.LinearText
import com.jocmp.mallet.LinearVideo

/** A highlight laid on one paragraph of a block: characters [start] (inclusive) to [end] (exclusive). */
data class Placement(
    val highlightId: String,
    val paragraph: Int,
    val start: Int,
    val end: Int,
)

/** What a new highlight stores: the passage and up to [HighlightAnchoring.MAX_CONTEXT] characters around it. */
data class Captured(
    val text: String,
    val prefix: String,
    val suffix: String,
)

sealed interface Capture {
    data class Passage(val captured: Captured) : Capture

    /** Nothing readable was selected: empty, blank or only list markers. */
    data object Empty : Capture

    /** More than [HighlightAnchoring.MAX_PASSAGE] characters. */
    data object TooLong : Capture
}

/**
 * Ties a highlight to the text of a block (the story, the explanation or one answer) without knowing where
 * the reader's selection was: the selection only hands over the words, so a highlight is its `text` plus
 * the text before and after it (`prefix`, `suffix`), the way a web annotation quotes a passage.
 *
 * The text of a block is its paragraphs joined by a line break. Whitespace is never compared: any run of
 * spaces or breaks is one separator, so a passage that crosses a paragraph is stored on one line and still
 * found, and a server that trims the context does no harm.
 */
object HighlightAnchoring {
    const val MAX_CONTEXT = 60
    const val MAX_PASSAGE = 1_000

    private val LIST_MARKER = Regex("""•|\d+\.""")

    /**
     * The [Captured] of a selection: [selectedPieces] are the selected parts of the paragraphs, in order
     * (one piece per paragraph the selection touches, list markers included). The context is stored only when
     * the words occur once in the block; when they occur several times nothing says which one was selected, so
     * none is stored and [place] finds them all; when they occur nowhere (the title of the story, say) none
     * can be stored either.
     */
    fun capture(paragraphs: List<String>, selectedPieces: List<String>): Capture {
        val words = words(selectedPieces.filterNot { it.isBlank() || isListMarker(it) }.joinToString(" "))

        if (words.isEmpty()) {
            return Capture.Empty
        }

        val text = words.joinToString(" ")

        if (text.length > MAX_PASSAGE) {
            return Capture.TooLong
        }

        val flat = paragraphs.joinToString("\n")
        val found = findAll(flat, words)

        if (found.size != 1) {
            return Capture.Passage(Captured(text = text, prefix = "", suffix = ""))
        }

        val context = flat.spaced()
        val span = found.single()

        return Capture.Passage(
            Captured(
                text = text,
                prefix = contextBefore(context, span.start),
                suffix = contextAfter(context, span.end),
            )
        )
    }

    /**
     * Where each of [highlights] (oldest first) sits in [paragraphs]: one [Placement] per paragraph it touches,
     * in the order of the highlights, so a newer one is drawn over an older one. A highlight whose text isn't
     * in the block has no placement. When its text occurs several times the occurrences whose surrounding text
     * matches the stored prefix and suffix best win; if the context doesn't tell them apart, those that start
     * and end at word boundaries win over those inside a longer word, and what still ties is placed everywhere.
     */
    fun place(paragraphs: List<String>, highlights: List<Highlight>): List<Placement> {
        if (paragraphs.isEmpty() || highlights.isEmpty()) {
            return emptyList()
        }

        val flat = paragraphs.joinToString("\n")
        val context = flat.spaced()
        val starts = paragraphStarts(paragraphs)

        return highlights.flatMap { highlight ->
            val words = words(highlight.text)

            if (words.isEmpty()) {
                emptyList()
            } else {
                bestMatches(flat, context, findAll(flat, words), highlight)
                    .flatMap { span -> split(highlight.id, span, paragraphs, starts) }
            }
        }
    }

    private data class Span(val start: Int, val end: Int)

    private fun isListMarker(piece: String) = LIST_MARKER.matches(piece.trim())

    private fun words(text: String): List<String> = buildList {
        var begin = -1

        text.forEachIndexed { index, char ->
            if (char.isWhitespace()) {
                if (begin >= 0) {
                    add(text.substring(begin, index))
                    begin = -1
                }
            } else if (begin < 0) {
                begin = index
            }
        }

        if (begin >= 0) {
            add(text.substring(begin))
        }
    }

    /**
     * Every place in [flat] where [words] follow each other, parted only by whitespace. Places that overlap count
     * as two: in "a a a" the words "a a" are in two places, and nothing says which of them was selected.
     */
    private fun findAll(flat: String, words: List<String>): List<Span> {
        if (words.isEmpty()) {
            return emptyList()
        }

        val found = mutableListOf<Span>()
        var from = 0

        while (from <= flat.length) {
            val begin = flat.indexOf(words.first(), from)

            if (begin < 0) {
                break
            }

            val end = matchFrom(flat, begin, words)

            if (end >= 0) {
                found += Span(begin, end)
            }

            from = begin + 1
        }

        return found
    }

    /** The end of the match that starts at [begin], or -1. */
    private fun matchFrom(flat: String, begin: Int, words: List<String>): Int {
        var position = begin

        words.forEachIndexed { index, word ->
            if (index > 0) {
                val gap = position

                while (position < flat.length && flat[position].isWhitespace()) {
                    position++
                }

                if (position == gap) {
                    return -1
                }
            }

            if (!flat.startsWith(word, position)) {
                return -1
            }

            position += word.length
        }

        return position
    }

    private fun bestMatches(flat: String, context: String, spans: List<Span>, highlight: Highlight): List<Span> {
        if (spans.size <= 1) {
            return spans
        }

        val scores = spans.map { span ->
            contextScore(context, span, highlight) * BOUNDARY_LEVELS + boundaryScore(flat, span)
        }
        val best = scores.max()

        return spans.filterIndexed { index, _ -> scores[index] == best }
    }

    /** How many characters around [span] agree with the stored context, nearest characters first. */
    private fun contextScore(context: String, span: Span, highlight: Highlight): Int {
        val prefix = highlight.prefix.spaced().trimEnd()
        val suffix = highlight.suffix.spaced().trimStart()

        if (prefix.isEmpty() && suffix.isEmpty()) {
            return 0
        }

        var before = span.start

        while (before > 0 && context[before - 1] == ' ') {
            before--
        }

        var after = span.end

        while (after < context.length && context[after] == ' ') {
            after++
        }

        return commonSuffixLength(context, before, prefix) + commonPrefixLength(context, after, suffix)
    }

    /** 0 to 2: one for each end of [span] that doesn't cut through a word. */
    private fun boundaryScore(flat: String, span: Span): Int {
        val startsClean = span.start == 0 ||
            !(flat[span.start - 1].isLetterOrDigit() && flat[span.start].isLetterOrDigit())
        val endsClean = span.end == flat.length ||
            !(flat[span.end - 1].isLetterOrDigit() && flat[span.end].isLetterOrDigit())

        return (if (startsClean) 1 else 0) + (if (endsClean) 1 else 0)
    }

    private fun commonSuffixLength(context: String, end: Int, prefix: String): Int {
        var length = 0

        while (length < prefix.length &&
            length < end &&
            context[end - 1 - length] == prefix[prefix.length - 1 - length]
        ) {
            length++
        }

        return length
    }

    private fun commonPrefixLength(context: String, begin: Int, suffix: String): Int {
        var length = 0

        while (length < suffix.length &&
            begin + length < context.length &&
            context[begin + length] == suffix[length]
        ) {
            length++
        }

        return length
    }

    private fun paragraphStarts(paragraphs: List<String>): IntArray {
        val starts = IntArray(paragraphs.size)
        var offset = 0

        paragraphs.forEachIndexed { index, paragraph ->
            starts[index] = offset
            offset += paragraph.length + 1
        }

        return starts
    }

    /** The part of [span] that falls in each paragraph it touches; the line break between paragraphs belongs to none. */
    private fun split(id: String, span: Span, paragraphs: List<String>, starts: IntArray): List<Placement> {
        return buildList {
            paragraphs.forEachIndexed { index, paragraph ->
                val from = maxOf(span.start, starts[index])
                val to = minOf(span.end, starts[index] + paragraph.length)

                if (from < to) {
                    add(Placement(highlightId = id, paragraph = index, start = from - starts[index], end = to - starts[index]))
                }
            }
        }
    }

    /** Up to [MAX_CONTEXT] characters before [start], without starting in the middle of a surrogate pair. */
    private fun contextBefore(context: String, start: Int): String {
        var from = maxOf(0, start - MAX_CONTEXT)

        if (from > 0 && context[from].isLowSurrogate() && context[from - 1].isHighSurrogate()) {
            from++
        }

        return context.substring(from, start)
    }

    /** Up to [MAX_CONTEXT] characters after [end], without ending in the middle of a surrogate pair. */
    private fun contextAfter(context: String, end: Int): String {
        var to = minOf(context.length, end + MAX_CONTEXT)

        if (to < context.length && to > end && context[to - 1].isHighSurrogate() && context[to].isLowSurrogate()) {
            to--
        }

        return context.substring(end, to)
    }

    /** The same text with every whitespace character as a plain space, so offsets stay where they were. */
    private fun String.spaced(): String {
        val out = CharArray(length)

        forEachIndexed { index, char -> out[index] = if (char.isWhitespace()) ' ' else char }

        return String(out)
    }

    private const val BOUNDARY_LEVELS = 3
}

/**
 * The text elements of the article in reading order, nested ones included: what the reader selects from.
 * Images, video and audio have no selectable text.
 */
fun LinearArticle.paragraphs(): List<LinearText> = buildList {
    elements.forEach { collectParagraphs(it, this) }
}

private fun collectParagraphs(element: LinearElement, into: MutableList<LinearText>) {
    when (element) {
        is LinearText -> into.add(element)
        is LinearBlockQuote -> element.content.forEach { collectParagraphs(it, into) }
        is LinearListItem -> element.content.forEach { collectParagraphs(it, into) }
        is LinearTable -> element.cells.entries
            .sortedWith(compareBy({ it.key.row }, { it.key.col }))
            .filterNot { it.value.isFiller }
            .forEach { cell -> cell.value.content.forEach { collectParagraphs(it, into) } }

        is LinearImage, is LinearVideo, is LinearAudio -> Unit
    }
}
