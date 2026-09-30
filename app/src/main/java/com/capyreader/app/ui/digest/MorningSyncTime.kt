package com.capyreader.app.ui.digest

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The time of day, local to the phone, at which the app syncs by itself to bring in the digest (this fork). The digest
 * is published about 06:10 and this leaves it ten minutes. The one place to change it.
 */
val MORNING_SYNC_TIME: LocalTime = LocalTime.of(6, 20)

/**
 * The next morning sync: the first time [at] on the clock of [zone] that is strictly after [now], so a sync that
 * runs at exactly that time, or late, is followed by the one of the next day and never by itself.
 *
 * It is worked out on the calendar of [zone] and not by adding 24 hours, so a change of summer time moves the
 * distance between two syncs (23 or 25 hours) and not the time of day. A time that doesn't exist on that day (it falls
 * in the hour that summer time skips) is taken as the same distance after the gap, and one that occurs twice as its
 * first occurrence; neither can happen at 06:20 in any zone that changes its clock at night.
 */
fun nextMorningSync(now: Instant, zone: ZoneId, at: LocalTime = MORNING_SYNC_TIME): ZonedDateTime {
    val local = now.atZone(zone)
    val today = ZonedDateTime.of(local.toLocalDate(), at, zone)

    if (today.isAfter(local)) {
        return today
    }

    return ZonedDateTime.of(local.toLocalDate().plusDays(1), at, zone)
}
