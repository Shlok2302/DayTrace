package com.example.voicerecorder

import com.example.voicerecorder.google.calendar.EventTimeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Days and times are read from the words, counting from the day of the
 * recording (Monday 21 September 2026 here), never from today.
 */
class EventTimeResolverTest {

    private val monday =
        LocalDate.of(2026, 9, 21)

    private fun day(words: String, on: LocalDate = monday) =
        EventTimeResolver.day(words, on)

    @Test
    fun relativeDaysCountFromTheRecording() {
        assertEquals(LocalDate.of(2026, 9, 22), day("tomorrow at 5 PM")?.date)
        assertEquals(LocalDate.of(2026, 9, 23), day("the day after tomorrow")?.date)
        assertEquals(monday, day("tonight at 8")?.date)
        assertEquals(LocalDate.of(2026, 9, 24), day("in three days")?.date)

        // Recorded late on a Sunday: "tomorrow" is the Monday, whatever day it is now.
        assertEquals(monday, day("tomorrow morning", LocalDate.of(2026, 9, 20))?.date)
    }

    @Test
    fun weekdays() {
        assertEquals(LocalDate.of(2026, 9, 25), day("on Friday")?.date)
        assertEquals(LocalDate.of(2026, 9, 25), day("this Friday")?.date)
        assertNull(day("on Friday")?.other)

        // "on Monday" said on a Monday: next week, but it could mean today.
        assertEquals(LocalDate.of(2026, 9, 28), day("on Monday")?.date)
        assertEquals(monday, day("on Monday")?.other)

        // Short names only after "on", "next"...
        assertEquals(LocalDate.of(2026, 9, 26), day("on sat")?.date)
        assertNull(day("we sat down and talked"))

        // A day that has passed is not an upcoming event's day.
        assertNull(day("last Friday"))
    }

    @Test
    fun nextTuesdayCanMeanTwoDays() {
        val next = day("next Tuesday")!!
        assertEquals(LocalDate.of(2026, 9, 22), next.date)
        assertEquals(LocalDate.of(2026, 9, 29), next.other)

        // Said on a Saturday, the coming Tuesday is already in next week.
        val fromSaturday = day("next Tuesday", LocalDate.of(2026, 9, 26))!!
        assertEquals(LocalDate.of(2026, 9, 29), fromSaturday.date)
        assertNull(fromSaturday.other)
    }

    @Test
    fun datesWithAMonth() {
        assertEquals(LocalDate.of(2026, 9, 25), day("on 25 September")?.date)
        assertEquals(LocalDate.of(2026, 9, 30), day("September 30th")?.date)
        assertEquals(LocalDate.of(2026, 10, 2), day("on the 2nd of Oct")?.date)
        // Already past this year: next year.
        assertEquals(LocalDate.of(2027, 9, 5), day("5 September")?.date)
        assertEquals(LocalDate.of(2028, 1, 3), day("3rd January 2028")?.date)
        assertNull(day("31 February"))
    }

    @Test
    fun dayOfTheMonth() {
        assertEquals(LocalDate.of(2026, 10, 5), day("on the 5th")?.date)
        assertEquals(LocalDate.of(2026, 9, 28), day("the 28th")?.date)
        // No 31st in September: the next month that has one.
        assertEquals(LocalDate.of(2026, 10, 31), day("on the 31st")?.date)
    }

    @Test
    fun wordsWithoutADayGiveNothing() {
        assertNull(day("on her birthday at 7 PM"))
        assertNull(day("sometime next week"))
        assertNull(day(""))
    }

    @Test
    fun clockTimes() {
        assertEquals(LocalTime.of(17, 0), EventTimeResolver.clock("tomorrow at 5 PM")?.time)
        assertEquals(LocalTime.of(17, 30), EventTimeResolver.clock("at 5:30 p.m.")?.time)
        assertEquals(LocalTime.of(9, 0), EventTimeResolver.clock("9am")?.time)
        assertEquals(LocalTime.of(0, 15), EventTimeResolver.clock("12:15 AM")?.time)
        assertEquals(LocalTime.of(17, 30), EventTimeResolver.clock("at 17:30")?.time)
        assertEquals(LocalTime.NOON, EventTimeResolver.clock("at noon")?.time)
        assertEquals(LocalTime.of(19, 0), EventTimeResolver.clock("at 7 in the evening")?.time)
        assertEquals(LocalTime.of(18, 30), EventTimeResolver.clock("half past six in the evening")?.time)
        assertEquals(LocalTime.of(8, 45), EventTimeResolver.clock("quarter to 9 in the morning")?.time)
        assertFalse(EventTimeResolver.clock("tomorrow at 5 PM")!!.halfGuessed)
        assertFalse(EventTimeResolver.clock("at 7 in the evening")!!.halfGuessed)
    }

    @Test
    fun aTimeWithoutAmOrPmIsFlagged() {
        val five = EventTimeResolver.clock("at 5")!!
        assertEquals(LocalTime.of(17, 0), five.time)
        assertTrue(five.halfGuessed)

        val ten = EventTimeResolver.clock("meet at 10")!!
        assertEquals(LocalTime.of(10, 0), ten.time)
        assertTrue(ten.halfGuessed)
    }

    @Test
    fun noClockTime() {
        assertNull(EventTimeResolver.clock("tomorrow evening"))
        assertNull(EventTimeResolver.clock("on the 5th"))
        assertNull(EventTimeResolver.clock("book a table for 5 to 6 people"))
        assertNull(EventTimeResolver.clock("25 September"))
        assertEquals("evening", EventTimeResolver.partOfDay("tomorrow evening"))
        assertNull(EventTimeResolver.partOfDay("tomorrow at 5 PM"))
    }

    @Test
    fun ranges() {
        val range = EventTimeResolver.range("from 5 to 6 PM")!!
        assertEquals(LocalTime.of(17, 0), range.first.time)
        assertEquals(LocalTime.of(18, 0), range.second.time)

        val acrossNoon = EventTimeResolver.range("11 to 1 pm")!!
        assertEquals(LocalTime.of(11, 0), acrossNoon.first.time)
        assertEquals(LocalTime.of(13, 0), acrossNoon.second.time)

        val withMinutes = EventTimeResolver.range("5-6:30 pm")!!
        assertEquals(LocalTime.of(18, 30), withMinutes.second.time)

        assertNull(EventTimeResolver.range("5 to 6 people"))
    }

    @Test
    fun lengths() {
        assertEquals(60, EventTimeResolver.durationMinutes("meeting for an hour"))
        assertEquals(30, EventTimeResolver.durationMinutes("for half an hour"))
        assertEquals(120, EventTimeResolver.durationMinutes("for two hours"))
        assertEquals(90, EventTimeResolver.durationMinutes("for an hour and a half"))
        assertEquals(30, EventTimeResolver.durationMinutes("a 30 minute call"))
        assertNull(EventTimeResolver.durationMinutes("meeting with Rahul tomorrow at 5 PM"))
    }
}
