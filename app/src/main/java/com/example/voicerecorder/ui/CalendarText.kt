package com.example.voicerecorder.ui

import android.content.Context
import com.example.voicerecorder.R
import com.example.voicerecorder.google.CalendarLink
import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.google.GoogleSettings
import com.example.voicerecorder.google.PendingCalendarAdd
import com.example.voicerecorder.google.calendar.EventCheck
import com.example.voicerecorder.google.calendar.EventDraft
import com.example.voicerecorder.google.calendar.EventDrafts
import com.example.voicerecorder.summary.GeminiSummarizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How Google Calendar events are written on screen: days, times,
 * reminders, and what the user should check.
 */
object CalendarText {

    private val dayFormat =
        DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

    private val fullDayFormat =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

    private val timeFormat =
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun time(
        time: LocalTime
    ): String =
        time.format(timeFormat)

    fun day(
        date: LocalDate
    ): String =
        date.format(dayFormat)

    /** "Tue, 22 Sep 2026 · tomorrow" */
    fun fullDay(
        context: Context,
        date: LocalDate
    ): String {

        val relative =
            when (date) {
                LocalDate.now() -> context.getString(R.string.calendar_today)
                LocalDate.now().plusDays(1) -> context.getString(R.string.calendar_tomorrow)
                else -> null
            }

        val day =
            date.format(fullDayFormat)

        return if (relative == null) day else context.getString(R.string.calendar_date_relative, day, relative)
    }

    /** "5:00 PM – 6:00 PM", or "All day". */
    fun hours(
        context: Context,
        draft: EventDraft
    ): String {

        val start =
            draft.start ?: return context.getString(R.string.calendar_all_day)

        val end =
            end(context, draft) ?: return time(start)

        return context.getString(R.string.calendar_time_range, time(start), end)
    }

    /** "6:00 PM", or "1:00 AM (next day)" when the event ends after midnight. */
    fun end(
        context: Context,
        draft: EventDraft
    ): String? {

        val endAt =
            draft.endDateTime() ?: return null

        val text =
            time(endAt.toLocalTime())

        return if (endAt.toLocalDate() != draft.date) context.getString(R.string.calendar_next_day, text) else text
    }

    /** "Tue, 22 Sep, 5:00 PM" or "Tue, 22 Sep · All day": saved with the event DayTrace added. */
    fun whenText(
        context: Context,
        draft: EventDraft
    ): String {

        val date =
            draft.date ?: return ""

        val start =
            draft.start ?: return "${day(date)} · ${context.getString(R.string.calendar_all_day)}"

        return "${day(date)}, ${time(start)}"
    }

    /** For the card: "Tomorrow, 5:00 PM", "Fri, 25 Sep". */
    fun short(
        context: Context,
        draft: EventDraft
    ): String {

        val date =
            draft.date ?: return ""

        val day =
            when (date) {
                LocalDate.now() -> context.getString(R.string.calendar_today).replaceFirstChar { it.titlecase() }
                LocalDate.now().plusDays(1) -> context.getString(R.string.calendar_tomorrow).replaceFirstChar { it.titlecase() }
                else -> day(date)
            }

        return draft.start?.let { "$day, ${time(it)}" } ?: day
    }

    /** "Your main calendar" until Google Calendar is connected and its name is known. */
    fun calendarName(
        context: Context,
        draft: EventDraft
    ): String =
        if (draft.calendarName == GoogleSettings.PRIMARY) context.getString(R.string.google_calendar_primary) else draft.calendarName

    fun reminder(
        context: Context,
        minutes: Int
    ): String =
        when (minutes) {
            GoogleSettings.REMINDER_CALENDAR_DEFAULT -> context.getString(R.string.google_reminder_default)
            GoogleSettings.REMINDER_NONE -> context.getString(R.string.google_reminder_none)
            60 -> context.getString(R.string.google_reminder_hour)
            24 * 60 -> context.getString(R.string.google_reminder_day)
            else -> context.getString(R.string.google_reminder_minutes, minutes)
        }

    /** The reminder the event will really get: all-day events keep the calendar's own. */
    fun reminderFor(
        context: Context,
        draft: EventDraft
    ): String =
        if (draft.allDay && draft.reminderMinutes > 0) {
            reminder(context, GoogleSettings.REMINDER_CALENDAR_DEFAULT)
        } else {
            reminder(context, draft.reminderMinutes)
        }

