package com.capyreader.app.ui.articles.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {
    private val fortyMinutes = 40 * 60_000L

    @Test
    fun positionAt_middleOfBar() {
        assertEquals(20 * 60_000L, PlaybackProgress.positionAt(0.5f, fortyMinutes))
    }

    @Test
    fun positionAt_ends() {
        assertEquals(0L, PlaybackProgress.positionAt(0f, fortyMinutes))
        assertEquals(fortyMinutes, PlaybackProgress.positionAt(1f, fortyMinutes))
    }

    @Test
    fun positionAt_clampsOutOfRange() {
        assertEquals(0L, PlaybackProgress.positionAt(-0.2f, fortyMinutes))
        assertEquals(fortyMinutes, PlaybackProgress.positionAt(1.3f, fortyMinutes))
    }

    @Test
    fun positionAt_nanIsStart() {
        assertEquals(0L, PlaybackProgress.positionAt(Float.NaN, fortyMinutes))
    }

    @Test
    fun positionAt_unknownDuration_isStart() {
        assertEquals(0L, PlaybackProgress.positionAt(0.5f, C.TIME_UNSET))
        assertEquals(0L, PlaybackProgress.positionAt(0.5f, 0L))
    }

    @Test
    fun positionAt_longEpisodeKeepsMillisecondPrecision() {
        val threeHours = 3 * 3_600_000L

        assertEquals(threeHours / 4, PlaybackProgress.positionAt(0.25f, threeHours))
    }

    @Test
    fun remaining_isDurationMinusPosition() {
        assertEquals(10 * 60_000L, PlaybackProgress.remaining(30 * 60_000L, fortyMinutes))
    }

    @Test
    fun remaining_neverNegative() {
        assertEquals(0L, PlaybackProgress.remaining(fortyMinutes + 5_000L, fortyMinutes))
        assertEquals(fortyMinutes, PlaybackProgress.remaining(-5_000L, fortyMinutes))
    }

    @Test
    fun remaining_unknownDuration_isZero() {
        assertEquals(0L, PlaybackProgress.remaining(5_000L, C.TIME_UNSET))
    }

    @Test
    fun format_underAnHour() {
        assertEquals("0:00", PlaybackProgress.format(0L))
        assertEquals("0:09", PlaybackProgress.format(9_000L))
        assertEquals("12:34", PlaybackProgress.format(12 * 60_000L + 34_000L))
    }

    @Test
    fun format_fromOneHour() {
        assertEquals("1:00:00", PlaybackProgress.format(3_600_000L))
        assertEquals("1:05:07", PlaybackProgress.format(3_600_000L + 5 * 60_000L + 7_000L))
    }

    @Test
    fun format_roundsDownToTheSecond() {
        assertEquals("0:01", PlaybackProgress.format(1_999L))
    }

    @Test
    fun format_unknownAndNegative_readZero() {
        assertEquals("0:00", PlaybackProgress.format(C.TIME_UNSET))
        assertEquals("0:00", PlaybackProgress.format(-1_000L))
    }

    @Test
    fun hasDuration() {
        assertTrue(PlaybackProgress.hasDuration(1L))
        assertFalse(PlaybackProgress.hasDuration(0L))
        assertFalse(PlaybackProgress.hasDuration(C.TIME_UNSET))
    }
}
