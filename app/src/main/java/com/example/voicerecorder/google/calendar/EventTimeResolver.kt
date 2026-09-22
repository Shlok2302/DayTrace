package com.example.voicerecorder.google.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.IsoFields

/**
 * Reads when an event is from the words that were said ("tomorrow at
 * 5 PM", "next Tuesday", "on the 5th"), counting from the day the
 * recording was made, never from today.
 *
 * It only reads what is really in the words. When the words allow two
 * readings it says so ([Day.other], [Clock.halfGuessed]); when it cannot
 * read them it returns null instead of guessing.
 */
object EventTimeResolver {

    /** A day. [other]: the words can also mean this day ("next Tuesday"). */
    data class Day(
        val date: LocalDate,
        val other: LocalDate? = null
    )

    /** A clock time. [halfGuessed]: no AM or PM was said ("at 5"); [time] is the likelier one. */
    data class Clock(
        val time: LocalTime,
        val halfGuessed: Boolean = false
    )

    // Day -------------------------------------------------------------------

    fun day(
        words: String,
        recordedOn: LocalDate
    ): Day? {

        val text =
            normalize(words)

        if (text.isEmpty()) {
            return null
        }

        dateWithMonth(text, recordedOn)?.let { return Day(it) }

        dayOfMonth(text, recordedOn)?.let { return Day(it) }

        if (Regex("""\bday after tomorrow\b""").containsMatchIn(text)) {
            return Day(recordedOn.plusDays(2))
        }

        if (Regex("""\b(tomorrow|tmrw|tomorow)\b""").containsMatchIn(text)) {
            return Day(recordedOn.plusDays(1))
        }

        Regex("""\bin (\d+|a|one|two|three|four|five|six|seven) days?\b""").find(text)?.let { match ->
            number(match.groupValues[1])?.let { return Day(recordedOn.plusDays(it.toLong())) }
        }

        weekday(text, recordedOn)?.let { return it }

        if (Regex("""\b(today|tonight|this (morning|afternoon|evening))\b""").containsMatchIn(text)) {
            return Day(recordedOn)
        }

        return null
    }

