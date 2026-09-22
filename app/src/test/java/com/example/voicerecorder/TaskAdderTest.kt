package com.example.voicerecorder

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleException.Kind
import com.example.voicerecorder.google.GoogleIntegrationStore
import com.example.voicerecorder.google.PendingTaskAdd
import com.example.voicerecorder.google.tasks.GoogleTask
import com.example.voicerecorder.google.tasks.GoogleTaskList
import com.example.voicerecorder.google.tasks.TaskAdder
import com.example.voicerecorder.google.tasks.TaskAdder.Outcome
import com.example.voicerecorder.google.tasks.TaskMarkers
import com.example.voicerecorder.google.tasks.TasksApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

/**
 * Adding to Google Tasks at most once. Google Tasks does not let the app
 * choose the task id, so the mark in the task's notes is what has to
 * hold: repeated taps, lost answers, offline retries and a reinstall
 * must never make a second task, and a failure must never lose what the
 * user confirmed.
 */
class TaskAdderTest {

    /** Google Tasks in memory. [failWith]: every request fails with this. */
    private class FakeTasks : TasksApi {

        val tasks = mutableMapOf<String, GoogleTask>()
        var inserts = 0
        var nextId = 1
        /** The body of the last insert, so a test can check what was sent. */
        var lastBody: JSONObject? = null
        var failWith: Kind? = null
        /** The task is created, but the answer never reaches the phone. */
        var loseNextAnswer = false
        /** Google refuses to list (e.g. the scope was withdrawn). */
        var failListWith: Kind? = null

        override suspend fun lists() =
            listOf(GoogleTaskList("@default", "My Tasks", default = true))

        override suspend fun insert(taskListId: String, task: JSONObject): GoogleTask {

            failWith?.let { throw GoogleException(it, "fake $it") }

            val id = "task${nextId++}"

            inserts++
            lastBody = task

            val created =
                GoogleTask(
                    id = id,
                    title = task.optString("title"),
                    notes = task.optString("notes"),
                    status = task.optString("status"),
                    htmlLink = "https://tasks.google.com/task/$id",
                    deleted = false
                )

            tasks["$taskListId/$id"] = created

            if (loseNextAnswer) {
                loseNextAnswer = false
                throw GoogleException(Kind.OFFLINE, "answer lost")
            }

            return created
        }

        override suspend fun get(taskListId: String, taskId: String): GoogleTask? {
            failWith?.let { throw GoogleException(it, "fake $it") }
            return tasks["$taskListId/$taskId"]
        }

        override suspend fun findByMarker(taskListId: String, marker: String): GoogleTask? {
            failListWith?.let { throw GoogleException(it, "fake $it") }
            failWith?.let { throw GoogleException(it, "fake $it") }
            return tasks.entries
                .filter { it.key.startsWith("$taskListId/") }
                .map { it.value }
                .firstOrNull { !it.deleted && it.notes.contains(marker) }
        }

        /** The user deleted the task in Google Tasks. */
        fun deleteAll() {
            tasks.replaceAll { _, task -> task.copy(deleted = true) }
        }

        /** The user ticked the task off in Google Tasks. */
        fun completeAll() {
            tasks.replaceAll { _, task -> task.copy(status = GoogleTask.STATUS_COMPLETED) }
        }

        val live: Int
            get() = tasks.values.count { !it.deleted }
    }

    private val directory =
        Files.createTempDirectory("daytrace-tasks").toFile()

    private val store =
        GoogleIntegrationStore(directory)

    private val google =
        FakeTasks()

    private val adder =
        TaskAdder(google, store) { 1_000L }

    private fun request(
        noteId: String = "Voice_Recording_1#a1",
        due: String = ""
    ) =
        TaskAdder.Request(noteId, "@default", "My Tasks", due) { marker ->
            JSONObject()
                .put("title", "Finish the DBMS assignment")
                .put("notes", "Finish the DBMS assignment before Friday.\n\n$marker")
                .put("status", GoogleTask.STATUS_NEEDS_ACTION)
                .apply { if (due.isNotEmpty()) put("due", "2026-09-25T00:00:00Z") }
        }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    // A task without a deadline ---------------------------------------------

