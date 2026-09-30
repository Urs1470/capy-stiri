package com.capyreader.app.ui.explica

import androidx.annotation.StringRes
import com.capyreader.app.R

/**
 * What the chips above the message field have in common: a label, and the fixed question they send through the
 * same `ask` as a typed one. The story's chips are [QuickQuestion], the day entry's are [DayQuestion].
 */
interface QuickChip {
    @get:StringRes
    val label: Int

    val question: String
}

/**
 * The chips next to Explain. Each one is a fixed question, sent through the same `ask` as a typed one and
 * answered by the server in the language of the digest. The questions are in English and carry nothing personal:
 * this repository is public.
 */
enum class QuickQuestion(@StringRes override val label: Int, override val question: String) : QuickChip {
    SUMMARY(
        label = R.string.explica_chip_summary,
        question = "Summarize this story in two sentences.",
    ),
    TERMS(
        label = R.string.explica_chip_terms,
        question = "Explain the technical terms and names in this story for a beginner.",
    ),
    WHY_IT_MATTERS(
        label = R.string.explica_chip_why,
        question = "Why does this matter for a reader in Moldova?",
    ),
    BACKGROUND(
        label = R.string.explica_chip_background,
        question = "What happened before this? Give the background as a short timeline.",
    );

    companion object {
        /**
         * The questions nobody has asked in this chat yet: a chip whose question is already in [turns] is
         * hidden, like a suggested question, so the chat never holds the same question twice from a chip.
         */
        fun notAskedIn(turns: List<ExplicaTurn>): List<QuickQuestion> = entries.notAskedIn(turns)
    }
}

/**
 * The chips of the day entry (the story of the feed "00 Azi"), which has no article and no explanation: the server
 * answers each question from all the other stories of the day. They take the place of the story's chips, and
 * like them carry nothing personal, because the repository is public.
 */
enum class DayQuestion(@StringRes override val label: Int, override val question: String) : QuickChip {
    TOP_STORIES(
        label = R.string.explica_chip_day_top,
        question = "What are the most important stories today, in order?",
    ),
    FOR_MOLDOVA(
        label = R.string.explica_chip_day_moldova,
        question = "Which of today's stories matter most for Moldova, and why?",
    ),
    ECONOMY_AND_MARKETS(
        label = R.string.explica_chip_day_economy,
        question = "What happened today in the economy and the markets, and what does it mean for savings?",
    ),
    WHAT_TO_WATCH(
        label = R.string.explica_chip_day_watch,
        question = "What should I follow in the coming days, based on today's stories?",
    );

    companion object {
        /** As [QuickQuestion.notAskedIn]: a chip whose question is already in [turns] is hidden. */
        fun notAskedIn(turns: List<ExplicaTurn>): List<DayQuestion> = entries.notAskedIn(turns)
    }
}

private fun <T : QuickChip> List<T>.notAskedIn(turns: List<ExplicaTurn>): List<T> {
    return filter { chip -> turns.none { it.q == chip.question } }
}
