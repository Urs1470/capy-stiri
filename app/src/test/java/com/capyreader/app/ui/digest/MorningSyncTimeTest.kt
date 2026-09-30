package com.capyreader.app.ui.digest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When the morning sync comes next: the local time of day, in any time zone and across summer time. */
class MorningSyncTimeTest {
    private val chisinau = ZoneId.of("Europe/Chisinau")

    private fun next(now: String, zone: ZoneId = chisinau, at: LocalTime = MORNING_SYNC_TIME) =
        nextMorningSync(Instant.parse(now), zone, at)

    @Test
    fun theTimeIsTwentyPastSix() {
        assertEquals(LocalTime.of(6, 20), MORNING_SYNC_TIME)
    }

    // around the time itself (Chisinau is on summer time, UTC+3, in October, so 06:20 there is 03:20 UTC)

    @Test
    fun justBefore_itIsToday() {
        assertEquals(Instant.parse("2026-10-01T03:20:00Z"), next("2026-10-01T03:19:59Z").toInstant())
        assertEquals(Instant.parse("2026-10-01T03:20:00Z"), next("2026-10-01T03:19:59.999Z").toInstant())
    }

    @Test
    fun atExactlyTheTime_itIsTomorrow_soARunNeverQueuesItself() {
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-01T03:20:00Z").toInstant())
    }

    @Test
    fun justAfter_itIsTomorrow() {
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-01T03:20:00.001Z").toInstant())
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-01T03:20:01Z").toInstant())
    }

    @Test
    fun aRunThatWasHeldUpForHours_isFollowedByTomorrowsAndNotByAnotherOfToday() {
        // 09:45 and 23:59 local: the day's 06:20 is long gone.
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-01T06:45:00Z").toInstant())
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-01T20:59:00Z").toInstant())
    }

    @Test
    fun aRunAfterMidnight_isStillTodays() {
        // 00:00 and 05:00 local on the 2nd.
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-01T21:00:00Z").toInstant())
        assertEquals(Instant.parse("2026-10-02T03:20:00Z"), next("2026-10-02T02:00:00Z").toInstant())
    }

    @Test
    fun theResultIsOnTheClockOfTheZoneItWasAskedFor() {
        val result = next("2026-10-01T03:19:59Z")

        assertEquals(chisinau, result.zone)
        assertEquals(LocalTime.of(6, 20), result.toLocalTime())
        assertEquals(LocalDate.of(2026, 10, 1), result.toLocalDate())
    }

    // time zones

    @Test
    fun theSameMoment_isAnotherMorningInAnotherZone() {
        val now = "2026-10-01T03:00:00Z"
        val zones = listOf(
            "Europe/Chisinau", "Asia/Kolkata", "America/New_York", "Pacific/Kiritimati", "Pacific/Auckland",
            "America/St_Johns", "UTC",
        ).map { ZoneId.of(it) }

        zones.forEach { zone ->
            val local = Instant.parse(now).atZone(zone)
            val expectedDay = if (local.toLocalTime().isBefore(LocalTime.of(6, 20))) {
                local.toLocalDate()
            } else {
                local.toLocalDate().plusDays(1)
            }

            val result = next(now, zone)

            assertEquals(zone.id, ZonedDateTime.of(expectedDay, LocalTime.of(6, 20), zone).toInstant(), result.toInstant())
            assertEquals(zone.id, LocalTime.of(6, 20), result.toLocalTime())
        }
    }

    @Test
    fun inNewYork_itIsTomorrowMorningsInstantNotChisinaus() {
        // 23:00 on the 30th in New York: the 06:20 that comes is on the 1st, at 10:20 UTC.
        assertEquals(Instant.parse("2026-10-01T10:20:00Z"), next("2026-10-01T03:00:00Z", ZoneId.of("America/New_York")).toInstant())
        // The same moment in Chisinau, where it is already 06:00: twenty minutes away.
        assertEquals(Instant.parse("2026-10-01T03:20:00Z"), next("2026-10-01T03:00:00Z").toInstant())
    }

    @Test
    fun aZoneThatIsNotOnTheHour_keepsTheLocalTime() {
        // Kolkata is UTC+5:30: 06:20 there is 00:50 UTC.
        assertEquals(
            Instant.parse("2026-10-02T00:50:00Z"),
            next("2026-10-01T03:00:00Z", ZoneId.of("Asia/Kolkata")).toInstant(),
        )
    }

    // summer time

