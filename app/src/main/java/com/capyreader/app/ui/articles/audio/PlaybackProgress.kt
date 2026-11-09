package com.capyreader.app.ui.articles.audio

import androidx.media3.common.C

/**
 * The arithmetic behind the full player's progress bar: where a drag lands, and how a
 * position is written. Kept apart from Compose so it can be tested on the JVM.
 */
internal object PlaybackProgress {
    /**
     * The position a drag to [fraction] (0 to 1 of the bar) means, in milliseconds.
     * Out-of-range fractions are clamped, and an unknown duration gives 0.
     */
    fun positionAt(fraction: Float, durationMs: Long): Long {
        if (!hasDuration(durationMs) || fraction.isNaN()) {
            return 0L
        }

        val clamped = fraction.coerceIn(0f, 1f)

        return (clamped.toDouble() * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /** The time left at [positionMs], never negative; 0 when the duration is unknown. */
    fun remaining(positionMs: Long, durationMs: Long): Long {
        if (!hasDuration(durationMs)) {
            return 0L
        }

        return (durationMs - positionMs.coerceIn(0L, durationMs)).coerceAtLeast(0L)
    }

    /**
     * "m:ss", or "h:mm:ss" from one hour. Whole seconds, rounded down, so the clock
     * never runs ahead of the sound. Unknown and negative times read "0:00".
     */
    fun format(timeMs: Long): String {
        val totalSeconds = if (timeMs == C.TIME_UNSET || timeMs < 0) 0L else timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun hasDuration(durationMs: Long): Boolean {
        return durationMs != C.TIME_UNSET && durationMs > 0
    }
}
