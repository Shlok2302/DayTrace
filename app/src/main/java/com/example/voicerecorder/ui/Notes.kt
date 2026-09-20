package com.example.voicerecorder.ui

import android.content.Context
import com.example.voicerecorder.summary.Note
import com.example.voicerecorder.summary.NoteStore
import com.example.voicerecorder.summary.SavedRecording
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One note together with the recording it came from. This is what the
 * history, day and stats screens show.
 */
data class NoteEntry(
    val recording: SavedRecording,
    val index: Int,
    val note: Note
) {

    val id: String
        get() = "${recording.name}#$index"

    val time: Long
        get() = recording.recordedAt

    val date: LocalDate
        get() = Notes.dateOf(time)
}

/**
 * Reads the saved results (NoteStore) for the screens. Read only: it
 * never changes or deletes anything.
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
        recordings
            .flatMap { recording ->
                recording.notes.mapIndexed { index, note -> NoteEntry(recording, index, note) }
            }
            .sortedByDescending { it.time }

    fun entry(
        recordings: List<SavedRecording>,
        id: String
    ): NoteEntry? =
        entries(recordings).firstOrNull { it.id == id }

    fun dateOf(
        millis: Long
    ): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

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
}
