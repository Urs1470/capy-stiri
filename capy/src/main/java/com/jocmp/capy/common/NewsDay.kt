package com.jocmp.capy.common

import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The hour the news day starts (this fork). The daily digest comes about 06:10, so from 04:00 local time Today is the
 * new day's digest, and until then it is still the evening's (Ion, 2026-10-01: Today showed stories of the day before,
 * because upstream's Today is the last 24 hours).
 */
const val NEWS_DAY_START_HOUR = 4

/** The start, in unix seconds, of the news day that [now] falls in: today at 04:00 in [zone], or yesterday's before 04:00. */
fun newsDayStart(now: OffsetDateTime, zone: ZoneId = ZoneId.systemDefault()): Long {
    val local = now.atZoneSameInstant(zone)
    val day = if (local.hour < NEWS_DAY_START_HOUR) local.toLocalDate().minusDays(1) else local.toLocalDate()

    return day.atTime(NEWS_DAY_START_HOUR, 0).atZone(zone).toEpochSecond()
}
