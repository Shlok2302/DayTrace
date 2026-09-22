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

        /** One lock for every write: the worker and the screens never overwrite each other. */
        val LOCK = Any()
    }
}
