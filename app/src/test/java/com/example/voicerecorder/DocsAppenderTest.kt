package com.example.voicerecorder

import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleException.Kind
import com.example.voicerecorder.google.GoogleIntegrationStore
import com.example.voicerecorder.google.docs.DocsApi
import com.example.voicerecorder.google.docs.DriveDocument
import com.example.voicerecorder.google.docs.DocsAppender
import com.example.voicerecorder.google.docs.DocsAppender.Outcome
import com.example.voicerecorder.google.docs.DocumentMarkers
import com.example.voicerecorder.google.docs.DocumentText
import com.example.voicerecorder.google.docs.GoogleDocument
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
 * Appending notes to a DayTrace document: the same note is never written
 * to the same document twice, the document itself is what decides what is
 * already in it, and a Google failure never loses the note.
 */
class DocsAppenderTest {

    /** Google Docs in memory: a document is just its title and its text. */
    private class FakeDocs : DocsApi {

        class Doc(var title: String, var text: String = "")

        val docs = mutableMapOf<String, Doc>()
        var creates = 0
        var updates = 0
        var lists = 0
        var nextId = 1
        var failWith: Kind? = null
        /** The text is written, but the answer never reaches the phone. */
        var loseNextAnswer = false

        override suspend fun create(title: String): GoogleDocument {
            failWith?.let { throw GoogleException(it, "fake $it") }
            creates++
            val id = "doc${nextId++}"
            docs[id] = Doc(title)
            return GoogleDocument(id, title, appendIndex = 1, text = "")
        }

        override suspend fun listDayTraceDocuments(): List<DriveDocument> {
            failWith?.let { throw GoogleException(it, "fake $it") }
            lists++
            // Google only ever lists the files this app made (drive.file).
            return docs.map { (id, doc) -> DriveDocument(id, doc.title, createdAt = 2_000L) }
        }

        override suspend fun get(documentId: String): GoogleDocument? {
            failWith?.let { throw GoogleException(it, "fake $it") }
            val doc = docs[documentId] ?: return null
            return GoogleDocument(documentId, doc.title, appendIndex = doc.text.length + 1, text = doc.text)
        }

        override suspend fun batchUpdate(documentId: String, requests: JSONArray) {

            failWith?.let { throw GoogleException(it, "fake $it") }

            val doc =
                docs[documentId] ?: throw GoogleException(Kind.NOT_FOUND, "gone")

            updates++

            // Only the inserted text matters here; the styles are decoration.
            for (index in 0 until requests.length()) {
                requests.optJSONObject(index)
                    ?.optJSONObject("insertText")
                    ?.optString("text")
                    ?.let { doc.text += it }
            }

            if (loseNextAnswer) {
                loseNextAnswer = false
                throw GoogleException(Kind.OFFLINE, "answer lost")
            }
        }

        /** The user deleted the document in Google Drive. */
        fun deleteAll() = docs.clear()
    }

    private val directory =
        Files.createTempDirectory("daytrace-docs").toFile()

    private val store =
        GoogleIntegrationStore(directory)

    private val google =
        FakeDocs()

    private val appender =
        DocsAppender(google, store) { 1_000L }

    private fun entry(
        title: String = "An AI study planner",
        body: String = "An idea for an app that plans what to study each day.",
        recorded: String = "22 Sep 2026, 05:12 PM"
    ) =
        DocumentText.Entry(
            category = "Idea",
            recordedText = recorded,
            title = title,
            body = body,
            tags = listOf("study", "app")
        )

    private fun request(
        noteId: String = "Voice_Recording_1#a1",
        documentId: String,
        entry: DocumentText.Entry = entry()
    ) =
        DocsAppender.Request(noteId, documentId, entry)

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    // Creating and selecting -------------------------------------------------

    @Test
    fun aDocumentIsCreatedAndRemembered() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        assertEquals("DayTrace Ideas", document.title)
        assertEquals("Idea", document.category)
        assertEquals(1, google.creates)

