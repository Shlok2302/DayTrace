package com.example.voicerecorder.google

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** An event DayTrace created in Google Calendar for a note. */
data class CalendarLink(
    val noteId: String,
    val calendarId: String,
    val calendarName: String,
    val eventId: String,
    val htmlLink: String,
    /**
     * Which event id the note is on: 0, or higher after the event was
     * deleted in Google Calendar and the user added it again.
     */
    val generation: Int,
    val addedAt: Long,
    /** When the event is, as shown when it was added ("Tue, 22 Sep, 5:00 PM"). */
    val whenText: String
)

/**
 * An event the user confirmed that is not in Google Calendar yet: the
 * phone was offline, Google was busy, or Google needs the user to
 * reconnect first. It is sent exactly as confirmed, with the same event
 * id every time, so sending it twice can never create two events.
 */
data class PendingCalendarAdd(
    val noteId: String,
    val calendarId: String,
    val calendarName: String,
    val eventId: String,
    val generation: Int,
    /** The event, as Google Calendar JSON. */
    val event: String,
    val whenText: String,
    val createdAt: Long,
    val state: String = STATE_WAITING,
    /** The kind of the last failure (GoogleException.Kind), or "". */
    val problem: String = ""
) {

    companion object {
        /** Sent automatically once the phone is online. */
        const val STATE_WAITING = "waiting"

        /** Waits until the user reconnects Google. */
        const val STATE_RECONNECT = "reconnect"

        /** Google refused it; the user can try again or cancel it. */
        const val STATE_FAILED = "failed"
    }
}

/** A task DayTrace created in Google Tasks for a note. */
data class TaskLink(
    val noteId: String,
    val taskListId: String,
    val taskListName: String,
    val taskId: String,
    val htmlLink: String,
    val addedAt: Long,
    /** The deadline as it was shown when it was added, or "" when there is none. */
    val dueText: String
)

/**
 * A task the user confirmed that is not in Google Tasks yet: the phone
 * was offline, Google was busy, or the user has to reconnect first.
 *
 * Google Tasks does not let the app choose the task id, so the task
 * carries a [marker] in its notes instead. Before a waiting task is sent
 * again, the list is searched for that marker, so a retry after a lost
 * answer adopts the task that already exists rather than making a second.
 */
data class PendingTaskAdd(
    val noteId: String,
    val taskListId: String,
    val taskListName: String,
    /** The task, as Google Tasks JSON. */
    val task: String,
    val marker: String,
    val dueText: String,
    val createdAt: Long,
    val state: String = STATE_WAITING,
    /** The kind of the last failure (GoogleException.Kind), or "". */
    val problem: String = ""
) {

    companion object {
        /** Sent automatically once the phone is online. */
        const val STATE_WAITING = "waiting"

        /** Waits until the user reconnects Google. */
        const val STATE_RECONNECT = "reconnect"

        /** Google refused it; the user can try again or cancel it. */
        const val STATE_FAILED = "failed"
    }
}

/**
 * A Google document DayTrace created, and may append notes to. DayTrace
 * only ever knows the documents it made itself (drive.file).
 */
data class DocumentLink(
    val documentId: String,
    val title: String,
    /** The category it was made for ("Idea", "Thoughts", ...), or "" for a free choice. */
    val category: String,
    val createdAt: Long
)

/**
 * One note that was appended to one document. Keeps the same note from
 * being written to the same document twice.
 */
data class DocumentEntry(
    val noteId: String,
    val documentId: String,
    val appendedAt: Long
) {

    /** The key both sides of a note-to-document pair are stored under. */
    val key: String
        get() = key(noteId, documentId)

    companion object {
        fun key(
            noteId: String,
            documentId: String
        ): String =
            "$noteId@$documentId"
    }
}

/**
 * What DayTrace found out about a Remember note: whether it is a calendar
 * event, and the words that say when and where. Only filled from what was
 * said (see EventDetector); empty fields were not said.
 */
data class EventSuggestion(
    val noteId: String,
    val kind: String,
    val sure: Boolean,
    val title: String,
    val whenWords: String,
    /** YYYY-MM-DD, or "" when no day was said. */
    val date: String,
    /** HH:MM, only when a clock time was said. */
    val startTime: String,
    val timeWords: String,
    val endTime: String,
    val durationMinutes: Int,
    val location: String,
    val checkedAt: Long,
    /** The user chose "Skip": no more suggestion on the card. */
    val skipped: Boolean = false
) {

    val isEvent: Boolean
        get() = kind == KIND_EVENT

    /** A to-do rather than something the user attends: Google Tasks, not Calendar. */
    val isTask: Boolean
        get() = kind == KIND_TASK

    companion object {
        const val KIND_EVENT = "event"
        const val KIND_TASK = "task"
        const val KIND_OTHER = "other"
    }
}