    @Test
    fun whenTheClocksGoForward_theTimeOfDayStays_andTheDayIsShorter() {
        // Chisinau moves from 02:00 to 03:00 on 29 March 2026 (UTC+2 to UTC+3).
        val after = next("2026-03-28T04:30:00Z") // 06:30 on the 28th, after that day's run

        assertEquals(Instant.parse("2026-03-29T03:20:00Z"), after.toInstant())
        assertEquals(LocalTime.of(6, 20), after.toLocalTime())
        assertEquals(LocalDate.of(2026, 3, 29), after.toLocalDate())
        assertEquals(Duration.ofHours(22).plusMinutes(50), Duration.between(Instant.parse("2026-03-28T04:30:00Z"), after))
    }

    @Test
    fun whenTheClocksGoBack_theTimeOfDayStays_andTheDayIsLonger() {
        // Chisinau moves from 03:00 back to 02:00 on 25 October 2026 (UTC+3 to UTC+2).
        val after = next("2026-10-24T03:30:00Z") // 06:30 on the 24th, after that day's run

        assertEquals(Instant.parse("2026-10-25T04:20:00Z"), after.toInstant())
        assertEquals(LocalTime.of(6, 20), after.toLocalTime())
        assertEquals(LocalDate.of(2026, 10, 25), after.toLocalDate())
        assertEquals(Duration.ofHours(24).plusMinutes(50), Duration.between(Instant.parse("2026-10-24T03:30:00Z"), after))
    }

    @Test
    fun onTheDayTheClocksMove_theSyncOfThatMorningIsStillAtTwentyPastSix() {
        // 01:00 local on 29 March and 02:00 local on 25 October, before the change: the same morning, not the next.
        assertEquals(Instant.parse("2026-03-29T03:20:00Z"), next("2026-03-28T23:00:00Z").toInstant())
        assertEquals(Instant.parse("2026-10-25T04:20:00Z"), next("2026-10-24T23:00:00Z").toInstant())
    }

    @Test
    fun anotherSummerTimeRule_isFollowedToo() {
        val newYork = ZoneId.of("America/New_York")

        // 8 March 2026, 02:00 to 03:00 (UTC-5 to UTC-4); 1 November 2026, 02:00 back to 01:00 (UTC-4 to UTC-5).
        assertEquals(Instant.parse("2026-03-08T10:20:00Z"), next("2026-03-07T12:00:00Z", newYork).toInstant())
        assertEquals(Instant.parse("2026-11-01T11:20:00Z"), next("2026-10-31T12:00:00Z", newYork).toInstant())
    }

    @Test
    fun aTimeThatDoesNotExistThatDay_isTakenAfterTheGap() {
        // Only a made-up time falls in the hour the clocks skip: 02:30 on 29 March 2026 in Berlin.
        val berlin = ZoneId.of("Europe/Berlin")
        val result = next("2026-03-29T00:30:00Z", berlin, at = LocalTime.of(2, 30))

        assertEquals(LocalTime.of(3, 30), result.toLocalTime())
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), result.toInstant())
    }

    @Test
    fun aTimeThatOccursTwice_isTakenOnce_atItsFirstOccurrence() {
        // 02:30 on 25 October 2026 in Berlin happens at 00:30 UTC and again at 01:30 UTC.
        val berlin = ZoneId.of("Europe/Berlin")
        val first = next("2026-10-24T20:00:00Z", berlin, at = LocalTime.of(2, 30))
        val afterFirst = next("2026-10-25T00:45:00Z", berlin, at = LocalTime.of(2, 30))

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first.toInstant())
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"), afterFirst.toInstant())
    }

    // the whole year

    @Test
    fun atEveryMomentOfTheYear_itIsALaterTwentyPastSix_withinOneDayAndABit() {
        val zones = listOf("Europe/Chisinau", "Europe/Bucharest", "America/New_York", "Australia/Lord_Howe", "UTC")
            .map { ZoneId.of(it) }
        val start = Instant.parse("2026-01-01T00:00:00Z")

        zones.forEach { zone ->
            var now = start

            while (now.isBefore(Instant.parse("2027-01-02T00:00:00Z"))) {
                val result = nextMorningSync(now, zone)
                val wait = Duration.between(now, result)

                assertTrue("$zone at $now", wait.toMillis() > 0)
                assertTrue("$zone at $now: $wait", wait <= Duration.ofHours(25).plusMinutes(30))
                assertEquals("$zone at $now", LocalTime.of(6, 20), result.toLocalTime())

                now = now.plus(Duration.ofMinutes(97))
            }
        }
    }
}
