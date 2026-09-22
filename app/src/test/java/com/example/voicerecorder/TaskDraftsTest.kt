package com.example.voicerecorder

import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.google.tasks.TaskCheck
import com.example.voicerecorder.google.tasks.TaskDraft
import com.example.voicerecorder.google.tasks.TaskDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Turning a Remember note into the task the user is shown.
 *
 * The rule that matters most: a deadline is only ever set when the
 * recording actually said one. DayTrace never makes one up, and never
 * turns "some time soon" into a date.
 */
class TaskDraftsTest {

    private val defaults =
        TaskDrafts.Defaults(taskListId = "@default", taskListName = "My Tasks", includeContext = true)

    private fun suggestion(
        kind: String = EventSuggestion.KIND_TASK,
        sure: Boolean = true,
        date: String = "",
        startTime: String = "",
        whenWords: String = "",
        checkedAt: Long = 5_000L
    ) =
        EventSuggestion(
            noteId = "Voice_Recording_1#a1",
            kind = kind,
            sure = sure,
            title = "",
            whenWords = whenWords,
            date = date,
            startTime = startTime,
            timeWords = "",
            endTime = "",
            durationMinutes = 0,
            location = "",
            checkedAt = checkedAt
        )

    private fun draft(
        noteTitle: String = "Finish the DBMS assignment",
        noteText: String = "Finish the DBMS assignment before Friday.",
        noteDueDate: String = "",
        noteDueTime: String = "",
        suggestion: EventSuggestion? = suggestion()
    ): TaskDraft =
        TaskDrafts.from(
            noteId = "Voice_Recording_1#a1",
            noteTitle = noteTitle,
            noteText = noteText,
            noteDueDate = noteDueDate,
            noteDueTime = noteDueTime,
            suggestion = suggestion,
            defaults = defaults
        )

    // Deadlines are never invented -------------------------------------------

    @Test
    fun aTaskWithNoDeadlineGetsNone() {

        val draft =
            draft(noteText = "Buy a new notebook.", suggestion = suggestion())

        assertNull(draft.due)
        assertFalse(draft.hasDeadline)
        assertTrue(draft.checks.any { it is TaskCheck.NoDeadline })
    }

    @Test
    fun aDayThatWasSaidIsKept() {

        val draft =
            draft(suggestion = suggestion(date = "2026-09-25", whenWords = "before Friday"))

        assertEquals(LocalDate.of(2026, 9, 25), draft.due)
        assertTrue(draft.checks.any { it is TaskCheck.DayWorkedOut })
    }

    @Test
    fun theNotesOwnDeadlineIsUsedWhenGeminiGaveNone() {

        val draft =
            draft(noteDueDate = "2026-10-02", suggestion = suggestion())

        assertEquals("nothing the user said is lost", LocalDate.of(2026, 10, 2), draft.due)
    }

    @Test
    fun aDateThatCannotBeReadIsNotADeadline() {

        val draft =
            draft(noteDueDate = "some time next week", suggestion = suggestion(date = "not a date"))

        assertNull(draft.due)
    }

    @Test
    fun withoutGeminiOnlyTheNoteIsUsed() {

        val draft =
            draft(noteDueDate = "2026-09-25", suggestion = null)

        assertEquals(LocalDate.of(2026, 9, 25), draft.due)
        assertEquals(TaskDraft.Verdict.UNKNOWN, draft.verdict)
        assertTrue(draft.checks.any { it is TaskCheck.NotChecked })
    }

    @Test
    fun anAnswerThatWasNeverCheckedCountsAsNoAnswer() {

        val draft =
            draft(suggestion = suggestion(checkedAt = 0L))

        assertEquals(TaskDraft.Verdict.UNKNOWN, draft.verdict)
    }

    // Google Tasks keeps only the day ----------------------------------------

    @Test
    fun aTimeThatWasSaidIsKeptInTheNoteNotThrownAway() {

        val draft =
            draft(
                noteText = "Send the APK to Shlok tonight.",
                suggestion = suggestion(date = "2026-09-22", startTime = "20:00")
            )

        assertEquals(LocalTime.of(20, 0), draft.dueTime)
        assertTrue(draft.checks.any { it is TaskCheck.TimeInNotes })

        val json =
            TaskDrafts.toJson(draft, "[DayTrace:abc123]", "22 Sep 2026, 05:12 PM")

        // The am/pm marker's case follows the device's locale data.
        assertTrue(
            "the time the API drops is written into the notes",
            json.getString("notes").uppercase().contains("8:00 PM")
        )

        assertEquals("only the day reaches Google", "2026-09-22T00:00:00Z", json.getString("due"))
    }

    @Test
    fun noDueFieldIsSentWhenThereIsNoDeadline() {

        val json =
            TaskDrafts.toJson(draft(suggestion = suggestion()), "[DayTrace:abc123]", "22 Sep 2026, 05:12 PM")

        assertFalse("an absent deadline must stay absent", json.has("due"))
    }

    // What the task carries ---------------------------------------------------

    @Test
    fun theTaskCarriesTheTitleTheContextAndTheMark() {

        val json =
            TaskDrafts.toJson(
                draft(noteText = "Finish the DBMS assignment before Friday, the faculty checks the demo."),
                "[DayTrace:abc123]",
                "22 Sep 2026, 05:12 PM"
            )

        assertEquals("Finish the DBMS assignment", json.getString("title"))

        val notes =
            json.getString("notes")

        assertTrue("the note's own words give the task its context", notes.contains("the faculty checks the demo"))
        assertTrue("the recording time is kept", notes.contains("22 Sep 2026, 05:12 PM"))
        assertTrue("the mark is last, so a retry can find it", notes.trim().endsWith("[DayTrace:abc123]"))
        assertEquals("needsAction", json.getString("status"))
    }

    @Test
    fun theContextIsLeftOutWhenTheUserTurnedItOff() {

        val draft =
            TaskDrafts.from(
                noteId = "Voice_Recording_1#a1",
                noteTitle = "Buy a new notebook",
                noteText = "Buy a new notebook for the DBMS lab.",
                noteDueDate = "",
                noteDueTime = "",
                suggestion = suggestion(),
                defaults = TaskDrafts.Defaults("@default", "My Tasks", includeContext = false)
            )

        val notes =
            TaskDrafts.toJson(draft, "[DayTrace:abc123]", "").getString("notes")

        assertFalse(notes.contains("for the DBMS lab"))
        assertTrue("the mark is still there", notes.contains("[DayTrace:abc123]"))
    }

    @Test
    fun aNoteWithNoTitleFallsBackToItsOwnWords() {

        val draft =
            draft(noteTitle = "", noteText = "Buy a new notebook.")

        assertEquals("Buy a new notebook.", draft.title)
    }

    // What DayTrace thinks it is ----------------------------------------------

    @Test
    fun aClearToDoIsShownAsOne() {
        assertEquals(TaskDraft.Verdict.TASK, draft(suggestion = suggestion(sure = true)).verdict)
    }

    @Test
    fun anUnsureToDoSaysSo() {
        assertEquals(TaskDraft.Verdict.MAYBE_TASK, draft(suggestion = suggestion(sure = false)).verdict)
    }

    @Test
    fun somethingGeminiCallsAnEventSaysSo() {

        val draft =
            draft(suggestion = suggestion(kind = EventSuggestion.KIND_EVENT))

        assertEquals(TaskDraft.Verdict.LOOKS_LIKE_EVENT, draft.verdict)
    }
}
