package com.example.voicerecorder

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleException.Kind
import com.example.voicerecorder.google.GoogleIntegrationStore
import com.example.voicerecorder.google.PendingCalendarAdd
import com.example.voicerecorder.google.calendar.CalendarAdder
import com.example.voicerecorder.google.calendar.CalendarAdder.Outcome
import com.example.voicerecorder.google.calendar.CalendarApi
import com.example.voicerecorder.google.calendar.CalendarEvent
import com.example.voicerecorder.google.calendar.CalendarEventIds
import com.example.voicerecorder.google.calendar.GoogleCalendar
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

/**
 * Adding to Google Calendar at most once: repeated taps, lost answers,
 * offline retries and deleted events never make a second copy, and a
 * failure never loses what the user confirmed.
 */
class CalendarAdderTest {

    /** Google Calendar in memory. [failWith]: every request fails with this. */
    private class FakeCalendar : CalendarApi {

        val events = mutableMapOf<String, CalendarEvent>()
        var inserts = 0
        var failWith: Kind? = null
        /** The event is created, but the answer never reaches the phone. */
        var loseNextAnswer = false

        override suspend fun calendars() = listOf(GoogleCalendar("me@example.com", "Me", primary = true))

        override suspend fun insert(calendarId: String, event: JSONObject): CalendarEvent {
            failWith?.let { throw GoogleException(it, "fake $it") }
            val id = event.getString("id")
            if (events.containsKey("$calendarId/$id")) throw GoogleException(Kind.CONFLICT, "taken")
            inserts++
            val created = CalendarEvent(id, "https://calendar.google.com/event?eid=$id", "confirmed")
            events["$calendarId/$id"] = created
            if (loseNextAnswer) {
                loseNextAnswer = false
                throw GoogleException(Kind.OFFLINE, "answer lost")
            }
            return created
        }

        override suspend fun get(calendarId: String, eventId: String): CalendarEvent? {
            failWith?.let { throw GoogleException(it, "fake $it") }
            return events["$calendarId/$eventId"]
        }

        fun deleteAll() {
            events.replaceAll { _, event -> event.copy(status = "cancelled") }
        }

        val live: Int
            get() = events.values.count { !it.isCancelled }
    }

    private val directory =
        Files.createTempDirectory("daytrace-google").toFile()

    private val store =
        GoogleIntegrationStore(directory)

    private val google =
        FakeCalendar()

    private val adder =
        CalendarAdder(google, store) { 1_000L }

    private fun request(noteId: String = "Voice_Recording_1#a1") =
        CalendarAdder.Request(noteId, "me@example.com", "Me", "Tue, 22 Sep, 5:00 PM") { id ->
            JSONObject().put("id", id).put("summary", "Meeting with Rahul")
        }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun addingTwiceCreatesOneEvent() = runBlocking {
        val first = adder.add(request())
        assertTrue(first is Outcome.Added)

        val second = adder.add(request())
        assertTrue(second is Outcome.AlreadyAdded)

        assertEquals(1, google.inserts)
        assertEquals(1, google.live)
        assertEquals((first as Outcome.Added).link.eventId, (second as Outcome.AlreadyAdded).link.eventId)
        assertEquals(first.link, store.calendarLink("Voice_Recording_1#a1"))
    }

    @Test
    fun aLostAnswerIsNotSentAsASecondEvent() = runBlocking {
        // Created at Google, but the phone never heard back: kept as waiting.
        google.loseNextAnswer = true
        val first = adder.add(request())
        assertTrue(first is Outcome.Queued)

        // Sending it again finds the event under the same id.
        val sent = adder.send(store.pendingFor("Voice_Recording_1#a1")!!)
        assertTrue(sent is Outcome.AlreadyAdded)

        assertEquals(1, google.inserts)
        assertEquals(1, google.live)
        assertNull(store.pendingFor("Voice_Recording_1#a1"))
        assertNotNull(store.calendarLink("Voice_Recording_1#a1"))
    }

