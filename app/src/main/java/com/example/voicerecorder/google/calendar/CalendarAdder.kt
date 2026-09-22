package com.example.voicerecorder.google.calendar

import com.example.voicerecorder.google.CalendarLink
import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleIntegrationStore
import com.example.voicerecorder.google.PendingCalendarAdd
import org.json.JSONObject

/**
 * Adds a note's event to Google Calendar, at most once.
 *
 * - The event id comes from the note (CalendarEventIds), so Google itself
 *   refuses a second copy; DayTrace also remembers the event it created
 *   (GoogleIntegrationStore) and does not send it again while it exists.
 * - Offline, or Google busy: the event is kept as the user confirmed it
 *   and sent later with the same id (GoogleSyncWorker).
 * - Anything the user has to act on (reconnect, a refused request) is
 *   thrown as a [GoogleException].
 *
 * The DayTrace note is never read or written here.
 */
class CalendarAdder(
    private val api: CalendarApi,
    private val store: GoogleIntegrationStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    sealed class Outcome {

        data class Added(val link: CalendarLink) : Outcome()

        /** It was already in Google Calendar: nothing new was created. */
        data class AlreadyAdded(val link: CalendarLink) : Outcome()

        /** Saved on the phone and sent when possible. [reason]: why not now. */
        data class Queued(val pending: PendingCalendarAdd, val reason: GoogleException.Kind) : Outcome()
    }

    /** One event to add, as the user confirmed it. [event] builds the JSON for an event id. */
    class Request(
        val noteId: String,
        val calendarId: String,
        val calendarName: String,
        val whenText: String,
        val event: (eventId: String) -> JSONObject
    )

    /**
     * The event this note already has in Google Calendar, or null.
     * Offline, the saved link is trusted, so nothing is added twice.
     */
    suspend fun existing(
        noteId: String
    ): CalendarLink? {

        val link =
            store.calendarLink(noteId) ?: return null

        return if (stillThere(link)) link else null
    }

    suspend fun add(
        request: Request
    ): Outcome {

        // Already confirmed and waiting to be sent: not a second time.
        store.pendingFor(request.noteId)?.let {
            return Outcome.Queued(it, GoogleException.Kind.OFFLINE)
        }

        val link =
            store.calendarLink(request.noteId)

        if (link != null && stillThere(link)) {
            return Outcome.AlreadyAdded(link)
        }

        // Deleted in Google Calendar: its id stays taken, so the next one is used.
        val generation =
            if (link == null) 0 else link.generation + 1

        return insert(request.noteId, request.calendarId, request.calendarName, request.whenText, generation, pending = null, request.event)
    }

    /** Sends an event that was waiting (GoogleSyncWorker). */
    suspend fun send(
        pending: PendingCalendarAdd
    ): Outcome {

        val confirmed =
            JSONObject(pending.event)

        return try {
            insert(
                pending.noteId,
                pending.calendarId,
                pending.calendarName,
                pending.whenText,
                pending.generation,
                pending,
            ) { eventId -> JSONObject(confirmed.toString()).put("id", eventId) }
        } catch (e: GoogleException) {
            // Kept, with what went wrong, until the user reconnects, retries or cancels it.
            store.savePending(
                pending.copy(
                    state = if (e.kind == GoogleException.Kind.NEEDS_CONSENT) {
                        PendingCalendarAdd.STATE_RECONNECT
                    } else {
                        PendingCalendarAdd.STATE_FAILED
                    },
                    problem = e.kind.name
                )
            )
            throw e
        }
    }

    private suspend fun insert(
        noteId: String,
        calendarId: String,
        calendarName: String,
        whenText: String,
        firstGeneration: Int,
        pending: PendingCalendarAdd?,
        event: (String) -> JSONObject
    ): Outcome {

        for (generation in firstGeneration until firstGeneration + MAX_TRIES) {

            val eventId =
                CalendarEventIds.forNote(noteId, calendarId, generation)

            val body =
                event(eventId)

            try {

                val created =
                    api.insert(calendarId, body)

                return Outcome.Added(saveLink(noteId, calendarId, calendarName, whenText, generation, created))

            } catch (e: GoogleException) {

                if (e.kind.retryLater) {
                    return queue(noteId, calendarId, calendarName, whenText, generation, body, pending, e.kind)
                }

                if (e.kind != GoogleException.Kind.CONFLICT) {
                    throw e
                }

                // The id is taken: by this note's own event (created before, but
                // the answer was lost), or by one deleted in Google Calendar.
                val existing =
                    try {
                        api.get(calendarId, eventId)
                    } catch (e: GoogleException) {
                        if (e.kind.retryLater) {
                            return queue(noteId, calendarId, calendarName, whenText, generation, body, pending, e.kind)
                        }
                        throw e
                    }

                if (existing != null && !existing.isCancelled) {
                    return Outcome.AlreadyAdded(saveLink(noteId, calendarId, calendarName, whenText, generation, existing))
                }
            }
        }

        throw GoogleException(GoogleException.Kind.CONFLICT, "Google Calendar kept refusing the event id")
    }

    private fun queue(
        noteId: String,
        calendarId: String,
        calendarName: String,
        whenText: String,
        generation: Int,
        body: JSONObject,
        pending: PendingCalendarAdd?,
        reason: GoogleException.Kind
    ): Outcome.Queued {

        val waiting =
            PendingCalendarAdd(
                noteId = noteId,
                calendarId = calendarId,
                calendarName = calendarName,
                eventId = body.getString("id"),
                generation = generation,
                event = body.toString(),
                whenText = whenText,
                createdAt = pending?.createdAt ?: now(),
                state = PendingCalendarAdd.STATE_WAITING,
                problem = reason.name
            )

        store.savePending(waiting)

        return Outcome.Queued(waiting, reason)
    }

    private fun saveLink(
        noteId: String,
        calendarId: String,
        calendarName: String,
        whenText: String,
        generation: Int,
        event: CalendarEvent
    ): CalendarLink {

        val link =
            CalendarLink(
                noteId = noteId,
                calendarId = calendarId,
                calendarName = calendarName,
                eventId = event.id,
                htmlLink = event.htmlLink,
                generation = generation,
                addedAt = now(),
                whenText = whenText
            )

        store.saveCalendarLink(link)
        store.removePending(noteId)

        return link
    }

    /** False only when Google says the event is gone (or not in this account's calendars). */
    private suspend fun stillThere(
        link: CalendarLink
    ): Boolean =
        try {
            api.get(link.calendarId, link.eventId)?.let { !it.isCancelled } ?: false
        } catch (e: GoogleException) {
            when {
                // Cannot check right now: assume it is still there rather than risk a copy.
                e.kind.retryLater -> true
                // Added with another Google account, which is no longer connected.
                e.kind == GoogleException.Kind.FORBIDDEN -> false
                else -> throw e
            }
        }

    private companion object {

        /** Event ids tried after the previous one turned out deleted in Google Calendar. */
        const val MAX_TRIES = 5
    }
}