    fun length(
        context: Context,
        minutes: Int
    ): String =
        when {
            minutes < 60 -> context.getString(R.string.google_length_minutes, minutes)
            minutes == 60 -> context.getString(R.string.google_length_hour)
            minutes % 60 == 0 -> context.getString(R.string.google_length_hours, (minutes / 60).toString())
            else -> context.getString(R.string.google_length_hours, String.format(Locale.getDefault(), "%.1f", minutes / 60f))
        }

    /** What the user should check, or null when it needs no warning. */
    fun check(
        context: Context,
        check: EventCheck,
        draft: EventDraft
    ): String? =
        when (check) {
            is EventCheck.TwoDays -> context.getString(R.string.calendar_check_two_days, check.words, fullDay(context, check.other))
            is EventCheck.DayWorkedOut -> context.getString(R.string.calendar_check_day_worked_out, check.words)
            is EventCheck.TimeWorkedOut -> context.getString(R.string.calendar_check_time_worked_out, check.words)
            is EventCheck.HalfGuessed -> context.getString(R.string.calendar_check_half, check.words, draft.start?.let(::time).orEmpty())
            is EventCheck.PartOfDayOnly -> context.getString(R.string.calendar_check_part_of_day, check.part)
            EventCheck.NoDay -> context.getString(R.string.calendar_check_no_day)
            EventCheck.LooksLikeTask -> context.getString(R.string.calendar_check_task)
            EventCheck.MaybeEvent -> context.getString(R.string.calendar_check_maybe)
            EventCheck.NotChecked -> context.getString(R.string.calendar_check_not_checked)
            // Shown in the time row instead.
            EventCheck.NoTime -> null
        }
}

/**
 * What the note screens show about Google Calendar, read once when a
 * screen loads (files/google/, never the notes).
 */
class CalendarStates(
    val connected: Boolean,
    val links: Map<String, CalendarLink>,
    val pending: Map<String, PendingCalendarAdd>,
    val suggestions: Map<String, EventSuggestion>,
    private val settings: GoogleSettings?
) {

    /**
     * The event DayTrace suggests for this note, or null: only for a
     * Remember note Gemini thinks is an event, while Google Calendar is
     * connected, not skipped, not added yet, and not in the past.
     */
    fun suggestedDraft(
        entry: NoteEntry
    ): EventDraft? {

        if (!connected || entry.note.isDeleted || entry.note.category != GeminiSummarizer.REMEMBER) {
            return null
        }

        if (entry.id in links || entry.id in pending) {
            return null
        }

        val suggestion =
            suggestions[entry.id]?.takeIf { it.isEvent && !it.skipped && it.checkedAt > 0 } ?: return null

        val draft =
            draftFor(entry, suggestion)

        return draft.takeIf { it.date == null || !it.date.isBefore(LocalDate.now()) }
    }

    fun draftFor(
        entry: NoteEntry,
        suggestion: EventSuggestion?
    ): EventDraft =
        EventDrafts.from(
            noteId = entry.id,
            noteTitle = entry.note.title,
            noteText = entry.note.text,
            noteDueDate = entry.note.dueDate,
            noteDueTime = entry.note.dueTime,
            recordedAt = Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).toLocalDateTime(),
            suggestion = suggestion,
            defaults = defaults(settings)
        )

    companion object {

        val NONE =
            CalendarStates(false, emptyMap(), emptyMap(), emptyMap(), null)

        /** Reads files: not on the main thread. */
        fun load(
            context: Context
        ): CalendarStates {

            val manager =
                GoogleIntegrationManager(context)

            val store =
                manager.store

            return CalendarStates(
                connected = manager.isConnected(GoogleService.CALENDAR),
                links = store.calendarLinks(),
                pending = store.pending().associateBy { it.noteId },
                suggestions = store.suggestions(),
                settings = manager.settings
            )
        }

        fun defaults(
            settings: GoogleSettings?
        ): EventDrafts.Defaults =
            EventDrafts.Defaults(
                calendarId = settings?.calendarId ?: GoogleSettings.PRIMARY,
                calendarName = settings?.calendarName ?: settings?.accountEmail ?: GoogleSettings.PRIMARY,
                reminderMinutes = settings?.eventReminderMinutes ?: GoogleSettings.REMINDER_CALENDAR_DEFAULT,
                lengthMinutes = settings?.eventLengthMinutes ?: GoogleSettings.DEFAULT_LENGTH_MINUTES
            )
    }
}
