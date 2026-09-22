package com.example.voicerecorder.google.tasks

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleRestClient
import org.json.JSONObject
import java.security.MessageDigest

/** A Google Tasks list the user can add tasks to. */
data class GoogleTaskList(
    val id: String,
    val name: String,
    /** The list every account starts with ("My Tasks"). */
    val default: Boolean
)

/** A task in Google Tasks, as far as DayTrace needs to know it. */
data class GoogleTask(
    val id: String,
    val title: String,
    val notes: String,
    val status: String,
    val htmlLink: String,
    /** Deleted in Google Tasks (Google keeps deleted tasks for a while). */
    val deleted: Boolean
) {

    val isCompleted: Boolean
        get() = status == STATUS_COMPLETED

    companion object {
        const val STATUS_NEEDS_ACTION = "needsAction"
        const val STATUS_COMPLETED = "completed"
    }
}

/** The Google Tasks requests DayTrace makes. Tests use a fake. */
interface TasksApi {

    /** The user's task lists; the default one first. */
    suspend fun lists(): List<GoogleTaskList>

    suspend fun insert(
        taskListId: String,
        task: JSONObject
    ): GoogleTask

    /** Null when there is no task with this id (never made, or deleted for good). */
    suspend fun get(
        taskListId: String,
        taskId: String
    ): GoogleTask?

    /**
     * The task in this list whose notes carry [marker], or null. This is
     * how DayTrace finds a task it created but never got the answer for.
     */
    suspend fun findByMarker(
        taskListId: String,
        marker: String
    ): GoogleTask?
}

/** Google Tasks API v1. */
class RestTasksApi(
    private val rest: GoogleRestClient
) : TasksApi {

    override suspend fun lists(): List<GoogleTaskList> {

        val lists =
            mutableListOf<GoogleTaskList>()

        var pageToken: String? = null

        do {
            val page =
                rest.get(
                    "$BASE/users/@me/lists?maxResults=100" +
                            pageToken?.let { "&pageToken=${GoogleRestClient.encode(it)}" }.orEmpty()
                )

            val items =
                page.optJSONArray("items")

            for (index in 0 until (items?.length() ?: 0)) {

                val item =
                    items?.optJSONObject(index) ?: continue

                val id =
                    item.optString("id")

                if (id.isEmpty()) {
                    continue
                }

                lists += GoogleTaskList(
                    id = id,
                    name = item.optString("title").ifEmpty { id },
                    // Google returns the account's starting list first.
                    default = lists.isEmpty()
                )
            }

            pageToken = page.optString("nextPageToken").ifEmpty { null }

        } while (pageToken != null)

        return lists
    }

    override suspend fun insert(
        taskListId: String,
        task: JSONObject
    ): GoogleTask =
        toTask(rest.post("$BASE/lists/${GoogleRestClient.encode(taskListId)}/tasks", task))

    override suspend fun get(
        taskListId: String,
        taskId: String
    ): GoogleTask? =
        try {
            toTask(
                rest.get("$BASE/lists/${GoogleRestClient.encode(taskListId)}/tasks/${GoogleRestClient.encode(taskId)}")
            )
        } catch (e: GoogleException) {
            if (e.kind == GoogleException.Kind.NOT_FOUND) null else throw e
        }

    override suspend fun findByMarker(
        taskListId: String,
        marker: String
    ): GoogleTask? {

        var pageToken: String? = null

        do {
            // Completed and hidden ones too: a task the user already ticked
            // off still counts as added, and must not be created again.
            val page =
                rest.get(
                    "$BASE/lists/${GoogleRestClient.encode(taskListId)}/tasks" +
                            "?maxResults=100&showCompleted=true&showHidden=true&showDeleted=false" +
                            pageToken?.let { "&pageToken=${GoogleRestClient.encode(it)}" }.orEmpty()
                )

            val items =
                page.optJSONArray("items")

            for (index in 0 until (items?.length() ?: 0)) {

                val item =
                    items?.optJSONObject(index) ?: continue

                if (item.optString("notes").contains(marker)) {
                    return toTask(item)
                }
            }

            pageToken = page.optString("nextPageToken").ifEmpty { null }

        } while (pageToken != null)

        return null
    }

    private fun toTask(
        json: JSONObject
    ): GoogleTask =
        GoogleTask(
            id = json.getString("id"),
            title = json.optString("title"),
            notes = json.optString("notes"),
            status = json.optString("status", GoogleTask.STATUS_NEEDS_ACTION),
            htmlLink = json.optString("webViewLink"),
            deleted = json.optBoolean("deleted")
        )

    private companion object {

        const val BASE =
            "https://tasks.googleapis.com/tasks/v1"
    }
}

/**
 * The hidden mark DayTrace puts at the end of a task's notes.
 *
 * Google Tasks, unlike Google Calendar, does not let the app choose the
 * task id, so there is no id Google itself would refuse a second time.
 * The mark takes its place: it is worked out from the note, so the same
 * note always produces the same mark, and a task carrying it is a task
 * DayTrace already created for that note.
 */
object TaskMarkers {

    /** Short enough to sit quietly at the end of the notes field. */
    private const val LENGTH = 12

    private const val PREFIX = "[DayTrace:"

    private const val SUFFIX = "]"

    fun forNote(
        noteId: String,
        taskListId: String
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("daytrace-task|$noteId|$taskListId".toByteArray(Charsets.UTF_8))

        val token =
            digest.take(LENGTH / 2).joinToString("") { "%02x".format(it) }

        return "$PREFIX$token$SUFFIX"
    }
}
