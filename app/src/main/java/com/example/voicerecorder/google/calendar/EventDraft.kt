package com.example.voicerecorder.google.calendar

import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.GoogleSettings
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Something the user should look at before adding the event. */
sealed class EventCheck {

    /** The words can mean two days ("next Tuesday"), or DayTrace and Gemini read them differently. */
    data class TwoDays(val words: String, val chosen: LocalDate, val other: LocalDate) : EventCheck()

    /** Gemini worked out the day from words DayTrace cannot check itself. */
    data class DayWorkedOut(val words: String) : EventCheck()

    /** Gemini worked out the time from words DayTrace cannot check itself. */
    data class TimeWorkedOut(val words: String) : EventCheck()

    /** No day was said (or it cannot be known, "on her birthday"): the user picks one. */
    object NoDay : EventCheck()

    /** "at 5": no AM or PM was said. */
    data class HalfGuessed(val words: String) : EventCheck()

    /** Only a part of the day was said ("in the evening"): all day unless a time is set. */
    data class PartOfDayOnly(val part: String) : EventCheck()

    /** No time was said: an all-day event. */
    object NoTime : EventCheck()

    /** DayTrace thinks this is a to-do, not an event. */
    object LooksLikeTask : EventCheck()

    /** Gemini was not sure this is an event. */
    object MaybeEvent : EventCheck()

    /** Gemini could not look at the note (e.g. offline): read from the note only. */
    object NotChecked : EventCheck()
}

/**
 * The Google Calendar event about to be added for a note, as shown in
 * the preview and changed with Edit. Nothing is invented: a day or time
 * that was not said stays empty, and anything unsure is in [checks].
 */
data class EventDraft(
    val noteId: String,
    val title: String,
    /** Null: no day was said; the user has to pick one. */
    val date: LocalDate?,
    /** Null: an all-day event. */
    val start: LocalTime?,
    /** Null: [start] plus [lengthMinutes]. */
    val end: LocalTime?,
    val lengthMinutes: Int,
    /** False: [lengthMinutes] is DayTrace's default, not something that was said. */
    val lengthSaid: Boolean,
    /** Only a place that was said. */
    val location: String,
    val noteText: String,
    val calendarId: String,
    val calendarName: String,
    val reminderMinutes: Int,
    val checks: List<EventCheck>,
    val verdict: Verdict
) {

    enum class Verdict {
        /** Gemini is sure it is an event. */
        EVENT,

        /** Gemini thinks so, but is not sure. */
        MAYBE_EVENT,

        /** Gemini thinks it is a to-do or something else. */
        NOT_EVENT,

        /** Gemini could not check it. */
        UNCHECKED
    }

    val allDay: Boolean
        get() = start == null

    /** When it ends, for a timed event (after midnight when it ends before it starts). */
    fun endDateTime(): LocalDateTime? {

        val day = date ?: return null
        val from = start ?: return null

        val startAt =
            day.atTime(from)

        val until =
            end ?: return startAt.plusMinutes(lengthMinutes.toLong())

        val endAt =
            day.atTime(until)

        return if (endAt.isAfter(startAt)) endAt else endAt.plusDays(1)
    }
}

/**
 * Builds the event for a note from what DayTrace found out about it
 * (EventSuggestion) and the note itself, and turns it into the JSON
 * Google Calendar expects. No Android code, so it is unit tested.
 */
object EventDrafts {

    class Defaults(
        val calendarId: String,
        val calendarName: String,
        val reminderMinutes: Int,
        val lengthMinutes: Int = GoogleSettings.DEFAULT_LENGTH_MINUTES
    )

    fun from(
        noteId: String,
        noteTitle: String,
        noteText: String,
        noteDueDate: String,
        noteDueTime: String,
        recordedAt: LocalDateTime,
        suggestion: EventSuggestion?,
        defaults: Defaults
    ): EventDraft =
        if (suggestion != null) {
            fromSuggestion(noteId, noteTitle, noteText, noteDueDate, recordedAt, suggestion, defaults)
        } else {
            fromNoteOnly(noteId, noteTitle, noteText, noteDueDate, noteDueTime, recordedAt, defaults)
        }

