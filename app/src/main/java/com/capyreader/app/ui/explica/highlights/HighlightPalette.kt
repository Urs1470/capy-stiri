package com.capyreader.app.ui.explica.highlights

import com.capyreader.app.ui.explica.HighlightColor
import kotlin.math.pow

/**
 * The background of a highlighted passage. The colors are opaque so the text keeps its contrast whatever the
 * surface is, and there is one set per theme mode: pastels behind dark text in the light themes, dim shades
 * behind light text in the dark ones. [contrastRatio] against the `onSurface` of every theme of the app is
 * checked in `HighlightPaletteTest` (at least 7:1, WCAG AAA).
 */
object HighlightPalette {
    fun argb(color: HighlightColor, dark: Boolean): Int {
        return if (dark) {
            when (color) {
                HighlightColor.YELLOW -> 0xFF564700.toInt()
                HighlightColor.GREEN -> 0xFF1F5226.toInt()
                HighlightColor.BLUE -> 0xFF164B73.toInt()
                HighlightColor.PINK -> 0xFF6E2A4A.toInt()
            }
        } else {
            when (color) {
                HighlightColor.YELLOW -> 0xFFFFF59D.toInt()
                HighlightColor.GREEN -> 0xFFC5E1A5.toInt()
                HighlightColor.BLUE -> 0xFFB3E5FC.toInt()
                HighlightColor.PINK -> 0xFFF8BBD0.toInt()
            }
        }
    }

    /** WCAG 2.x contrast ratio of two opaque colors, from 1 (the same) to 21 (black on white). */
    fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
        val first = luminance(firstArgb)
        val second = luminance(secondArgb)

        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        val red = channel((argb shr 16) and 0xFF)
        val green = channel((argb shr 8) and 0xFF)
        val blue = channel(argb and 0xFF)

        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun channel(value: Int): Double {
        val unit = value / 255.0

        return if (unit <= 0.03928) unit / 12.92 else ((unit + 0.055) / 1.055).pow(2.4)
    }
}