    @Test
    fun aTaskWithoutADeadlineIsAdded() = runBlocking {

        val outcome =
            adder.add(request())

        assertTrue(outcome is Outcome.Added)
        assertEquals(1, google.inserts)

        val link =
            store.taskLink("Voice_Recording_1#a1")

        assertNotNull(link)
        assertEquals("", link!!.dueText)
        assertEquals("@default", link.taskListId)
    }

    @Test
    fun aTaskWithADeadlineKeepsIt() = runBlocking {

        val outcome =
            adder.add(request(due = "Fri, 25 Sep"))

        assertTrue(outcome is Outcome.Added)
        assertEquals("Fri, 25 Sep", store.taskLink("Voice_Recording_1#a1")?.dueText)

        assertEquals("2026-09-25T00:00:00Z", google.lastBody?.optString("due"))
        assertTrue(google.tasks.values.single().notes.contains("[DayTrace:"))
    }

    @Test
    fun noDeadlineIsInventedWhenNoneWasSaid() = runBlocking {

        adder.add(request())

        assertFalse("a task without a said deadline must not get one", google.lastBody!!.has("due"))
    }

    // Duplicates -------------------------------------------------------------

    @Test
    fun addingTwiceCreatesOneTask() = runBlocking {

        val first = adder.add(request())
        assertTrue(first is Outcome.Added)

        val second = adder.add(request())
        assertTrue("the second add must not create a task", second is Outcome.AlreadyAdded)

        assertEquals(1, google.inserts)
        assertEquals(1, google.live)
    }

    @Test
    fun aLostAnswerIsNotSentAsASecondTask() = runBlocking {

        // The task reaches Google, the answer does not.
        google.loseNextAnswer = true

        val queued = adder.add(request())
        assertTrue(queued is Outcome.Queued)
        assertEquals(1, google.inserts)

        // The retry finds the task by its mark instead of making another.
        val sent = adder.send(store.pendingTaskFor("Voice_Recording_1#a1")!!)
        assertTrue(sent is Outcome.AlreadyAdded)

        assertEquals(1, google.inserts)
        assertEquals(1, google.live)
        assertNull("the waiting task is cleared", store.pendingTaskFor("Voice_Recording_1#a1"))
    }

    @Test
    fun reinstalledAppFindsItsTask() = runBlocking {

        adder.add(request())

        // A fresh install: the phone remembers nothing, Google still has the task.
        store.removeTaskLink("Voice_Recording_1#a1")

        val again = adder.add(request())

        assertTrue(again is Outcome.AlreadyAdded)
        assertEquals(1, google.inserts)
    }

    @Test
    fun aCompletedTaskIsNotAddedAgain() = runBlocking {

        adder.add(request())
        google.completeAll()

        val again = adder.add(request())

        assertTrue("ticking it off in Google Tasks does not mean it is gone", again is Outcome.AlreadyAdded)
        assertEquals(1, google.inserts)
    }

    @Test
    fun deletedInGoogleTasksCanBeAddedAgain() = runBlocking {

        adder.add(request())
        google.deleteAll()

        val again = adder.add(request())

        assertTrue(again is Outcome.Added)
        assertEquals(2, google.inserts)
        assertEquals(1, google.live)
    }

    @Test
    fun otherNotesAreIndependent() = runBlocking {

        adder.add(request("Voice_Recording_1#a1"))
        adder.add(request("Voice_Recording_1#b2"))

        assertEquals(2, google.inserts)
        assertEquals(2, google.live)

        assertNotNull(store.taskLink("Voice_Recording_1#a1"))
        assertNotNull(store.taskLink("Voice_Recording_1#b2"))
    }

    @Test
    fun theMarkIsTheSameForANoteAndDiffersBetweenNotes() {

        val one = TaskMarkers.forNote("Voice_Recording_1#a1", "@default")
        val same = TaskMarkers.forNote("Voice_Recording_1#a1", "@default")
        val other = TaskMarkers.forNote("Voice_Recording_1#b2", "@default")
        val otherList = TaskMarkers.forNote("Voice_Recording_1#a1", "list2")

        assertEquals(one, same)
        assertFalse(one == other)
        assertFalse(one == otherList)
    }

    // Permission and reconnect ----------------------------------------------