/**
 * Everything the Google integrations remember, in the app's private
 * storage (files/google/), apart from the notes: NoteStore and the note
 * files are never touched, so nothing here can change or lose a note.
 *
 * - calendar_links.json: note id -> the event created for it
 * - calendar_pending.json: note id -> an event waiting to be sent
 * - calendar_suggestions.json: note id -> is it an event, and when
 * - task_links.json: note id -> the Google task created for it
 * - task_pending.json: note id -> a task waiting to be sent
 * - documents.json: the Google documents DayTrace made
 * - document_entries.json: which notes were appended to which document
 */
class GoogleIntegrationStore(
    private val directory: File
) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIRECTORY))

    // Calendar events created ---------------------------------------------

    fun calendarLink(
        noteId: String
    ): CalendarLink? =
        calendarLinks()[noteId]

    fun calendarLinks(): Map<String, CalendarLink> =
        readAll(LINKS) { noteId, json ->
            CalendarLink(
                noteId = noteId,
                calendarId = json.getString("calendar_id"),
                calendarName = json.optString("calendar_name"),
                eventId = json.getString("event_id"),
                htmlLink = json.optString("html_link"),
                generation = json.optInt("generation"),
                addedAt = json.optLong("added_at"),
                whenText = json.optString("when")
            )
        }

    fun saveCalendarLink(
        link: CalendarLink
    ) {
        change(LINKS) { all ->
            all.put(
                link.noteId,
                JSONObject()
                    .put("calendar_id", link.calendarId)
                    .put("calendar_name", link.calendarName)
                    .put("event_id", link.eventId)
                    .put("html_link", link.htmlLink)
                    .put("generation", link.generation)
                    .put("added_at", link.addedAt)
                    .put("when", link.whenText)
            )
            true
        }
    }

    // Events waiting to be sent -------------------------------------------

    fun pending(): List<PendingCalendarAdd> =
        readAll(PENDING) { noteId, json ->
            PendingCalendarAdd(
                noteId = noteId,
                calendarId = json.getString("calendar_id"),
                calendarName = json.optString("calendar_name"),
                eventId = json.getString("event_id"),
                generation = json.optInt("generation"),
                event = json.getString("event"),
                whenText = json.optString("when"),
                createdAt = json.optLong("created_at"),
                state = json.optString("state", PendingCalendarAdd.STATE_WAITING),
                problem = json.optString("problem")
            )
        }.values.sortedBy { it.createdAt }

    fun pendingFor(
        noteId: String
    ): PendingCalendarAdd? =
        pending().firstOrNull { it.noteId == noteId }

    fun savePending(
        pending: PendingCalendarAdd
    ) {
        change(PENDING) { all ->
            all.put(
                pending.noteId,
                JSONObject()
                    .put("calendar_id", pending.calendarId)
                    .put("calendar_name", pending.calendarName)
                    .put("event_id", pending.eventId)
                    .put("generation", pending.generation)
                    .put("event", pending.event)
                    .put("when", pending.whenText)
                    .put("created_at", pending.createdAt)
                    .put("state", pending.state)
                    .put("problem", pending.problem)
            )
            true
        }
    }

    fun removePending(
        noteId: String
    ): Boolean =
        change(PENDING) { all -> all.remove(noteId) != null }

    /** Cancels every waiting event; returns how many. */
    fun clearPending(): Int {

        var count = 0

        change(PENDING) { all ->
            count = all.length()
            val keys = all.keys().asSequence().toList()
            keys.forEach { all.remove(it) }
            count > 0
        }

        return count
    }

    // Google Tasks created ------------------------------------------------

    fun taskLink(
        noteId: String
    ): TaskLink? =
        taskLinks()[noteId]

    fun taskLinks(): Map<String, TaskLink> =
        readAll(TASK_LINKS) { noteId, json ->
            TaskLink(
                noteId = noteId,
                taskListId = json.getString("task_list_id"),
                taskListName = json.optString("task_list_name"),
                taskId = json.getString("task_id"),
                htmlLink = json.optString("html_link"),
                addedAt = json.optLong("added_at"),
                dueText = json.optString("due")
            )
        }

    fun saveTaskLink(
        link: TaskLink
    ) {
        change(TASK_LINKS) { all ->
            all.put(
                link.noteId,
                JSONObject()
                    .put("task_list_id", link.taskListId)
                    .put("task_list_name", link.taskListName)
                    .put("task_id", link.taskId)
                    .put("html_link", link.htmlLink)
                    .put("added_at", link.addedAt)
                    .put("due", link.dueText)
            )
            true
        }
    }

    /**
     * Forgets the task DayTrace made for a note. Only for a task the user
     * deleted in Google Tasks: the task itself is never deleted from here.
     */
    fun removeTaskLink(
        noteId: String
    ): Boolean =
        change(TASK_LINKS) { all -> all.remove(noteId) != null }

    // Tasks waiting to be sent --------------------------------------------

    fun pendingTasks(): List<PendingTaskAdd> =
        readAll(TASK_PENDING) { noteId, json ->
            PendingTaskAdd(
                noteId = noteId,
                taskListId = json.getString("task_list_id"),
                taskListName = json.optString("task_list_name"),
                task = json.getString("task"),
                marker = json.optString("marker"),
                dueText = json.optString("due"),
                createdAt = json.optLong("created_at"),
                state = json.optString("state", PendingTaskAdd.STATE_WAITING),
                problem = json.optString("problem")
            )
        }.values.sortedBy { it.createdAt }

    fun pendingTaskFor(
        noteId: String
    ): PendingTaskAdd? =
        pendingTasks().firstOrNull { it.noteId == noteId }

    fun savePendingTask(
        pending: PendingTaskAdd
    ) {
        change(TASK_PENDING) { all ->
            all.put(
                pending.noteId,
                JSONObject()
                    .put("task_list_id", pending.taskListId)
                    .put("task_list_name", pending.taskListName)
                    .put("task", pending.task)
                    .put("marker", pending.marker)
                    .put("due", pending.dueText)
                    .put("created_at", pending.createdAt)
                    .put("state", pending.state)
                    .put("problem", pending.problem)
            )
            true
        }
    }

    fun removePendingTask(
        noteId: String
    ): Boolean =
        change(TASK_PENDING) { all -> all.remove(noteId) != null }

    /** Cancels every waiting task; returns how many. */
    fun clearPendingTasks(): Int {

        var count = 0

        change(TASK_PENDING) { all ->
            count = all.length()
            all.keys().asSequence().toList().forEach { all.remove(it) }
            count > 0
        }

        return count
    }

    // Google documents ------------------------------------------------------

    fun documents(): List<DocumentLink> =
        readAll(DOCUMENTS) { documentId, json ->
            DocumentLink(
                documentId = documentId,
                title = json.optString("title"),
                category = json.optString("category"),
                createdAt = json.optLong("created_at")
            )
        }.values.sortedBy { it.createdAt }

    fun document(
        documentId: String
    ): DocumentLink? =
        documents().firstOrNull { it.documentId == documentId }

    fun saveDocument(
        document: DocumentLink
    ) {
        change(DOCUMENTS) { all ->
            all.put(
                document.documentId,
                JSONObject()
                    .put("title", document.title)
                    .put("category", document.category)
                    .put("created_at", document.createdAt)
            )
            true
        }
    }

    /** The document is gone in Google Docs: DayTrace stops offering it. */
    fun removeDocument(
        documentId: String
    ): Boolean =
        change(DOCUMENTS) { all -> all.remove(documentId) != null }

    // Notes already written to a document ----------------------------------

    fun documentEntries(): Map<String, DocumentEntry> =
        readAll(DOC_ENTRIES) { _, json ->
            DocumentEntry(
                noteId = json.getString("note_id"),
                documentId = json.getString("document_id"),
                appendedAt = json.optLong("appended_at")
            )
        }

    /** The note was already appended to this document, or null. */
    fun documentEntry(
        noteId: String,
        documentId: String
    ): DocumentEntry? =
        documentEntries()[DocumentEntry.key(noteId, documentId)]

    /** Every document a note has been appended to. */
    fun documentsFor(
        noteId: String
    ): List<DocumentEntry> =
        documentEntries().values.filter { it.noteId == noteId }

    fun saveDocumentEntry(
        entry: DocumentEntry
    ) {
        change(DOC_ENTRIES) { all ->
            all.put(
                entry.key,
                JSONObject()
                    .put("note_id", entry.noteId)
                    .put("document_id", entry.documentId)
                    .put("appended_at", entry.appendedAt)
            )
            true
        }
    }

    /** Forgets every note written to a document that no longer exists. */
    fun removeDocumentEntries(
        documentId: String
    ): Int {

        var removed = 0

        change(DOC_ENTRIES) { all ->
            all.keys().asSequence().toList()
                .filter { all.optJSONObject(it)?.optString("document_id") == documentId }
                .forEach {
                    all.remove(it)
                    removed++
                }
            removed > 0
        }

        return removed
    }

    // Is it an event? -----------------------------------------------------

    fun suggestion(
        noteId: String
    ): EventSuggestion? =
        suggestions()[noteId]

    fun suggestions(): Map<String, EventSuggestion> =
        readAll(SUGGESTIONS) { noteId, json ->
            EventSuggestion(
                noteId = noteId,
                kind = json.optString("kind", EventSuggestion.KIND_OTHER),
                sure = json.optBoolean("sure"),
                title = json.optString("title"),
                whenWords = json.optString("when_words"),
                date = json.optString("date"),
                startTime = json.optString("start_time"),
                timeWords = json.optString("time_words"),
                endTime = json.optString("end_time"),
                durationMinutes = json.optInt("duration_minutes"),
                location = json.optString("location"),
                checkedAt = json.optLong("checked_at"),
                skipped = json.optBoolean("skipped")
            )
        }

    fun saveSuggestions(
        suggestions: Collection<EventSuggestion>
    ) {

        if (suggestions.isEmpty()) {
            return
        }

        change(SUGGESTIONS) { all ->
            suggestions.forEach { suggestion ->
                all.put(
                    suggestion.noteId,
                    JSONObject()
                        .put("kind", suggestion.kind)
                        .put("sure", suggestion.sure)
                        .put("title", suggestion.title)
                        .put("when_words", suggestion.whenWords)
                        .put("date", suggestion.date)
                        .put("start_time", suggestion.startTime)
                        .put("time_words", suggestion.timeWords)
                        .put("end_time", suggestion.endTime)
                        .put("duration_minutes", suggestion.durationMinutes)
                        .put("location", suggestion.location)
                        .put("checked_at", suggestion.checkedAt)
                        .put("skipped", suggestion.skipped)
                )
            }
            true
        }
    }

    /** "Skip": the card stops suggesting it. The note itself is not changed. */
    fun skip(
        noteId: String
    ): Boolean =
        change(SUGGESTIONS) { all ->
            val suggestion = all.optJSONObject(noteId) ?: return@change false
            suggestion.put("skipped", true)
            true
        }

    // Files -----------------------------------------------------------------

    private fun <T> readAll(
        name: String,
        convert: (String, JSONObject) -> T
    ): Map<String, T> {

        val all =
            synchronized(LOCK) { read(name) }

        return all.keys().asSequence().mapNotNull { key ->
            runCatching { key to convert(key, all.getJSONObject(key)) }.getOrNull()
        }.toMap()
    }

    /** [edit] returns true when it changed something, so the file is written. */
    private fun change(
        name: String,
        edit: (JSONObject) -> Boolean
    ): Boolean =
        synchronized(LOCK) {
            val all = read(name)
            if (!edit(all)) return@synchronized false
            write(name, all)
            true
        }

    private fun read(
        name: String
    ): JSONObject =
        runCatching { JSONObject(File(directory, name).readText()) }.getOrElse { JSONObject() }

    /** A temporary file first, so a crash never leaves half a file. */
    private fun write(
        name: String,
        json: JSONObject
    ) {

        directory.mkdirs()

        val file =
            File(directory, name)

        val temporary =
            File(directory, "$name.tmp")

        temporary.writeText(json.toString(2))

        if (!temporary.renameTo(file)) {
            // Windows (unit tests) cannot rename over an existing file.
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IOException("Could not save $name")
            }
        }
    }

    private companion object {

        const val DIRECTORY = "google"

        const val LINKS = "calendar_links.json"
        const val PENDING = "calendar_pending.json"
        const val SUGGESTIONS = "calendar_suggestions.json"

        const val TASK_LINKS = "task_links.json"
        const val TASK_PENDING = "task_pending.json"

        const val DOCUMENTS = "documents.json"
        const val DOC_ENTRIES = "document_entries.json"

        /** One lock for every write: the worker and the screens never overwrite each other. */
        val LOCK = Any()
    }
}