    private fun fromSuggestion(
        noteId: String,
        noteTitle: String,
        noteText: String,
        noteDueDate: String,
        recordedAt: LocalDateTime,
        suggestion: EventSuggestion,
        defaults: Defaults
    ): EventDraft {

        val checks =
            mutableListOf<EventCheck>()

        val recordedOn =
            recordedAt.toLocalDate()

        val words =
            suggestion.whenWords.trim()

        // Day --------------------------------------------------------------

        val read =
            EventTimeResolver.day(words, recordedOn)

        val geminiDate =
            if (words.isEmpty()) null else parseDate(suggestion.date)

        var date: LocalDate? =
            when {
                // Nothing said about when. The note's own deadline was checked
                // against what was said when the note was made, so it may be used.
                words.isEmpty() -> parseDate(noteDueDate)

                read != null && read.other != null -> {
                    val chosen = if (geminiDate == read.other) read.other else read.date
                    checks += EventCheck.TwoDays(words, chosen, if (chosen == read.date) read.other else read.date)
                    chosen
                }

                read != null -> {
                    if (geminiDate != null && geminiDate != read.date) {
                        checks += EventCheck.TwoDays(words, read.date, geminiDate)
                    }
                    read.date
                }

                geminiDate != null -> {
                    checks += EventCheck.DayWorkedOut(words)
                    geminiDate
                }

                else -> null
            }

        // An event before the day it was recorded cannot be right.
        if (date != null && date.isBefore(recordedOn)) {
            date = null
            checks.removeAll { it is EventCheck.TwoDays || it is EventCheck.DayWorkedOut }
        }

        if (date == null) {
            checks += EventCheck.NoDay
        }

        // Time -------------------------------------------------------------

        val timeWords =
            suggestion.timeWords.trim().ifEmpty { words }

        val range =
            EventTimeResolver.range(timeWords) ?: EventTimeResolver.range(words)

        val clock =
            range?.first ?: EventTimeResolver.clock(timeWords) ?: EventTimeResolver.clock(words)

        val geminiStart =
            if (timeWords.isEmpty()) null else parseTime(suggestion.startTime)

        val start: LocalTime? =
            when {
                clock != null && !clock.halfGuessed -> clock.time

                clock != null -> {
                    checks += EventCheck.HalfGuessed(timeWords)
                    // Gemini had the whole recording: take its half of the day when it read the same hour.
                    if (geminiStart != null && geminiStart.minute == clock.time.minute && geminiStart.hour % 12 == clock.time.hour % 12) {
                        geminiStart
                    } else {
                        clock.time
                    }
                }

                geminiStart != null -> {
                    checks += EventCheck.TimeWorkedOut(timeWords)
                    geminiStart
                }

                else -> null
            }

        if (start == null) {
            checks += EventTimeResolver.partOfDay("$timeWords $words")
                ?.let { EventCheck.PartOfDayOnly(it) }
                ?: EventCheck.NoTime
        }

        // Only an end DayTrace reads in the words itself ("from 5 to 6 PM"):
        // Gemini sometimes works one out from the length, or makes one up.
        val end: LocalTime? =
            if (start == null) null else range?.second?.time

        val saidLength =
            EventTimeResolver.durationMinutes("$words $timeWords $noteText")
                ?: suggestion.durationMinutes.takeIf { it in 5..(24 * 60) }

        // Verdict ----------------------------------------------------------

        val verdict =
            when {
                suggestion.isEvent && suggestion.sure -> EventDraft.Verdict.EVENT
                suggestion.isEvent -> EventDraft.Verdict.MAYBE_EVENT
                else -> EventDraft.Verdict.NOT_EVENT
            }

        when (verdict) {
            EventDraft.Verdict.MAYBE_EVENT -> checks.add(0, EventCheck.MaybeEvent)
            EventDraft.Verdict.NOT_EVENT -> checks.add(0, EventCheck.LooksLikeTask)
            else -> Unit
        }

        return EventDraft(
            noteId = noteId,
            title = suggestion.title.trim().ifEmpty { noteTitle },
            date = date,
            start = start,
            end = end,
            lengthMinutes = saidLength ?: defaults.lengthMinutes,
            lengthSaid = saidLength != null || end != null,
            location = suggestion.location.trim(),
            noteText = noteText,
            calendarId = defaults.calendarId,
            calendarName = defaults.calendarName,
            reminderMinutes = defaults.reminderMinutes,
            checks = checks,
            verdict = verdict
        )
    }

