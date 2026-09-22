package com.example.voicerecorder

import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.GoogleSettings
import com.example.voicerecorder.google.calendar.CalendarEventIds
import com.example.voicerecorder.google.calendar.EventCheck
import com.example.voicerecorder.google.calendar.EventDraft
import com.example.voicerecorder.google.calendar.EventDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The event built for a note: never a day, time or place that was not
 * said, and every doubt shown to the user.
 */
class EventDraftsTest {

    /** Monday 21 September 2026, 14:05. */
    private val recordedAt =
        LocalDateTime.of(2026, 9, 21, 14, 5)

    private val defaults =
        EventDrafts.Defaults("me@example.com", "Me", GoogleSettings.REMINDER_CALENDAR_DEFAULT)

    private fun suggestion(
        kind: String = EventSuggestion.KIND_EVENT,
        sure: Boolean = true,
        title: String = "",
        whenWords: String = "",
        date: String = "",
        start: String = "",
        timeWords: String = "",
        end: String = "",
        minutes: Int = 0,
        location: String = ""
    ) = EventSuggestion("rec#1", kind, sure, title, whenWords, date, start, timeWords, end, minutes, location, 0L)

    private fun draft(
        suggestion: EventSuggestion?,
        text: String = "A note.",
        dueDate: String = "",
        dueTime: String = ""
    ) = EventDrafts.from("rec#1", "Note title", text, dueDate, dueTime, recordedAt, suggestion, defaults)

    @Test
    fun meetingTomorrowAtFive() {
        val draft = draft(
            suggestion(title = "Meeting with Rahul", whenWords = "tomorrow at 5 PM", date = "2026-09-22", start = "17:00", timeWords = "at 5 PM"),
            text = "Meeting with Rahul tomorrow at 5 PM."
        )

        assertEquals("Meeting with Rahul", draft.title)
        assertEquals(LocalDate.of(2026, 9, 22), draft.date)
        assertEquals(LocalTime.of(17, 0), draft.start)
        assertEquals(60, draft.lengthMinutes)
        assertFalse("The length is DayTrace's default", draft.lengthSaid)
        assertEquals(EventDraft.Verdict.EVENT, draft.verdict)
        assertTrue(draft.checks.isEmpty())
    }

    @Test
    fun nextTuesdayIsShownAsUnsure() {
        val draft = draft(suggestion(title = "Dentist appointment", whenWords = "next Tuesday", date = "2026-09-29"))

        // Gemini's reading of the two possible days is used, and the other one is shown.
        assertEquals(LocalDate.of(2026, 9, 29), draft.date)
        assertEquals(EventCheck.TwoDays("next Tuesday", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 22)), draft.checks.first())

