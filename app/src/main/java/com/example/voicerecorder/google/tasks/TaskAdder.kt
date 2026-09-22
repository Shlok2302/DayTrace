package com.example.voicerecorder.google.tasks

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleIntegrationStore
import com.example.voicerecorder.google.PendingTaskAdd
import com.example.voicerecorder.google.TaskLink
import org.json.JSONObject

/**
 * Adds a note's task to Google Tasks, at most once.
 *
 * Google Tasks does not let the app choose the task id, so the trick
 * Google Calendar uses (an id Google itself refuses twice) is not
 * available. Instead:
 *
 * - every task carries a mark worked out from the note ([TaskMarkers]),
 *   written at the end of its notes
 * - DayTrace remembers the task it created (GoogleIntegrationStore) and
 *   does not send it again while that task exists
 * - before creating anything, and again before sending a task that was
 *   waiting, the list is searched for the mark: if a task carrying it is
 *   already there (the answer to an earlier request was lost, or the app
 *   was reinstalled), that task is adopted rather than a second created
 *
 * A task the user ticked off in Google Tasks still counts as added, and
 * a task the user deleted there can be added again.
 *
 * The DayTrace note is never read or written here.
 */
class TaskAdder(
    private val api: TasksApi,
    private val store: GoogleIntegrationStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    sealed class Outcome {

        data class Added(val link: TaskLink) : Outcome()

        /** It was already in Google Tasks: nothing new was created. */
        data class AlreadyAdded(val link: TaskLink) : Outcome()

        /** Saved on the phone and sent when possible. [reason]: why not now. */
        data class Queued(val pending: PendingTaskAdd, val reason: GoogleException.Kind) : Outcome()
    }

    /** One task to add, as the user confirmed it. [task] builds the JSON for a mark. */
    class Request(
        val noteId: String,
        val taskListId: String,
        val taskListName: String,
        val dueText: String,
        val task: (marker: String) -> JSONObject
    )

    /**
     * The task this note already has in Google Tasks, or null. Offline,
     * the saved link is trusted, so nothing is added twice.
     */
    suspend fun existing(
        noteId: String
    ): TaskLink? {

        val link =
            store.taskLink(noteId) ?: return null

        return if (stillThere(link)) link else null
    }

    suspend fun add(
        request: Request
    ): Outcome {

        // Already confirmed and waiting to be sent: not a second time.
        store.pendingTaskFor(request.noteId)?.let {
            return Outcome.Queued(it, GoogleException.Kind.OFFLINE)
        }

        val link =
            store.taskLink(request.noteId)

        if (link != null && stillThere(link)) {
            return Outcome.AlreadyAdded(link)
        }

        val marker =
            TaskMarkers.forNote(request.noteId, request.taskListId)

        return insert(
            noteId = request.noteId,
            taskListId = request.taskListId,
            taskListName = request.taskListName,
            dueText = request.dueText,
            marker = marker,
            body = request.task(marker),
            pending = null
        )
    }

    /** Sends a task that was waiting (TaskSyncWorker). */
    suspend fun send(
        pending: PendingTaskAdd
    ): Outcome =
        try {
            insert(
                noteId = pending.noteId,
                taskListId = pending.taskListId,
                taskListName = pending.taskListName,
                dueText = pending.dueText,
                marker = pending.marker,
                body = JSONObject(pending.task),
                pending = pending
            )
        } catch (e: GoogleException) {
            // Kept, with what went wrong, until the user reconnects, retries or cancels it.
            store.savePendingTask(
                pending.copy(
                    state = if (e.kind == GoogleException.Kind.NEEDS_CONSENT) {
                        PendingTaskAdd.STATE_RECONNECT
                    } else {
                        PendingTaskAdd.STATE_FAILED
                    },
                    problem = e.kind.name
                )
            )
            throw e
        }

    private suspend fun insert(
        noteId: String,
        taskListId: String,
        taskListName: String,
        dueText: String,
        marker: String,
        body: JSONObject,
        pending: PendingTaskAdd?
    ): Outcome {

        /*
         * A task carrying this mark already exists when an earlier
         * request did reach Google but its answer did not. Adopting it is
         * what keeps a retry from making a second task.
         */
        try {
            api.findByMarker(taskListId, marker)?.let { found ->
                return Outcome.AlreadyAdded(saveLink(noteId, taskListId, taskListName, dueText, found))
            }
        } catch (e: GoogleException) {
            if (e.kind.retryLater) {
                return queue(noteId, taskListId, taskListName, dueText, marker, body, pending, e.kind)
            }
            throw e
        }

        return try {
            Outcome.Added(saveLink(noteId, taskListId, taskListName, dueText, api.insert(taskListId, body)))
        } catch (e: GoogleException) {
            if (e.kind.retryLater) {
                queue(noteId, taskListId, taskListName, dueText, marker, body, pending, e.kind)
            } else {
                throw e
            }
        }
    }

    private fun queue(
        noteId: String,
        taskListId: String,
        taskListName: String,
        dueText: String,
        marker: String,
        body: JSONObject,
        pending: PendingTaskAdd?,
        reason: GoogleException.Kind
    ): Outcome.Queued {

        val waiting =
            PendingTaskAdd(
                noteId = noteId,
                taskListId = taskListId,
                taskListName = taskListName,
                task = body.toString(),
                marker = marker,
                dueText = dueText,
                createdAt = pending?.createdAt ?: now(),
                state = PendingTaskAdd.STATE_WAITING,
                problem = reason.name
            )

        store.savePendingTask(waiting)

        return Outcome.Queued(waiting, reason)
    }

    private fun saveLink(
        noteId: String,
        taskListId: String,
        taskListName: String,
        dueText: String,
        task: GoogleTask
    ): TaskLink {

        val link =
            TaskLink(
                noteId = noteId,
                taskListId = taskListId,
                taskListName = taskListName,
                taskId = task.id,
                htmlLink = task.htmlLink,
                addedAt = now(),
                dueText = dueText
            )

        store.saveTaskLink(link)
        store.removePendingTask(noteId)

        return link
    }

    /**
     * False only when Google says the task is gone. A task the user
     * ticked off is still there: DayTrace must not add it again.
     */
    private suspend fun stillThere(
        link: TaskLink
    ): Boolean =
        try {
            api.get(link.taskListId, link.taskId)?.let { !it.deleted } ?: false
        } catch (e: GoogleException) {
            when {
                // Cannot check right now: assume it is still there rather than risk a copy.
                e.kind.retryLater -> true
                // Added with another Google account, which is no longer connected.
                e.kind == GoogleException.Kind.FORBIDDEN -> false
                else -> throw e
            }
        }
}