    @Test
    fun expiredAuthorizationAsksToReconnect() = runBlocking {

        google.failWith = Kind.NEEDS_CONSENT

        try {
            adder.add(request())
            fail("the user has to reconnect")
        } catch (e: GoogleException) {
            assertEquals(Kind.NEEDS_CONSENT, e.kind)
        }

        assertEquals(0, google.inserts)
        assertNull("nothing is saved when Google refuses", store.taskLink("Voice_Recording_1#a1"))
        assertNull(store.pendingTaskFor("Voice_Recording_1#a1"))
    }

    @Test
    fun revokedPermissionWhileSendingWaitsForReconnect() = runBlocking {

        google.failWith = Kind.OFFLINE
        adder.add(request())

        val pending =
            store.pendingTaskFor("Voice_Recording_1#a1")

        assertNotNull(pending)

        // The user removed DayTrace's access in their Google Account.
        google.failWith = Kind.NEEDS_CONSENT

        try {
            adder.send(pending!!)
            fail("the user has to reconnect")
        } catch (e: GoogleException) {
            assertEquals(Kind.NEEDS_CONSENT, e.kind)
        }

        val kept =
            store.pendingTaskFor("Voice_Recording_1#a1")

        assertEquals("what the user confirmed is kept", PendingTaskAdd.STATE_RECONNECT, kept?.state)
        assertEquals(Kind.NEEDS_CONSENT.name, kept?.problem)
    }

    @Test
    fun googleRefusingIsReportedAndNothingIsSaved() = runBlocking {

        google.failWith = Kind.FORBIDDEN

        try {
            adder.add(request())
            fail("a refused request is reported")
        } catch (e: GoogleException) {
            assertEquals(Kind.FORBIDDEN, e.kind)
        }

        assertNull(store.taskLink("Voice_Recording_1#a1"))
        assertNull(store.pendingTaskFor("Voice_Recording_1#a1"))
        assertEquals(0, google.inserts)
    }

    @Test
    fun aListingThatFailsForGoodIsNotTurnedIntoATask() = runBlocking {

        // Google will not say what is in the list, so DayTrace cannot know
        // whether the task is already there: it must not create one.
        google.failListWith = Kind.FORBIDDEN

        try {
            adder.add(request())
            fail("without being able to check, nothing is created")
        } catch (e: GoogleException) {
            assertEquals(Kind.FORBIDDEN, e.kind)
        }

        assertEquals(0, google.inserts)
    }

    // The note is never touched ----------------------------------------------

    @Test
    fun aWaitingTaskIsKeptExactlyAsConfirmed() = runBlocking {

        google.failWith = Kind.OFFLINE

        val queued = adder.add(request(due = "Fri, 25 Sep"))
        assertTrue(queued is Outcome.Queued)

        val pending =
            store.pendingTaskFor("Voice_Recording_1#a1")!!

        assertEquals("Fri, 25 Sep", pending.dueText)
        assertEquals(PendingTaskAdd.STATE_WAITING, pending.state)
        assertEquals("Finish the DBMS assignment", JSONObject(pending.task).getString("title"))
        assertTrue(pending.marker.startsWith("[DayTrace:"))

        // Back online, it goes out exactly once.
        google.failWith = null

        val sent = adder.send(pending)

        assertTrue(sent is Outcome.Added)
        assertEquals(1, google.inserts)
        assertNull(store.pendingTaskFor("Voice_Recording_1#a1"))
    }

    @Test
    fun confirmingTwiceWhileOfflineQueuesOnce() = runBlocking {

        google.failWith = Kind.OFFLINE

        assertTrue(adder.add(request()) is Outcome.Queued)
        assertTrue(adder.add(request()) is Outcome.Queued)

        assertEquals(1, store.pendingTasks().size)
    }

    @Test
    fun existingIsNullWhenTheTaskWasDeletedInGoogleTasks() = runBlocking {

        adder.add(request())
        assertNotNull(adder.existing("Voice_Recording_1#a1"))

        google.deleteAll()

        assertNull(adder.existing("Voice_Recording_1#a1"))
    }

    @Test
    fun offlineTheSavedTaskIsTrusted() = runBlocking {

        adder.add(request())

        // Cannot check right now: assume it is still there rather than risk a copy.
        google.failWith = Kind.OFFLINE

        assertNotNull(adder.existing("Voice_Recording_1#a1"))
    }
}
