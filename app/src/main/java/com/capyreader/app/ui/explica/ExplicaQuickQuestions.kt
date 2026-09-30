package com.capyreader.app.ui.explica

import androidx.annotation.StringRes
import com.capyreader.app.R

/**
 * The chips next to Explain. Each one is a fixed question, sent through the same `ask` as a typed one and
 * answered by the server in the language of the digest. The questions are in English and carry nothing personal:
 * this repository is public.
 */
enum class QuickQuestion(@StringRes val label: Int, val question: String) {
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
        fun notAskedIn(turns: List<ExplicaTurn>): List<QuickQuestion> {
            return entries.filter { quick -> turns.none { it.q == quick.question } }
        }
    }
}
