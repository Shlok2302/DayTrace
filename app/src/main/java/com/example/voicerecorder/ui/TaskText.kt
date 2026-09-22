package com.example.voicerecorder.ui

import android.content.Context
import com.example.voicerecorder.R
import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.google.GoogleSettings
import com.example.voicerecorder.google.PendingTaskAdd
import com.example.voicerecorder.google.TaskLink
import com.example.voicerecorder.google.tasks.TaskCheck
import com.example.voicerecorder.google.tasks.TaskDraft
import com.example.voicerecorder.google.tasks.TaskDrafts
import com.example.voicerecorder.summary.GeminiSummarizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a task draft is put into words on the DayTrace screens. */
object TaskText {

    /** "Fri, 25 Sep 2026" — a deadline in full, for the preview. */
    fun fullDay(
        context: Context,
        date: LocalDate
    ): String =
        when (date) {
            LocalDate.now() -> "Today, " + date.format(dayFormat)
            LocalDate.now().plusDays(1) -> "Tomorrow, " + date.format(dayFormat)
            else -> date.format(fullFormat)
        }

    /** "25 Sep" — a deadline in short, for a card chip. */
    fun shortDay(
        date: LocalDate
    ): String =
        date.format(shortFormat)

    /** The deadline of a draft as one short line, or "" when it has none. */
    fun deadline(
        context: Context,
        draft: TaskDraft
    ): String =
        draft.due?.let { fullDay(context, it) } ?: context.getString(R.string.task_no_deadline)

    /** What a saved task's deadline was, for a link; "" when it had none. */
    fun deadlineOf(
        draft: TaskDraft
    ): String =
        draft.due?.let { shortDay(it) }.orEmpty()

    fun listName(
        context: Context,
        draft: TaskDraft
    ): String =
        draft.taskListName.ifEmpty { context.getString(R.string.google_task_list_default) }

    /**
     * The one thing about this check the user should know, or null when
     * it does not need saying on screen.
     */
    fun check(
        context: Context,
        check: TaskCheck
    ): String? =
        when (check) {
            is TaskCheck.NoDeadline -> context.getString(R.string.task_no_deadline_note)
            is TaskCheck.TimeInNotes -> context.getString(R.string.task_time_in_notes_note, check.time)
            is TaskCheck.DayWorkedOut -> context.getString(R.string.task_day_worked_out_note, check.words)
            is TaskCheck.NotChecked -> context.getString(R.string.task_not_checked_note)
        }

    private val dayFormat =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    private val fullFormat =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

    private val shortFormat =
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
}

/**
 * What the note screens show about Google Tasks, read once when a screen
 * loads (files/google/, never the notes).
 */
class TaskStates(
    val connected: Boolean,
    val links: Map<String, TaskLink>,
    val pending: Map<String, PendingTaskAdd>,
    val suggestions: Map<String, EventSuggestion>,
    private val settings: GoogleSettings?
) {

    /**
     * The task DayTrace suggests for this note, or null: only for a
     * Remember note Gemini thinks is a to-do, while Google Tasks is
     * connected, not skipped, and not added yet.
     */
    fun suggestedDraft(
        entry: NoteEntry
    ): TaskDraft? {

        if (!connected || entry.note.isDeleted || entry.note.category != GeminiSummarizer.REMEMBER) {
            return null
        }

        if (entry.id in links || entry.id in pending) {
            return null
        }

        val suggestion =
            suggestions[entry.id]?.takeIf { it.isTask && !it.skipped && it.checkedAt > 0 } ?: return null

        return draftFor(entry, suggestion)
    }

    fun draftFor(
        entry: NoteEntry,
        suggestion: EventSuggestion?
    ): TaskDraft =
        TaskDrafts.from(
            noteId = entry.id,
            noteTitle = entry.note.title,
            noteText = entry.note.text,
            noteDueDate = entry.note.dueDate,
            noteDueTime = entry.note.dueTime,
            suggestion = suggestion,
            defaults = defaults(settings)
        )

    companion object {

        val NONE =
            TaskStates(false, emptyMap(), emptyMap(), emptyMap(), null)

        /** Reads files: not on the main thread. */
        fun load(
            context: Context
        ): TaskStates {

            val manager =
                GoogleIntegrationManager(context)

            val store =
                manager.store

            return TaskStates(
                connected = manager.isConnected(GoogleService.TASKS),
                links = store.taskLinks(),
                pending = store.pendingTasks().associateBy { it.noteId },
                suggestions = store.suggestions(),
                settings = manager.settings
            )
        }

        fun defaults(
            settings: GoogleSettings?
        ): TaskDrafts.Defaults =
            TaskDrafts.Defaults(
                taskListId = settings?.taskListId ?: GoogleSettings.DEFAULT_LIST,
                taskListName = settings?.taskListName.orEmpty(),
                includeContext = settings?.taskIncludeContext ?: true
            )
    }
}

/**
 * Everything the note screens need about Google, read in one go: the
 * calendar side and the tasks side together, plus whether Google Docs is
 * connected. One object, so a screen loads its Google state once.
 */
class GoogleStates(
    val calendar: CalendarStates,
    val tasks: TaskStates,
    val docsConnected: Boolean
) {

    companion object {

        val NONE =
            GoogleStates(CalendarStates.NONE, TaskStates.NONE, false)

        /** Reads files: not on the main thread. */
        fun load(
            context: Context
        ): GoogleStates =
            GoogleStates(
                calendar = CalendarStates.load(context),
                tasks = TaskStates.load(context),
                docsConnected = GoogleIntegrationManager(context).isConnected(GoogleService.DOCS)
            )
    }
}
