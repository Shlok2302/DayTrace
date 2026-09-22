package com.example.voicerecorder.google.tasks

import com.example.voicerecorder.google.EventSuggestion
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Something the user should look at before the task is created. Each one
 * is only about where a value came from, never a guess of DayTrace's own.
 */
sealed class TaskCheck {

    /** No deadline was said, so the task gets none. */
    object NoDeadline : TaskCheck()

    /**
     * A clock time was said, but Google Tasks only stores the day. The
     * time is kept in the task's notes instead of being thrown away.
     */
    data class TimeInNotes(val time: String) : TaskCheck()

    /** The day was worked out from words such as "Friday" or "tomorrow". */
    data class DayWorkedOut(val words: String) : TaskCheck()

    /** Gemini did not look at this note; only the note itself was used. */
    object NotChecked : TaskCheck()
}

/**
 * A Google task exactly as it will be created, after the user has seen
 * and possibly changed it. Nothing here is ever invented: a deadline is
 * only set when the recording actually said one, or the user picked it.
 */
data class TaskDraft(
    val noteId: String,
    val title: String,
    /** The note's own words, put under the title as context. */
    val context: String,
    /** The day the task is due, or null when none was said. */
    val due: LocalDate?,
    /**
     * A clock time that was said. Google Tasks cannot store it, so it is
     * written into the notes; keeping it here is what makes that possible.
     */
    val dueTime: LocalTime?,
    val taskListId: String,
    val taskListName: String,
    val checks: List<TaskCheck>,
    val verdict: Verdict
) {

    enum class Verdict {
        /** Gemini is sure this is a to-do. */
        TASK,

        /** It might be a to-do; the user decides. */
        MAYBE_TASK,

        /** Gemini thinks it belongs in the calendar instead. */
        LOOKS_LIKE_EVENT,

        /** Nothing was worked out: only the note itself was used. */
        UNKNOWN
    }

    val hasDeadline: Boolean
        get() = due != null
}

object TaskDrafts {

    class Defaults(
        val taskListId: String,
        val taskListName: String,
        val includeContext: Boolean
    )

    /**
     * Builds the draft from the note and, when there is one, what Gemini
     * worked out about it ([suggestion]). The note's own deadline
     * (due_date / due_time, set while the notes were written) is used
     * when Gemini did not give one, so nothing the user said is lost.
     */
    fun from(
        noteId: String,
        noteTitle: String,
        noteText: String,
        noteDueDate: String,
        noteDueTime: String,
        suggestion: EventSuggestion?,
        defaults: Defaults
    ): TaskDraft {

        val checks =
            mutableListOf<TaskCheck>()

        // Gemini's day first, then the note's own; never a made-up one.
        val suggestedDate =
            parseDate(suggestion?.date)

        val noteDate =
            parseDate(noteDueDate)

        val due =
            suggestedDate ?: noteDate

        val dueTime =
            parseTime(suggestion?.startTime) ?: parseTime(noteDueTime)

        if (suggestion == null || suggestion.checkedAt == 0L) {
            checks += TaskCheck.NotChecked
        }

        val whenWords =
            suggestion?.whenWords.orEmpty().trim()

        if (due == null) {
            checks += TaskCheck.NoDeadline
        } else if (suggestedDate != null && whenWords.isNotEmpty()) {
            checks += TaskCheck.DayWorkedOut(whenWords)
        }

        if (dueTime != null) {
            checks += TaskCheck.TimeInNotes(dueTime.format(TIME_FORMAT))
        }

        val verdict =
            when {
                suggestion == null || suggestion.checkedAt == 0L -> TaskDraft.Verdict.UNKNOWN
                suggestion.isEvent -> TaskDraft.Verdict.LOOKS_LIKE_EVENT
                suggestion.isTask && suggestion.sure -> TaskDraft.Verdict.TASK
                suggestion.isTask -> TaskDraft.Verdict.MAYBE_TASK
                else -> TaskDraft.Verdict.MAYBE_TASK
            }

        return TaskDraft(
            noteId = noteId,
            // The note's own title is already a short line of its own.
            title = noteTitle.trim().ifEmpty { noteText.trim().take(MAX_TITLE) },
            context = if (defaults.includeContext) noteText.trim() else "",
            due = due,
            dueTime = dueTime,
            taskListId = defaults.taskListId,
            taskListName = defaults.taskListName,
            checks = checks,
            verdict = verdict
        )
    }

    /**
     * The task as Google Tasks JSON.
     *
     * Google Tasks stores only the DAY of a deadline: the time part of
     * "due" is discarded by the API and cannot be read back. A time the
     * user actually said is therefore written into the notes, so it is
     * still in front of them, rather than silently lost.
     *
     * [marker] goes last, on its own line: it is how DayTrace recognises
     * a task it already created for this note.
     */
    fun toJson(
        draft: TaskDraft,
        marker: String,
        recordedText: String
    ): JSONObject {

        val notes =
            buildString {

                if (draft.context.isNotBlank() && draft.context != draft.title) {
                    append(draft.context.trim())
                }

                draft.dueTime?.let { time ->
                    if (isNotEmpty()) append("\n\n")
                    append("Due at ").append(time.format(TIME_FORMAT))
                }

                if (recordedText.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Recorded ").append(recordedText)
                }

                if (isNotEmpty()) append("\n\n")
                append(marker)
            }

        val task =
            JSONObject()
                .put("title", draft.title)
                .put("notes", notes)
                .put("status", GoogleTask.STATUS_NEEDS_ACTION)

        // RFC 3339 at UTC midnight: the API keeps the day and drops the time.
        draft.due?.let {
            task.put("due", it.atStartOfDay(ZoneOffset.UTC).format(DUE_FORMAT))
        }

        return task
    }

    private fun parseDate(
        value: String?
    ): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun parseTime(
        value: String?
    ): LocalTime? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    private const val MAX_TITLE = 80

    /** The same shape the rest of DayTrace shows a time in. */
    private val TIME_FORMAT =
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    private val DUE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
}