        // No time was said: all day, and the preview says so.
        assertTrue(draft.allDay)
        assertTrue(EventCheck.NoTime in draft.checks)
    }

    @Test
    fun aDayThatCannotBeKnownIsLeftEmpty() {
        val draft = draft(suggestion(title = "Call mom", whenWords = "on her birthday at 7 PM", start = "19:00", timeWords = "at 7 PM"))

        assertNull(draft.date)
        assertTrue(EventCheck.NoDay in draft.checks)
        assertEquals(LocalTime.of(19, 0), draft.start)
    }

    @Test
    fun aDateGeminiInventedIsIgnored() {
        // No words say when, so the date cannot have been said.
        val draft = draft(suggestion(whenWords = "", date = "2026-09-25", start = "10:00"))

        assertNull(draft.date)
        assertNull(draft.start)
        assertTrue(EventCheck.NoDay in draft.checks)
    }

    @Test
    fun aDayBeforeTheRecordingIsDropped() {
        val draft = draft(suggestion(whenWords = "on the 5th of September 2026", date = "2026-09-05"))
        assertNull(draft.date)
        assertTrue(EventCheck.NoDay in draft.checks)
    }

    @Test
    fun geminiAndDayTraceDisagreeing() {
        val draft = draft(suggestion(whenWords = "on Friday", date = "2026-09-26"))
        assertEquals(LocalDate.of(2026, 9, 25), draft.date)
        assertEquals(EventCheck.TwoDays("on Friday", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26)), draft.checks.first())
    }

    @Test
    fun partOfTheDayIsNotATime() {
        val draft = draft(suggestion(whenWords = "tomorrow evening", date = "2026-09-22", timeWords = "evening"))
        assertNull(draft.start)
        assertTrue(EventCheck.PartOfDayOnly("evening") in draft.checks)
    }

    @Test
    fun noAmOrPm() {
        // "at 5": Gemini heard the whole recording and says 17:00; DayTrace flags it anyway.
        val draft = draft(suggestion(whenWords = "tomorrow at 5", date = "2026-09-22", start = "05:00", timeWords = "at 5"))
        assertEquals(LocalTime.of(5, 0), draft.start)
        assertTrue(draft.checks.any { it is EventCheck.HalfGuessed })
    }

    @Test
    fun rangesLengthsAndPlaces() {
        val range = draft(suggestion(whenWords = "tomorrow from 5 to 6:30 PM", date = "2026-09-22", start = "17:00", timeWords = "from 5 to 6:30 PM", location = "Starbucks"))
        assertEquals(LocalTime.of(17, 0), range.start)
        assertEquals(LocalTime.of(18, 30), range.end)
        assertTrue(range.lengthSaid)
        assertEquals("Starbucks", range.location)

        val length = draft(suggestion(whenWords = "tomorrow at 10 AM", date = "2026-09-22", start = "10:00", timeWords = "at 10 AM", minutes = 120))
        assertEquals(120, length.lengthMinutes)
        assertTrue(length.lengthSaid)
        assertEquals(LocalDateTime.of(2026, 9, 22, 12, 0), length.endDateTime())

        // An end time Gemini worked out (or made up) is not used: the default length is shown instead.
        val madeUp = draft(suggestion(whenWords = "tomorrow at 10 AM", date = "2026-09-22", start = "10:00", timeWords = "at 10 AM", end = "13:00"))
        assertNull(madeUp.end)
        assertFalse(madeUp.lengthSaid)
        assertEquals(LocalDateTime.of(2026, 9, 22, 11, 0), madeUp.endDateTime())
    }

    @Test
    fun toDosAreNotEvents() {
        val buyMilk = draft(suggestion(kind = EventSuggestion.KIND_TASK, whenWords = ""), text = "Buy milk.")
        assertEquals(EventDraft.Verdict.NOT_EVENT, buyMilk.verdict)
        assertEquals(EventCheck.LooksLikeTask, buyMilk.checks.first())

        val unsure = draft(suggestion(sure = false, whenWords = "tomorrow", date = "2026-09-22"))
        assertEquals(EventDraft.Verdict.MAYBE_EVENT, unsure.verdict)
        assertEquals(EventCheck.MaybeEvent, unsure.checks.first())
    }

    @Test
    fun withoutGeminiOnlyTheNoteIsUsed() {
        val meeting = draft(null, text = "Meeting with Rahul tomorrow at 5 PM.", dueDate = "2026-09-22", dueTime = "17:00")
        assertEquals(EventDraft.Verdict.UNCHECKED, meeting.verdict)
        assertEquals(EventCheck.NotChecked, meeting.checks.first())
        assertEquals(LocalDate.of(2026, 9, 22), meeting.date)
        assertEquals(LocalTime.of(17, 0), meeting.start)

        // "this evening" gave the note a due time of 19:00, but no time was said.
        val evening = draft(null, text = "Meet Priya this evening.", dueDate = "2026-09-21", dueTime = "19:00")
        assertNull(evening.start)
        assertTrue(EventCheck.PartOfDayOnly("evening") in evening.checks)
    }

    @Test
    fun timedEventJson() {
        val draft = draft(
            suggestion(title = "Meeting with Rahul", whenWords = "tomorrow at 5 PM", date = "2026-09-22", start = "17:00", timeWords = "at 5 PM", location = "Café Coffee Day"),
            text = "Meeting with Rahul tomorrow at 5 PM at Café Coffee Day."
        ).copy(reminderMinutes = 30)

        val json = EventDrafts.toJson(draft, "dtabc123", ZoneId.of("Asia/Kolkata"), "21 Sep 2026, 02:05 PM")

        assertEquals("dtabc123", json.getString("id"))
        assertEquals("Meeting with Rahul", json.getString("summary"))
        assertEquals("Café Coffee Day", json.getString("location"))
        assertEquals("2026-09-22T17:00:00+05:30", json.getJSONObject("start").getString("dateTime"))
        assertEquals("Asia/Kolkata", json.getJSONObject("start").getString("timeZone"))
        assertEquals("2026-09-22T18:00:00+05:30", json.getJSONObject("end").getString("dateTime"))
        assertTrue(json.getString("description").contains("recorded 21 Sep 2026, 02:05 PM"))
        assertTrue(json.getString("description").contains(draft.noteText))
        assertEquals("rec#1", json.getJSONObject("extendedProperties").getJSONObject("private").getString("daytraceNoteId"))

        val reminders = json.getJSONObject("reminders")
        assertFalse(reminders.getBoolean("useDefault"))
        assertEquals(30, reminders.getJSONArray("overrides").getJSONObject(0).getInt("minutes"))
    }

    @Test
    fun allDayEventJson() {
        val draft = draft(suggestion(whenWords = "on Friday", date = "2026-09-25")).copy(reminderMinutes = 30)
        val json = EventDrafts.toJson(draft, "dtabc123", ZoneId.of("Asia/Kolkata"), "21 Sep 2026")

        assertEquals("2026-09-25", json.getJSONObject("start").getString("date"))
        // Google's end date is exclusive.
        assertEquals("2026-09-26", json.getJSONObject("end").getString("date"))
        // All-day events keep the calendar's own notifications.
        assertTrue(json.getJSONObject("reminders").getBoolean("useDefault"))
        assertFalse(json.has("location"))

        val silent = EventDrafts.toJson(draft.copy(reminderMinutes = GoogleSettings.REMINDER_NONE), "dtabc123", ZoneId.of("UTC"), "")
        assertFalse(silent.getJSONObject("reminders").getBoolean("useDefault"))
        assertEquals(0, silent.getJSONObject("reminders").getJSONArray("overrides").length())
    }

    @Test
    fun endsAfterMidnight() {
        val draft = draft(suggestion(whenWords = "tomorrow from 11 PM to 1 AM", date = "2026-09-22", timeWords = "from 11 PM to 1 AM"))
        assertEquals(LocalTime.of(23, 0), draft.start)
        assertEquals(LocalDateTime.of(2026, 9, 23, 1, 0), draft.endDateTime())
    }

    @Test
    fun eventIdsAreStableAndValid() {
        val id = CalendarEventIds.forNote("Voice_Recording_1789#ab12cd34", "me@example.com", 0)

        assertTrue(id.matches(Regex("[a-v0-9]{5,1024}")))
        assertEquals(id, CalendarEventIds.forNote("Voice_Recording_1789#ab12cd34", "me@example.com", 0))
        assertNotEquals(id, CalendarEventIds.forNote("Voice_Recording_1789#ab12cd34", "me@example.com", 1))
        assertNotEquals(id, CalendarEventIds.forNote("Voice_Recording_1789#ab12cd34", "other@example.com", 0))
    }
}
