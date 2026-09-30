package com.capyreader.app.ui.explica.highlights

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.theme.colorschemes.MonochromeColorScheme
import com.capyreader.app.ui.theme.colorschemes.NewsprintColorScheme
import com.capyreader.app.ui.theme.colorschemes.SunsetColorScheme
import com.capyreader.app.ui.theme.colorschemes.TachiyomiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightPaletteTest {
    /** The text of the reader is `onSurface`; these are the schemes the app can show, plus the Material baseline. */
    private val lightSchemes: Map<String, ColorScheme> = mapOf(
        "Tachiyomi" to TachiyomiColorScheme.getColorScheme(isDark = false, isAmoled = false),
        "Sunset" to SunsetColorScheme.getColorScheme(isDark = false, isAmoled = false),
        "Monochrome" to MonochromeColorScheme.getColorScheme(isDark = false, isAmoled = false),
        "Newsprint" to NewsprintColorScheme.getColorScheme(isDark = false, isAmoled = false),
        "Material baseline" to lightColorScheme(),
    )

    private val darkSchemes: Map<String, ColorScheme> = mapOf(
        "Tachiyomi" to TachiyomiColorScheme.getColorScheme(isDark = true, isAmoled = false),
        "Tachiyomi pure black" to TachiyomiColorScheme.getColorScheme(isDark = true, isAmoled = true),
        "Sunset" to SunsetColorScheme.getColorScheme(isDark = true, isAmoled = false),
        "Monochrome" to MonochromeColorScheme.getColorScheme(isDark = true, isAmoled = false),
        "Newsprint" to NewsprintColorScheme.getColorScheme(isDark = true, isAmoled = false),
        "Material baseline" to darkColorScheme(),
    )

    private fun lowestContrast(schemes: Map<String, ColorScheme>, dark: Boolean): Pair<Double, String> {
        return schemes.flatMap { (name, scheme) ->
            HighlightColor.entries.map { color ->
                val ratio = HighlightPalette.contrastRatio(scheme.onSurface.toArgb(), HighlightPalette.argb(color, dark))

                ratio to "$name / $color"
            }
        }.minBy { it.first }
    }

    @Test
    fun inTheLightThemes_theTextIsReadableOnEveryColor_atAAALevel() {
        val (ratio, where) = lowestContrast(lightSchemes, dark = false)

        assertTrue("lowest light contrast is $ratio at $where", ratio >= 7.0)
    }

    @Test
    fun inTheDarkThemes_theTextIsReadableOnEveryColor_atAAALevel() {
        val (ratio, where) = lowestContrast(darkSchemes, dark = true)

        assertTrue("lowest dark contrast is $ratio at $where", ratio >= 7.0)
    }

    @Test
    fun theFourColorsDiffer_inBothModes() {
        listOf(false, true).forEach { dark ->
            val colors = HighlightColor.entries.map { HighlightPalette.argb(it, dark) }

            assertEquals(4, colors.toSet().size)
        }
    }

    @Test
    fun contrastRatio_matchesTheWcagDefinition() {
        assertEquals(21.0, HighlightPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.001)
        assertEquals(1.0, HighlightPalette.contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 0.001)
        // #777777 on white is the classic 4.48:1
        assertEquals(4.48, HighlightPalette.contrastRatio(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun theColorsKeepTheNamesTheServerStores() {
        assertEquals(
            listOf("yellow", "green", "blue", "pink"),
            HighlightColor.entries.map { it.wire },
        )
        assertEquals(HighlightColor.PINK, HighlightColor.fromWire("pink"))
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromWire("purple"))
    }
}
