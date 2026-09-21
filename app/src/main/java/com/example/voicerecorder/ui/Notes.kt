package com.example.voicerecorder.ui

import android.content.Context
import com.example.voicerecorder.summary.Note
import com.example.voicerecorder.summary.NoteStore
import com.example.voicerecorder.summary.SavedRecording
import com.example.voicerecorder.reminders.ReminderTimes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One note together with the recording it came from. This is what the
 * history, day and stats screens show.
 */
data class NoteEntry(
    val recording: SavedRecording,
    val note: Note
) {

    val id: String
        get() = note.id

    val time: Long
        get() = recording.recordedAt

    val date: LocalDate
        get() = Notes.dateOf(time)
}

/**
 * Reads the saved results (NoteStore) for the screens. Read only: it
 * never changes or deletes anything. Notes in the recycle bin are left
 * out everywhere except [binEntries].
 */
object Notes {

    private val timeFormat =
        DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())

    private val dayFormat =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    private val monthFormat =
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    fun recordings(
        context: Context
    ): List<SavedRecording> =
        NoteStore(context.applicationContext).loadAll()

    fun entries(
        recordings: List<SavedRecording>
    ): List<NoteEntry> =
        allEntries(recordings)
            .filterNot { it.note.isDeleted }
            .sortedByDescending { it.time }

    /** The recycle bin, most recently deleted first. */
    fun binEntries(
        recordings: List<SavedRecording>
    ): List<NoteEntry> =
        allEntries(recordings)
            .filter { it.note.isDeleted }
            .sortedByDescending { it.note.deletedAt }

    /** Any note, including one in the recycle bin. */
    fun entry(
        recordings: List<SavedRecording>,
        id: String
    ): NoteEntry? =
        allEntries(recordings).firstOrNull { it.id == id }

    private fun allEntries(
        recordings: List<SavedRecording>
    ): List<NoteEntry> =
        recordings.flatMap { recording ->
            recording.notes.map { note -> NoteEntry(recording, note) }
        }

    fun dateOf(
        millis: Long
    ): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    fun millisOf(
        time: LocalDateTime
    ): Long =
        time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun formatTime(
        millis: Long
    ): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormat)

    fun formatDate(
        date: LocalDate
    ): String =
        date.format(dayFormat)

    fun formatDateTime(
        millis: Long
    ): String =
        "${formatDate(dateOf(millis))}, ${formatTime(millis)}"

    fun formatMonth(
        date: LocalDate
    ): String =
        date.format(monthFormat)

    /**
     * "today, 08:00 PM", "tomorrow", "Fri, 25 Sep, 09:30 AM": when a
     * Remember note is due. Empty if it has no deadline.
     */
    fun formatDue(
        note: Note
    ): String {

        val date =
            runCatching { LocalDate.parse(note.dueDate) }.getOrNull() ?: return ""

        val today =
            LocalDate.now()

        val day =
            when (date) {
                today -> "today"
                today.plusDays(1) -> "tomorrow"
                today.minusDays(1) -> "yesterday"
                else -> date.format(dueDayFormat)
            }

        val time =
            ReminderTimes.due(note.dueDate, note.dueTime)
                ?.takeIf { note.dueTime.isNotEmpty() }
                ?.format(timeFormat)

        return if (time == null) day else "$day, $time"
    }

    /** True once a note's deadline has passed. */
    fun isOverdue(
        note: Note
    ): Boolean {

        val due =
            ReminderTimes.due(note.dueDate, note.dueTime) ?: return false

        // A deadline with only a day is overdue once that day is over.
        val end =
            if (note.dueTime.isEmpty()) due.toLocalDate().plusDays(1).atStartOfDay() else due

        return LocalDateTime.now().isAfter(end)
    }

    private val dueDayFormat =
        DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
}
