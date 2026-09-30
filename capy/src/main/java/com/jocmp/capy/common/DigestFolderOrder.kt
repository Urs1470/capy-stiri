package com.jocmp.capy.common

import java.text.Normalizer

/**
 * The sections of the daily news digest (this fork), in the order they are read. Every section is a folder
 * (a Miniflux category). [DigestFolderOrder] puts these folders first, in this order, ahead of every other
 * folder, so this list is the one place that decides the order of the drawer, of the swipe up to the next
 * section and of "open next feed" after mark all read.
 *
 * "00 Azi" is the category of the day's stories and goes first.
 */
val DIGEST_SECTION_ORDER = listOf(
    "00 Azi",
    "Republica Moldova",
    "România",
    "Economie",
    "Bursă",
    "Geopolitică și știri globale",
    "AI",
    "Tehnologie — domeniul meu",
)

/**
 * Orders folder titles: the digest's sections first, in the order of [DIGEST_SECTION_ORDER], then every
 * other title alphabetically, ignoring case (how the drawer has always ordered folders).
 *
 * A title is a section when its words and the section's words start with one another, ignoring case,
 * diacritics and punctuation: "Geopolitică" is the long "Geopolitică și știri globale" (the digest's model
 * sometimes writes the short name), "România — local" is "România". Whole words only, so "AI" is the
 * section AI and "Aisle" is not.
 */
object DigestFolderOrder : Comparator<String> {
    // Declared before sectionWords: object properties are initialized in this order, and wordsOf uses both.
    private val marks = Regex("\\p{M}+")
    private val notWord = Regex("[^\\p{L}\\p{N}]+")

    private val sectionWords = DIGEST_SECTION_ORDER.map(::wordsOf)

    /** The position of [title] in [DIGEST_SECTION_ORDER], or null when it isn't one of the digest's sections. */
    fun rank(title: String): Int? {
        val words = wordsOf(title)

        if (words.isEmpty()) {
            return null
        }

        return sectionWords
            .indexOfFirst { section -> words.startsWith(section) || section.startsWith(words) }
            .takeIf { it >= 0 }
    }

    override fun compare(a: String, b: String): Int {
        val byRank = compareValues(rank(a) ?: NOT_A_SECTION, rank(b) ?: NOT_A_SECTION)

        return if (byRank != 0) byRank else String.CASE_INSENSITIVE_ORDER.compare(a, b)
    }

    /** Lowercase words without diacritics ("Bursă" is "bursa"), cut at anything that is not a letter or digit. */
    private fun wordsOf(text: String): List<String> {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase()
            .split(notWord)
            .filter { it.isNotEmpty() }
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        return size >= prefix.size && subList(0, prefix.size) == prefix
    }

    private const val NOT_A_SECTION = Int.MAX_VALUE
}
