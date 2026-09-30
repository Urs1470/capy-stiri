package com.capyreader.app.ui.digest

import com.jocmp.mallet.LinearAudio
import com.jocmp.mallet.LinearBlockQuote
import com.jocmp.mallet.LinearElement
import com.jocmp.mallet.LinearImage
import com.jocmp.mallet.LinearListItem
import com.jocmp.mallet.LinearTable
import com.jocmp.mallet.LinearText
import com.jocmp.mallet.LinearVideo
import com.jocmp.mallet.Mallet

// Mallet resolves relative links against this; the text keeps only the words of a link, so it never shows.
private const val BASE_URL = "https://localhost/"

/**
 * The text of [html] without markup, for sharing: a paragraph, a heading or a paragraph of a quote is a block,
 * blocks are separated by a blank line, and the items of a list sit on lines of their own. A link keeps its
 * words, not its address. [Mallet] does the parsing, as it does for the articles the reader draws, so
 * entities, `br` and whitespace come out as they look on screen. Returns "" when there is no text.
 */
fun htmlToPlainText(html: String): String {
    if (html.isBlank()) {
        return ""
    }

    val article = Mallet.flatten(html, BASE_URL).getOrNull() ?: return ""

    return article.elements.toBlocks().joinToString(separator = "\n\n")
}

/** Consecutive list items make one block, so a list isn't pulled apart by blank lines. */
private fun List<LinearElement>.toBlocks(): List<String> {
    val blocks = mutableListOf<String>()
    val items = mutableListOf<String>()

    fun endList() {
        if (items.isNotEmpty()) {
            blocks += items.joinToString(separator = "\n")
            items.clear()
        }
    }

    forEach { element ->
        if (element is LinearListItem) {
            element.asLine()?.let { items += it }
        } else {
            endList()
            blocks += element.asBlocks()
        }
    }

    endList()

    return blocks
}

private fun LinearElement.asBlocks(): List<String> {
    return when (this) {
        is LinearText -> listOfNotNull(text.asBlock())
        is LinearBlockQuote -> content.toBlocks()
        is LinearImage -> listOfNotNull(caption?.text?.asBlock())
        is LinearTable -> listOfNotNull(asBlock())
        is LinearListItem -> listOfNotNull(asLine())
        is LinearAudio, is LinearVideo -> emptyList()
    }
}

private fun String.asBlock(): String? = trim().takeIf { it.isNotEmpty() }

/** "• text" or "1. text"; the lines of a nested list or a second paragraph of the item are indented under it. */
private fun LinearListItem.asLine(): String? {
    val marker = if (orderedIndex != null) "$orderedIndex. " else "• "
    val lines = content.toBlocks().flatMap { it.lines() }

    if (lines.isEmpty()) {
        return null
    }

    return lines.mapIndexed { index, line -> if (index == 0) marker + line else "  $line" }
        .joinToString(separator = "\n")
}

/** One line per row, the cells of a row separated by " | ". */
private fun LinearTable.asBlock(): String? {
    return (0 until rowCount)
        .mapNotNull { row ->
            (0 until colCount)
                .mapNotNull { col ->
                    cellAt(row, col)
                        ?.takeUnless { it.isFiller }
                        ?.content
                        ?.toBlocks()
                        ?.joinToString(separator = " ")
                        ?.takeIf { it.isNotEmpty() }
                }
                .joinToString(separator = " | ")
                .takeIf { it.isNotEmpty() }
        }
        .joinToString(separator = "\n")
        .takeIf { it.isNotEmpty() }
}