    /**
     * Gemini could not look at the note: only what the note itself says
     * is used, and the preview says it was not checked.
     */
    private fun fromNoteOnly(
        noteId: String,
        noteTitle: String,
        noteText: String,
        noteDueDate: String,
        noteDueTime: String,
        recordedAt: LocalDateTime,
        defaults: Defaults
    ): EventDraft {

        val checks =
            mutableListOf<EventCheck>(EventCheck.NotChecked)

        val recordedOn =
            recordedAt.toLocalDate()

        val read =
            EventTimeResolver.day(noteText, recordedOn)

        // The note's deadline was worked out from the recording when the note was made.
        val dueDate =
            parseDate(noteDueDate)

        val date =
            when {
                dueDate != null -> dueDate
                read != null -> {
                    read.other?.let { checks += EventCheck.TwoDays(noteText, read.date, it) }
                    read.date
                }
                else -> null
            }?.takeUnless { it.isBefore(recordedOn) }

        if (date == null) {
            checks += EventCheck.NoDay
        }

        val range =
            EventTimeResolver.range(noteText)

        val clock =
            range?.first ?: EventTimeResolver.clock(noteText)

        val start =
            when {
                clock != null -> {
                    if (clock.halfGuessed) checks += EventCheck.HalfGuessed(noteText)
                    clock.time
                }
                // "this evening" gives the note a due time (19:00) that was never said as a time.
                EventTimeResolver.partOfDay(noteText) != null -> null
                else -> parseTime(noteDueTime)?.also { checks += EventCheck.TimeWorkedOut(noteText) }
            }

        if (start == null) {
            checks += EventTimeResolver.partOfDay(noteText)?.let { EventCheck.PartOfDayOnly(it) } ?: EventCheck.NoTime
        }

        val saidLength =
            EventTimeResolver.durationMinutes(noteText)

        val end =
            if (start == null) null else range?.second?.time

        return EventDraft(
            noteId = noteId,
            title = noteTitle,
            date = date,
            start = start,
            end = end,
            lengthMinutes = saidLength ?: defaults.lengthMinutes,
            lengthSaid = saidLength != null || end != null,
            location = "",
            noteText = noteText,
            calendarId = defaults.calendarId,
            calendarName = defaults.calendarName,
            reminderMinutes = defaults.reminderMinutes,
            checks = checks,
            verdict = EventDraft.Verdict.UNCHECKED
        )
    }

    /**
     * The event as Google Calendar JSON. [recordedText] is when the note
     * was recorded, for the description. All-day events use the calendar's
     * own notifications unless the user chose none.
     */
    fun toJson(
        draft: EventDraft,
        eventId: String,
        zone: ZoneId,
        recordedText: String
    ): JSONObject {

        val date =
            requireNotNull(draft.date) { "An event needs a day" }

        val event =
            JSONObject()
                .put("id", eventId)
                .put("summary", draft.title.trim().ifEmpty { "DayTrace note" })
                .put("description", "From a DayTrace note recorded $recordedText:\n\n${draft.noteText}")
                .put(
                    "extendedProperties",
                    JSONObject().put("private", JSONObject().put("daytraceNoteId", draft.noteId))
                )

        if (draft.location.isNotBlank()) {
            event.put("location", draft.location.trim())
        }

        val start =
            draft.start

        if (start == null) {
            // All day: the end date is the day after (Google's end is exclusive).
            event.put("start", JSONObject().put("date", date.toString()))
            event.put("end", JSONObject().put("date", date.plusDays(1).toString()))
        } else {
            val startAt = date.atTime(start).atZone(zone)
            val endAt = requireNotNull(draft.endDateTime()).atZone(zone)
            event.put("start", JSONObject().put("dateTime", startAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", zone.id))
            event.put("end", JSONObject().put("dateTime", endAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", zone.id))
        }

        val reminders =
            when {
                draft.reminderMinutes == GoogleSettings.REMINDER_NONE ->
                    JSONObject().put("useDefault", false).put("overrides", JSONArray())
                draft.reminderMinutes > 0 && !draft.allDay ->
                    JSONObject().put("useDefault", false).put(
                        "overrides",
                        JSONArray().put(JSONObject().put("method", "popup").put("minutes", draft.reminderMinutes))
                    )
                else ->
                    JSONObject().put("useDefault", true)
            }

        event.put("reminders", reminders)

        return event
    }

    private fun parseDate(
        text: String
    ): LocalDate? =
        runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    private fun parseTime(
        text: String
    ): LocalTime? =
        runCatching { LocalTime.parse(text.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
}
