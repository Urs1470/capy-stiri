package com.capyreader.app.ui.digest

import com.jocmp.capy.Folder
import com.jocmp.capy.common.DIGEST_SECTION_ORDER
import com.jocmp.capy.common.DigestFolderOrder
import com.jocmp.capy.common.sortedByTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The comparator lives in :capy (where Account.folders sorts), but the tests of this fork run in :app.
class DigestFolderOrderTest {
    private fun order(vararg titles: String) =
        titles.toList().sortedWith(DigestFolderOrder)

    private fun foldersOrder(vararg titles: String) =
        titles.map { Folder(title = it) }.sortedByTitle().map { it.title }

    @Test
    fun theSectionsComeInTheDigestsOrder_whateverTheOrderTheyArriveIn() {
        val shuffled = listOf(
            "Tehnologie — domeniul meu",
            "Bursă",
            "AI",
            "Republica Moldova",
            "Geopolitică și știri globale",
            "00 Azi",
            "Economie",
            "România",
        )

        assertEquals(DIGEST_SECTION_ORDER, shuffled.sortedWith(DigestFolderOrder))
    }

    @Test
    fun theOrderListIsTheDigestsOrder() {
        assertEquals(
            listOf(
                "00 Azi",
                "Republica Moldova",
                "România",
                "Economie",
                "Bursă",
                "Geopolitică și știri globale",
                "AI",
                "Tehnologie — domeniul meu",
            ),
            DIGEST_SECTION_ORDER,
        )
    }

    @Test
    fun otherFoldersFollowTheSections_alphabetically_ignoringCase() {
        assertEquals(
            listOf("România", "AI", "apple", "Banana", "cherry", "Zebra"),
            order("Zebra", "cherry", "AI", "Banana", "apple", "România"),
        )
    }

    @Test
    fun foldersThatAreNotSectionsKeepTheirAlphabeticalOrder() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            order("cherry", "apple", "Banana"),
        )
    }

    @Test
    fun aTitleMatchesIgnoringCaseAndDiacritics() {
        assertEquals(1, DigestFolderOrder.rank("REPUBLICA MOLDOVA"))
        assertEquals(2, DigestFolderOrder.rank("romania"))
        assertEquals(2, DigestFolderOrder.rank("ROMÂNIA"))
        assertEquals(4, DigestFolderOrder.rank("bursa"))
        assertEquals(5, DigestFolderOrder.rank("GEOPOLITICA SI STIRI GLOBALE"))
        assertEquals(7, DigestFolderOrder.rank("tehnologie - domeniul meu"))
    }

    @Test
    fun theCedillaFormsOfSAndTMatchToo() {
        // ş and ţ (with cedilla) are what some keyboards and feeds write instead of ș and ț.
        assertEquals(5, DigestFolderOrder.rank("Geopolitică şi ştiri globale"))
        assertEquals(5, DigestFolderOrder.rank("Geopolitică și știri globale"))
        assertEquals(3, DigestFolderOrder.rank("Economie şi finanţe"))
    }

    @Test
    fun theShortNamesTheModelSometimesWritesMatchTheLongSections() {
        assertEquals(5, DigestFolderOrder.rank("Geopolitică"))
        assertEquals(7, DigestFolderOrder.rank("Tehnologie"))

        assertEquals(
            listOf("Economie", "Geopolitică", "AI", "Tehnologie"),
            order("Tehnologie", "AI", "Geopolitică", "Economie"),
        )
    }

    @Test
    fun theShortAndTheLongNameOfASectionSitNextToEachOther_theShortOneFirst() {
        assertEquals(
            listOf("AI", "Tehnologie", "Tehnologie — domeniul meu", "Alpha"),
            order("Alpha", "Tehnologie — domeniul meu", "Tehnologie", "AI"),
        )
    }

    @Test
    fun aTitleThatStartsWithASectionAndGoesOnIsThatSection() {
        assertEquals(2, DigestFolderOrder.rank("România — local"))
        assertEquals(3, DigestFolderOrder.rank("Economie și finanțe"))
        assertEquals(6, DigestFolderOrder.rank("AI și robotică"))
        assertEquals(6, DigestFolderOrder.rank("AI: știri"))
    }

    @Test
    fun aiMatchesTheWholeWordOnly() {
        assertEquals(6, DigestFolderOrder.rank("AI"))
        assertEquals(6, DigestFolderOrder.rank("ai"))
        assertEquals(6, DigestFolderOrder.rank("Ai"))

        assertNull(DigestFolderOrder.rank("Aisle"))
        assertNull(DigestFolderOrder.rank("Aidan"))
        assertNull(DigestFolderOrder.rank("Airbus"))
        assertNull(DigestFolderOrder.rank("A"))
        assertNull(DigestFolderOrder.rank("Maine"))

        // AI is a section and comes first; the titles that only start like it follow, alphabetically.
        assertEquals(
            listOf("AI", "Aidan", "Airbus", "Aisle"),
            order("Aisle", "Aidan", "Airbus", "AI"),
        )
    }

    @Test
    fun aWordThatOnlyStartsLikeASectionIsNotThatSection() {
        assertNull(DigestFolderOrder.rank("Economiei"))
        assertNull(DigestFolderOrder.rank("Bursa2"))
        assertNull(DigestFolderOrder.rank("Romanian news"))
        assertNull(DigestFolderOrder.rank("Republic"))
    }

    @Test
    fun todayIsAlwaysFirst_evenBeforeAFolderThatSortsEarlierAlphabetically() {
        assertEquals(
            listOf("00 Azi", "Republica Moldova", "0", "00 Altceva"),
            order("Republica Moldova", "00 Altceva", "0", "00 Azi"),
        )
    }

    @Test
    fun aBlankTitleIsNotASection() {
        assertNull(DigestFolderOrder.rank(""))
        assertNull(DigestFolderOrder.rank("   "))
        assertNull(DigestFolderOrder.rank("—"))
    }

    @Test
    fun titlesThatDifferOnlyInCaseAreTied() {
        assertEquals(0, DigestFolderOrder.compare("apple", "APPLE"))
        assertEquals(0, DigestFolderOrder.compare("România", "ROMÂNIA"))
    }

    @Test
    fun sortedByTitle_ordersFoldersTheWayTheDrawerShowsThem() {
        assertEquals(
            listOf(
                "00 Azi",
                "Republica Moldova",
                "România",
                "Economie",
                "Bursă",
                "Geopolitică",
                "AI",
                "Tehnologie",
                "apple",
                "Zeta",
            ),
            foldersOrder(
                "Zeta",
                "Tehnologie",
                "apple",
                "AI",
                "Geopolitică",
                "Bursă",
                "Economie",
                "România",
                "Republica Moldova",
                "00 Azi",
            ),
        )
    }

    @Test
    fun sorting_givesTheSameResultTwice() {
        val once = listOf("b", "Tehnologie", "a", "AI", "România").map { Folder(title = it) }.sortedByTitle()

        assertEquals(once, once.sortedByTitle())
        assertEquals(listOf("România", "AI", "Tehnologie", "a", "b"), once.map { it.title })
    }
}