        assertEquals(listOf(document.documentId), store.documents().map { it.documentId })
    }

    @Test
    fun aDocumentIsNotCreatedForEveryNote() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request("Voice_Recording_1#a1", document.documentId))
        appender.append(request("Voice_Recording_1#b2", document.documentId))

        assertEquals("both notes go into the one document", 1, google.creates)
        assertEquals(2, google.updates)
    }

    @Test
    fun documentsAreOfferedByCategory() = runBlocking {

        val ideas = appender.createDocument("DayTrace Ideas", "Idea")
        appender.createDocument("DayTrace Thoughts", "Thoughts")

        assertEquals(listOf(ideas.documentId), appender.documents("Idea").map { it.documentId })
        assertEquals(2, appender.documents().size)
    }

    // Appending --------------------------------------------------------------

    @Test
    fun theNoteIsWrittenWithItsDateCategoryAndTags() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        assertTrue(appender.append(request(documentId = document.documentId)) is Outcome.Appended)

        val text =
            google.docs[document.documentId]!!.text

        assertTrue("the category is kept", text.contains("Idea"))
        assertTrue("the recording time is kept", text.contains("22 Sep 2026, 05:12 PM"))
        assertTrue(text.contains("An AI study planner"))
        assertTrue(text.contains("An idea for an app that plans what to study each day."))
        assertTrue(text.contains("study, app"))
        assertTrue(text.contains(DocumentMarkers.forNote("Voice_Recording_1#a1")))
    }

    @Test
    fun theRecordingTimeIsKeptNotTheTimeOfWriting() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(
            request(documentId = document.documentId, entry = entry(recorded = "3 Jan 2026, 09:30 AM"))
        )

        val text =
            google.docs[document.documentId]!!.text

        assertTrue(text.contains("3 Jan 2026, 09:30 AM"))
    }

    @Test
    fun entriesStayInTheOrderTheyWereAdded() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request("Voice_Recording_1#a1", document.documentId, entry(title = "First idea")))
        appender.append(request("Voice_Recording_2#b2", document.documentId, entry(title = "Second idea")))
        appender.append(request("Voice_Recording_3#c3", document.documentId, entry(title = "Third idea")))

        val text =
            google.docs[document.documentId]!!.text

        assertTrue(
            "the newest entry is last",
            text.indexOf("First idea") < text.indexOf("Second idea") &&
                    text.indexOf("Second idea") < text.indexOf("Third idea")
        )
    }

    @Test
    fun anIdeaAndAThoughtGoToTheirOwnDocuments() = runBlocking {

        val ideas = appender.createDocument("DayTrace Ideas", "Idea")
        val thoughts = appender.createDocument("DayTrace Thoughts", "Thoughts")

        appender.append(
            request("Voice_Recording_1#a1", ideas.documentId, entry(title = "An AI study planner"))
        )

        appender.append(
            DocsAppender.Request(
                "Voice_Recording_2#b2",
                thoughts.documentId,
                DocumentText.Entry(
                    category = "Thoughts",
                    recordedText = "23 Sep 2026, 08:00 AM",
                    title = "I work better without my phone",
                    body = "I realized I study better when my phone is in another room.",
                    tags = listOf("focus")
                )
            )
        )

        val ideaText = google.docs[ideas.documentId]!!.text
        val thoughtText = google.docs[thoughts.documentId]!!.text

        assertTrue(ideaText.contains("An AI study planner"))
        assertFalse("the idea did not leak into the thoughts", thoughtText.contains("An AI study planner"))

        assertTrue(thoughtText.contains("I work better without my phone"))
        assertTrue(thoughtText.contains("Thoughts"))
        assertTrue(thoughtText.contains("focus"))
    }

    @Test
    fun addingANoteLeavesWhatIsAlreadyInTheDocumentUntouched() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        // Something the user wrote in the document themselves.
        google.docs[document.documentId]!!.text = "My own heading\nSomething I typed myself.\n"

        val before =
            google.docs[document.documentId]!!.text

        appender.append(request(documentId = document.documentId))
        appender.append(request("Voice_Recording_1#b2", document.documentId, entry(title = "Another idea")))

        val after =
            google.docs[document.documentId]!!.text

        assertTrue("the user's own writing is still there", after.startsWith(before))
        assertTrue(after.contains("An AI study planner"))
        assertTrue(after.contains("Another idea"))
    }

    @Test
    fun aLongNoteIsWrittenWhole() = runBlocking {

        val document =
            appender.createDocument("DayTrace Thoughts", "Thoughts")

        val long =
            (1..400).joinToString(" ") { "word$it" }

        appender.append(request(documentId = document.documentId, entry = entry(body = long)))

        assertTrue("nothing is cut off", google.docs[document.documentId]!!.text.contains(long))
    }

    // Duplicates -------------------------------------------------------------

    @Test
    fun appendingTwiceWritesOnce() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        assertTrue(appender.append(request(documentId = document.documentId)) is Outcome.Appended)

        val again =
            appender.append(request(documentId = document.documentId))

        assertTrue("the second append must not write", again is Outcome.AlreadyThere)
        assertEquals(1, google.updates)
    }

    @Test
    fun aLostAnswerIsNotWrittenTwice() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        // The text is written, the answer does not come back.
        google.loseNextAnswer = true

        try {
            appender.append(request(documentId = document.documentId))
            fail("the failure is reported")
        } catch (e: GoogleException) {
            assertEquals(Kind.OFFLINE, e.kind)
        }

        assertEquals(1, google.updates)

        // The retry sees its own mark in the document and writes nothing.
        val again =
            appender.append(request(documentId = document.documentId))

        assertTrue(again is Outcome.AlreadyThere)
        assertEquals(1, google.updates)
    }

    @Test
    fun reinstalledAppSeesItsOwnEntry() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request(documentId = document.documentId))

        // A fresh install: the phone forgot, the document still has the entry.
        store.removeDocumentEntries(document.documentId)

        val again =
            appender.append(request(documentId = document.documentId))

        assertTrue("the document itself is what decides", again is Outcome.AlreadyThere)
        assertEquals(1, google.updates)
    }

    @Test
    fun theSameNoteCanGoToADifferentDocument() = runBlocking {

        val ideas = appender.createDocument("DayTrace Ideas", "Idea")
        val projects = appender.createDocument("DayTrace Projects", "")

        assertTrue(appender.append(request(documentId = ideas.documentId)) is Outcome.Appended)
        assertTrue(appender.append(request(documentId = projects.documentId)) is Outcome.Appended)

        assertEquals(2, google.updates)
    }

    @Test
    fun differentNotesAreIndependent() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request("Voice_Recording_1#a1", document.documentId))
        appender.append(request("Voice_Recording_1#b2", document.documentId))

        assertEquals(2, google.updates)
        assertTrue(appender.alreadyAppended("Voice_Recording_1#a1", document.documentId))
        assertTrue(appender.alreadyAppended("Voice_Recording_1#b2", document.documentId))
        assertFalse(appender.alreadyAppended("Voice_Recording_1#c3", document.documentId))
    }

    @Test
    fun rewritingOneNoteDoesNotForgetTheOthers() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request("Voice_Recording_1#a1", document.documentId))
        appender.append(request("Voice_Recording_1#b2", document.documentId, entry(title = "Another idea")))

        // Someone deleted the first note's part of the document by hand.
        val doc = google.docs[document.documentId]!!
        doc.text = doc.text.replace(DocumentMarkers.forNote("Voice_Recording_1#a1"), "")

        // It is written again, as the user asked.
        assertTrue(appender.append(request("Voice_Recording_1#a1", document.documentId)) is Outcome.Appended)

        // The other note must still count as written.
        assertTrue(
            "the second note's entry survives the first being rewritten",
            appender.alreadyAppended("Voice_Recording_1#b2", document.documentId)
        )

        assertTrue(
            appender.append(request("Voice_Recording_1#b2", document.documentId, entry(title = "Another idea")))
                    is Outcome.AlreadyThere
        )
    }

    /**
     * A reinstall wipes the app's own storage. Without asking Google what
     * DayTrace already made, the user would be offered a new document and
     * end up with two "DayTrace Ideas".
     */
    @Test
    fun afterAReinstallTheDocumentIsFoundAgainInsteadOfRemade() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request("Voice_Recording_1#a1", document.documentId))

        // A fresh install: everything DayTrace knew is gone.
        store.removeDocument(document.documentId)
        store.removeDocumentEntries(document.documentId)
        assertTrue(appender.documents().isEmpty())

        assertEquals("the document DayTrace made is found again", 1, appender.recoverDocuments())

        val recovered =
            appender.documents().single()

        assertEquals(document.documentId, recovered.documentId)
        assertEquals("DayTrace Ideas", recovered.title)
        assertEquals("nothing new was made", 1, google.creates)
    }

    @Test
    fun aRecoveredDocumentStillRefusesTheNoteItAlreadyHas() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request("Voice_Recording_1#a1", document.documentId))

        store.removeDocument(document.documentId)
        store.removeDocumentEntries(document.documentId)
        appender.recoverDocuments()

        val again =
            appender.append(request("Voice_Recording_1#a1", document.documentId))

        assertTrue("the mark in the document is what decides", again is Outcome.AlreadyThere)
        assertEquals(1, google.updates)
    }

    @Test
    fun recoveringDoesNotDuplicateWhatIsAlreadyKnown() = runBlocking {

        appender.createDocument("DayTrace Ideas", "Idea")
        appender.createDocument("DayTrace Thoughts", "Thoughts")

        assertEquals("nothing new to recover", 0, appender.recoverDocuments())
        assertEquals(2, appender.documents().size)
    }

    @Test
    fun aRecoveredDocumentIsOfferedForEveryCategory() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        store.removeDocument(document.documentId)
        appender.recoverDocuments()

        // Drive does not say what it collects, so it is offered everywhere
        // rather than hidden from the category the user is sending.
        assertEquals(1, appender.documents("Idea").size)
        assertEquals(1, appender.documents("Thoughts").size)
    }

    @Test
    fun theMarkIsTheSameForANoteAndDiffersBetweenNotes() {

        assertEquals(
            DocumentMarkers.forNote("Voice_Recording_1#a1"),
            DocumentMarkers.forNote("Voice_Recording_1#a1")
        )

        assertFalse(
            DocumentMarkers.forNote("Voice_Recording_1#a1") == DocumentMarkers.forNote("Voice_Recording_1#b2")
        )
    }

    // Failures ---------------------------------------------------------------

    @Test
    fun expiredAuthorizationIsReportedAndNothingIsWritten() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        google.failWith = Kind.NEEDS_CONSENT

        try {
            appender.append(request(documentId = document.documentId))
            fail("the user has to reconnect")
        } catch (e: GoogleException) {
            assertEquals(Kind.NEEDS_CONSENT, e.kind)
        }

        assertEquals(0, google.updates)
        assertFalse(appender.alreadyAppended("Voice_Recording_1#a1", document.documentId))
    }

    @Test
    fun revokedAccessIsReportedAndNothingIsWritten() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        google.failWith = Kind.FORBIDDEN

        try {
            appender.append(request(documentId = document.documentId))
            fail("a refused request is reported")
        } catch (e: GoogleException) {
            assertEquals(Kind.FORBIDDEN, e.kind)
        }

        assertEquals(0, google.updates)
    }

    @Test
    fun aDeletedDocumentIsForgottenNotRecreated() = runBlocking {

        val document =
            appender.createDocument("DayTrace Ideas", "Idea")

        appender.append(request(documentId = document.documentId))

        // The user deleted it in Google Drive.
        google.deleteAll()

        val outcome =
            appender.append(request("Voice_Recording_1#b2", document.documentId))

        assertTrue(outcome is Outcome.DocumentGone)
        assertNull("DayTrace stops offering it", store.document(document.documentId))
        assertEquals("nothing new is created behind the user's back", 1, google.creates)
    }

    @Test
    fun appendingToADocumentDayTraceNeverMadeDoesNothing() = runBlocking {

        val outcome =
            appender.append(request(documentId = "someone-elses-doc"))

        assertTrue(outcome is Outcome.DocumentGone)
        assertEquals(0, google.updates)
    }

    // What gets written ------------------------------------------------------

    @Test
    fun theRequestsInsertOnceAndStyleWhatWasInserted() {

        val requests =
            DocumentText.requests(entry(), "[DayTrace:abc123]", appendIndex = 1)

        val inserts =
            (0 until requests.length()).count { requests.getJSONObject(it).has("insertText") }

        assertEquals("one insert, so the offsets below stay true", 1, inserts)

        val text =
            requests.getJSONObject(0).getJSONObject("insertText").getString("text")

        // Every styled range must sit inside the text that was inserted.
        val end = 1 + text.length

        for (index in 1 until requests.length()) {

            val request =
                requests.getJSONObject(index)

            val range =
                request.optJSONObject("updateParagraphStyle")?.optJSONObject("range")
                    ?: request.getJSONObject("updateTextStyle").getJSONObject("range")

            assertTrue("a style range must start inside the new text", range.getInt("startIndex") >= 1)
            assertTrue("a style range must end inside the new text", range.getInt("endIndex") <= end)
            assertTrue(range.getInt("startIndex") < range.getInt("endIndex"))
        }
    }

    @Test
    fun theTitleIsStyledAsAHeadingSoTheNewestEntryStandsOut() {

        val requests =
            DocumentText.requests(entry(), "[DayTrace:abc123]", appendIndex = 1)

        val text =
            requests.getJSONObject(0).getJSONObject("insertText").getString("text")

        val heading =
            (0 until requests.length())
                .map { requests.getJSONObject(it) }
                .firstOrNull { it.has("updateParagraphStyle") }

        assertNotNull("the title is a heading", heading)

        val range =
            heading!!.getJSONObject("updateParagraphStyle").getJSONObject("range")

        val titleAt =
            text.indexOf("An AI study planner") + 1

        assertEquals("the heading covers the title", titleAt, range.getInt("startIndex"))
        assertEquals(titleAt + "An AI study planner".length, range.getInt("endIndex"))
    }

    @Test
    fun anEmptyDocumentIsAppendedToAtTheStart() {

        val requests =
            DocumentText.requests(entry(), "[DayTrace:abc123]", appendIndex = 1)

        assertEquals(
            1,
            requests.getJSONObject(0).getJSONObject("insertText").getJSONObject("location").getInt("index")
        )
    }
}