    @Test
    fun reinstalledAppFindsItsEvent() = runBlocking {
        adder.add(request())

        // Same Google account, but DayTrace's own records are gone.
        val fresh = CalendarAdder(google, GoogleIntegrationStore(Files.createTempDirectory("fresh").toFile()))
        assertTrue(fresh.add(request()) is Outcome.AlreadyAdded)
        assertEquals(1, google.live)
    }

    @Test
    fun offlineIsKeptAndSentLater() = runBlocking {
        google.failWith = Kind.OFFLINE

        val queued = adder.add(request())
        assertTrue(queued is Outcome.Queued)
        assertEquals(Kind.OFFLINE, (queued as Outcome.Queued).reason)
        assertEquals(0, google.inserts)

        // Tapping again while waiting does not queue a second one.
        assertTrue(adder.add(request()) is Outcome.Queued)
        assertEquals(1, store.pending().size)

        val waiting = store.pendingFor("Voice_Recording_1#a1")!!
        assertEquals(CalendarEventIds.forNote("Voice_Recording_1#a1", "me@example.com", 0), waiting.eventId)
        assertEquals("Meeting with Rahul", JSONObject(waiting.event).getString("summary"))

        // Back online.
        google.failWith = null
        val sent = adder.send(waiting)
        assertTrue(sent is Outcome.Added)
        assertEquals(waiting.eventId, (sent as Outcome.Added).link.eventId)
        assertEquals(1, google.live)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun deletedInGoogleCalendarCanBeAddedAgain() = runBlocking {
        val first = adder.add(request()) as Outcome.Added
        google.deleteAll()

        val again = adder.add(request())
        assertTrue(again is Outcome.Added)
        assertNotEquals(first.link.eventId, (again as Outcome.Added).link.eventId)
        assertEquals(1, again.link.generation)
        assertEquals(1, google.live)
    }

    @Test
    fun whenOfflineTheSavedEventIsTrusted() = runBlocking {
        adder.add(request())
        google.failWith = Kind.OFFLINE

        // Cannot check with Google: never risk a second copy.
        assertTrue(adder.add(request()) is Outcome.AlreadyAdded)
        assertNotNull(adder.existing("Voice_Recording_1#a1"))
        assertEquals(1, google.inserts)
    }

    @Test
    fun expiredAuthorizationAsksToReconnect() = runBlocking {
        google.failWith = Kind.NEEDS_CONSENT

        try {
            adder.add(request())
            fail("Should ask the user to reconnect")
        } catch (e: GoogleException) {
            assertEquals(Kind.NEEDS_CONSENT, e.kind)
        }

        // Nothing half-done is left behind.
        assertNull(store.calendarLink("Voice_Recording_1#a1"))
        assertTrue(store.pending().isEmpty())

        // After reconnecting it works.
        google.failWith = null
        assertTrue(adder.add(request()) is Outcome.Added)
    }

    @Test
    fun aWaitingEventWaitsForReconnect() = runBlocking {
        google.failWith = Kind.OFFLINE
        adder.add(request())

        google.failWith = Kind.NEEDS_CONSENT
        try {
            adder.send(store.pendingFor("Voice_Recording_1#a1")!!)
            fail("Should ask the user to reconnect")
        } catch (e: GoogleException) {
            assertEquals(Kind.NEEDS_CONSENT, e.kind)
        }

        // Still there, marked for reconnecting: what the user confirmed is not lost.
        assertEquals(PendingCalendarAdd.STATE_RECONNECT, store.pendingFor("Voice_Recording_1#a1")!!.state)
    }

    @Test
    fun googleRefusingIsReportedAndNothingIsSaved() = runBlocking {
        google.failWith = Kind.NOT_FOUND

        try {
            adder.add(request())
            fail("Should report the missing calendar")
        } catch (e: GoogleException) {
            assertEquals(Kind.NOT_FOUND, e.kind)
        }

        assertNull(store.calendarLink("Voice_Recording_1#a1"))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun otherNotesAreIndependent() = runBlocking {
        adder.add(request("Voice_Recording_1#a1"))
        assertTrue(adder.add(request("Voice_Recording_1#b2")) is Outcome.Added)
        assertEquals(2, google.live)
    }
}