    /** "25 September", "25th of Sept 2026", "September 25", "Sept 25th, 2026" */
    private fun dateWithMonth(
        text: String,
        recordedOn: LocalDate
    ): LocalDate? {

        val month =
            MONTH_WORDS

        val dayFirst =
            Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($month)\b(?:,?\s+(\d{4}))?""").find(text)

        val monthFirst =
            Regex("""\b($month)\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4}))?""").find(text)

        val (day, monthName, year) =
            when {
                dayFirst != null -> Triple(dayFirst.groupValues[1], dayFirst.groupValues[2], dayFirst.groupValues[3])
                monthFirst != null -> Triple(monthFirst.groupValues[2], monthFirst.groupValues[1], monthFirst.groupValues[3])
                else -> return null
            }

        val monthNumber =
            monthNumber(monthName) ?: return null

        if (year.isNotEmpty()) {
            return runCatching { LocalDate.of(year.toInt(), monthNumber, day.toInt()) }.getOrNull()
        }

        // No year: this year, or next year if that day has already passed.
        val thisYear =
            runCatching { LocalDate.of(recordedOn.year, monthNumber, day.toInt()) }.getOrNull() ?: return null

        return if (thisYear.isBefore(recordedOn)) thisYear.plusYears(1) else thisYear
    }

    /** "on the 5th", "the 25th": this month, or next month if that day has passed. */
    private fun dayOfMonth(
        text: String,
        recordedOn: LocalDate
    ): LocalDate? {

        val match =
            Regex("""\b(?:on\s+)?the\s+(\d{1,2})(?:st|nd|rd|th)\b""").find(text)
                ?: Regex("""\bon\s+(\d{1,2})(?:st|nd|rd|th)\b""").find(text)
                ?: return null

        val day =
            match.groupValues[1].toInt()

        // The first month, from the recording's, that has this day and where it is not past.
        for (monthsAhead in 0L..2L) {

            val month =
                recordedOn.withDayOfMonth(1).plusMonths(monthsAhead)

            if (day > month.lengthOfMonth()) {
                continue
            }

            val date =
                month.withDayOfMonth(day)

            if (!date.isBefore(recordedOn)) {
                return date
            }
        }

        return null
    }

    private fun weekday(
        text: String,
        recordedOn: LocalDate
    ): Day? {

        // "last Friday" has passed: not the day of an upcoming event.
        if (Regex("""\blast\s+($FULL_WEEKDAYS|$SHORT_WEEKDAYS)\b""").containsMatchIn(text)) {
            return null
        }

        // Short names ("sat", "wed") only after a word such as "on", so "we sat down" is not Saturday.
        val match =
            Regex("""\b(?:(next|this|coming|on|by|before)\s+)?($FULL_WEEKDAYS)\b""").find(text)
                ?: Regex("""\b(next|this|coming|on|by|before)\s+($SHORT_WEEKDAYS)\b""").find(text)
                ?: return null

        val target =
            weekdayOf(match.groupValues[2]) ?: return null

        val ahead =
            ((target.value - recordedOn.dayOfWeek.value) + 7) % 7

        return when (match.groupValues[1]) {

            // "this Friday": the one this week, today included.
            "this" -> Day(recordedOn.plusDays(ahead.toLong()))

            "coming" -> Day(recordedOn.plusDays(if (ahead == 0) 7L else ahead.toLong()))

            // "next Tuesday" said on a Monday: tomorrow, or the Tuesday of next week?
            "next" -> {
                val nearest = recordedOn.plusDays(if (ahead == 0) 7L else ahead.toLong())
                if (sameWeek(nearest, recordedOn)) Day(nearest, other = nearest.plusDays(7)) else Day(nearest)
            }

            // "on Monday" said on a Monday: most likely next week, but it could be today.
            else ->
                if (ahead == 0) Day(recordedOn.plusDays(7), other = recordedOn) else Day(recordedOn.plusDays(ahead.toLong()))
        }
    }

    private fun sameWeek(
        a: LocalDate,
        b: LocalDate
    ): Boolean =
        a.get(IsoFields.WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_BASED_YEAR) &&
                a.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == b.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    // Time ------------------------------------------------------------------

    /** The start time in the words, or null when no clock time was said. */
    fun clock(
        words: String
    ): Clock? {

        val text =
            normalize(words)

        if (text.isEmpty()) {
            return null
        }

        range(text)?.let { return it.first }

        if (Regex("""\b(noon|midday)\b""").containsMatchIn(text)) {
            return Clock(LocalTime.NOON)
        }

        if (Regex("""\bmidnight\b""").containsMatchIn(text)) {
            return Clock(LocalTime.MIDNIGHT)
        }

        Regex("""\b(\d{1,2})[:.](\d{2})\s*(am|pm)\b""").find(text)?.let { match ->
            return exact(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3])
        }

        Regex("""\b(\d{1,2})\s*(am|pm)\b""").find(text)?.let { match ->
            return exact(match.groupValues[1].toInt(), 0, match.groupValues[2])
        }

        Regex("""\b(\d{1,2})[:.](\d{2})\b""").find(text)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (minute > 59 || hour > 23) return null
            // "17:30" is clear; "5:30" is not.
            return if (hour >= 13 || hour == 0) Clock(LocalTime.of(hour, minute)) else fromContext(hour, minute, text)
        }

        Regex("""\b(half past|quarter past|quarter to)\s+(\d{1,2}|$NUMBER_WORDS)\b""").find(text)?.let { match ->
            val hour = number(match.groupValues[2])?.takeIf { it in 1..12 } ?: return null
            return when (match.groupValues[1]) {
                "half past" -> fromContext(hour, 30, text)
                "quarter past" -> fromContext(hour, 15, text)
                else -> fromContext(if (hour == 1) 12 else hour - 1, 45, text)
            }
        }

        Regex("""\b(\d{1,2}|$NUMBER_WORDS)\s+o'?clock\b""").find(text)?.let { match ->
            val hour = number(match.groupValues[1])?.takeIf { it in 1..12 } ?: return null
            return fromContext(hour, 0, text)
        }

        // "at 5", but not "at 5th" or "at 5 people".
        Regex("""\bat\s+(\d{1,2})\b(?!\s*(?:st|nd|rd|th|people|persons|of|%))""").find(text)?.let { match ->
            val hour = match.groupValues[1].toInt().takeIf { it in 1..12 } ?: return null
            return fromContext(hour, 0, text)
        }

        return null
    }

    /** "from 5 to 6 PM", "5-6:30 pm", "between 10 am and noon" is not read. */
    fun range(
        words: String
    ): Pair<Clock, Clock>? {

        val text =
            normalize(words)

        val match =
            Regex(
                """\b(from|between)?\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\s*(?:to|-|–|till|until|and)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\b"""
            ).find(text) ?: return null

        val (from, startHour, startMinute, startHalf, endHour, endMinute, endHalf) =
            match.destructured

        // "5 to 6 people" is not a time: it needs "from", an AM/PM, or minutes.
        val looksLikeTime =
            from.isNotEmpty() || startHalf.isNotEmpty() || endHalf.isNotEmpty() ||
                    startMinute.isNotEmpty() || endMinute.isNotEmpty()

        if (!looksLikeTime) {
            return null
        }

        val end =
            if (endHalf.isNotEmpty()) {
                exact(endHour.toInt(), endMinute.toIntOrNull() ?: 0, endHalf)
            } else {
                fromContext(endHour.toInt(), endMinute.toIntOrNull() ?: 0, text)
            } ?: return null

        val start =
            when {
                startHalf.isNotEmpty() -> exact(startHour.toInt(), startMinute.toIntOrNull() ?: 0, startHalf)
                // "5 to 6 PM": the start is in the same half of the day, unless that puts it after the end ("11 to 1 PM").
                endHalf.isNotEmpty() -> {
                    val sameHalf = exact(startHour.toInt(), startMinute.toIntOrNull() ?: 0, endHalf)
                    if (sameHalf != null && sameHalf.time.isAfter(end.time)) {
                        exact(startHour.toInt(), startMinute.toIntOrNull() ?: 0, if (endHalf == "pm") "am" else "pm")
                    } else {
                        sameHalf
                    }
                }
                else -> fromContext(startHour.toInt(), startMinute.toIntOrNull() ?: 0, text)
            } ?: return null

        return start to end
    }

    /** "for an hour", "for 90 minutes", "a 2 hour meeting"; null when no length was said. */
    fun durationMinutes(
        words: String
    ): Int? {

        val text =
            normalize(words)

        if (Regex("""\bhalf an? hour\b""").containsMatchIn(text)) {
            return 30
        }

        if (Regex("""\b(an?|one) hour and a half\b|\b(one|1) and a half hours?\b|\b1\.5 ?(hours?|hrs?)\b""").containsMatchIn(text)) {
            return 90
        }

        Regex("""\bfor\s+(an?|one|\d+(?:\.\d+)?|$NUMBER_WORDS)\s*(hours?|hrs?|minutes?|mins?)\b""").find(text)?.let { match ->
            return minutes(match.groupValues[1], match.groupValues[2])
        }

        Regex("""\b(\d+|$NUMBER_WORDS)[- ](hour|hr|minute|min)[- ]?(?:long\s+)?(meeting|call|session|class|lecture|appointment|interview|slot)\b""").find(text)?.let { match ->
            return minutes(match.groupValues[1], match.groupValues[2])
        }

        return null
    }

    /** "morning", "evening"...: a part of the day said without a clock time. */
    fun partOfDay(
        words: String
    ): String? =
        Regex("""\b(morning|afternoon|evening|tonight|night)\b""").find(normalize(words))?.groupValues?.get(1)

    // Helpers ---------------------------------------------------------------

    private fun exact(
        hour: Int,
        minute: Int,
        half: String
    ): Clock? {

        if (hour !in 1..12 || minute !in 0..59) {
            return null
        }

        val hour24 =
            when {
                half == "am" && hour == 12 -> 0
                half == "am" -> hour
                hour == 12 -> 12
                else -> hour + 12
            }

        return Clock(LocalTime.of(hour24, minute))
    }

    /**
     * A time said without AM or PM. The part of the day decides it when it
     * was said; otherwise the likelier half is taken and flagged.
     */
    private fun fromContext(
        hour: Int,
        minute: Int,
        text: String
    ): Clock? {

        if (hour !in 0..23 || minute !in 0..59) {
            return null
        }

        if (hour >= 13 || hour == 0) {
            return Clock(LocalTime.of(hour, minute))
        }

        return when (partOfDay(text)) {
            "morning" -> Clock(LocalTime.of(if (hour == 12) 0 else hour, minute))
            "afternoon", "evening", "tonight", "night" -> Clock(LocalTime.of(if (hour == 12) 12 else hour + 12, minute))
            // Events at 1-7 are mostly in the afternoon or evening; 8-11 in the morning.
            else -> Clock(LocalTime.of(if (hour in 1..7) hour + 12 else hour, minute), halfGuessed = true)
        }
    }

    private fun minutes(
        amount: String,
        unit: String
    ): Int? {

        val value =
            when (amount) {
                "a", "an", "one" -> 1.0
                else -> amount.toDoubleOrNull() ?: number(amount)?.toDouble()
            } ?: return null

        val total =
            if (unit.startsWith("h")) value * 60 else value

        return total.toInt().takeIf { it in 5..(24 * 60) }
    }

    private fun normalize(
        words: String
    ): String =
        words.lowercase()
            .replace('’', '\'')
            .replace(Regex("""\b([ap])\.\s?m\.?"""), "$1m")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun number(
        word: String
    ): Int? =
        word.toIntOrNull() ?: NUMBERS[word]

    private fun monthNumber(
        word: String
    ): Int? =
        MONTHS.entries.firstOrNull { (name, _) -> word.startsWith(name.take(3)) && name.startsWith(word.take(3)) }?.value

    private fun weekdayOf(
        word: String
    ): DayOfWeek? =
        DayOfWeek.values().firstOrNull { it.name.lowercase().startsWith(word.take(3)) }

    private val NUMBERS =
        mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "forty-five" to 45
        )

    private const val NUMBER_WORDS =
        "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty five|forty-five|forty"

    private val MONTHS =
        linkedMapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
            "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12
        )

    private const val MONTH_WORDS =
        "january|february|march|april|may|june|july|august|september|october|november|december|" +
                "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"

    private const val FULL_WEEKDAYS =
        "monday|tuesday|wednesday|thursday|friday|saturday|sunday"

    private const val SHORT_WEEKDAYS =
        "mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun"
}
