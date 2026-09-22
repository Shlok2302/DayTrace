package com.example.voicerecorder

import com.example.voicerecorder.google.GoogleErrors
import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleException.Kind
import com.example.voicerecorder.google.GoogleRestClient
import com.example.voicerecorder.google.GoogleTokens
import com.example.voicerecorder.google.HttpAnswer
import com.example.voicerecorder.google.Transport
import com.example.voicerecorder.google.calendar.EventDetector
import com.example.voicerecorder.summary.Note
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.UnknownHostException

/**
 * Authorization and errors, shared by every Google service: an expired
 * token is refreshed once, and every failure has a kind the screens can
 * explain.
 */
class GoogleRestClientTest {

    private class Tokens(vararg tokens: String) : GoogleTokens {
        private val left = ArrayDeque(tokens.toList())
        val forgotten = mutableListOf<String>()
        override suspend fun token(scopes: List<String>) = left.removeFirstOrNull() ?: "last"
        override suspend fun forget(token: String) { forgotten += token }
    }

    private fun client(tokens: GoogleTokens, answer: (String) -> HttpAnswer) =
        GoogleRestClient(tokens, listOf("scope"), Transport { _, _, token, _ -> answer(token) })

    private fun error(code: Int, reason: String = "") =
        GoogleErrors.fromHttp(
            code,
            JSONObject().put(
                "error",
                JSONObject().put("code", code).put("errors", JSONArray().put(JSONObject().put("reason", reason)))
            ).toString()
        ).kind

    @Test
    fun anExpiredTokenIsRefreshedOnce() = runBlocking {
        val tokens = Tokens("old", "new")
        val answer = client(tokens) { token ->
            if (token == "old") HttpAnswer(401, "{}") else HttpAnswer(200, """{"id":"x"}""")
        }.get("https://example.com")

        assertEquals("x", answer.getString("id"))
        assertEquals(listOf("old"), tokens.forgotten)
    }

    @Test
    fun aRevokedAccessNeedsReconnecting() = runBlocking {
        try {
            client(Tokens("a", "b")) { HttpAnswer(401, "{}") }.get("https://example.com")
            fail()
        } catch (e: GoogleException) {
            assertEquals(Kind.NEEDS_CONSENT, e.kind)
        }
    }

    @Test
    fun noConnectionIsOffline() = runBlocking {
        try {
            client(Tokens("a")) { throw UnknownHostException("www.googleapis.com") }.get("https://example.com")
            fail()
        } catch (e: GoogleException) {
            assertEquals(Kind.OFFLINE, e.kind)
            assertTrue(e.kind.retryLater)
        }
    }

    @Test
    fun errorKinds() {
        assertEquals(Kind.QUOTA, error(403, "rateLimitExceeded"))
        assertEquals(Kind.QUOTA, error(429))
        assertEquals(Kind.API_DISABLED, error(403, "accessNotConfigured"))
        assertEquals(Kind.NEEDS_CONSENT, error(403, "insufficientPermissions"))
        assertEquals(Kind.FORBIDDEN, error(403, "forbidden"))
        assertEquals(Kind.NOT_FOUND, error(404, "notFound"))
        assertEquals(Kind.NOT_FOUND, error(410, "deleted"))
        assertEquals(Kind.CONFLICT, error(409, "duplicate"))
        assertEquals(Kind.SERVER, error(503, "backendError"))
        assertEquals(Kind.INVALID, error(400, "invalid"))
        assertTrue(Kind.SERVER.retryLater)
        assertFalse(Kind.INVALID.retryLater)
    }

    @Test
    fun errorsKeepNothingPrivate() {
        val body = """{"error":{"code":400,"message":"Invalid value for summary: Meeting with Rahul","errors":[{"reason":"invalid"}]}}"""
        val message = GoogleErrors.fromHttp(400, body).message.orEmpty()
        assertFalse(message.contains("Rahul"))
    }

    @Test
    fun geminiEventAnswers() {
        val notes = listOf(
            Note("Remember", "Meeting with Rahul tomorrow at 5 PM at Starbucks.", id = "r#1"),
            Note("Remember", "Buy milk.", id = "r#2"),
            Note("Remember", "Dentist appointment next Tuesday.", id = "r#3")
        )

        val answer = JSONObject().put(
            "notes",
            JSONArray()
                .put(JSONObject().put("number", 1).put("kind", "event").put("sure", true).put("title", "Meeting with Rahul")
                    .put("when_words", "tomorrow at 5 PM").put("date", "2026-09-22").put("start_time", "17:00")
                    .put("time_words", "at 5 PM").put("end_time", "").put("duration_minutes", 0).put("location", "Starbucks"))
                .put(JSONObject().put("number", 2).put("kind", "task").put("sure", true).put("title", "")
                    .put("when_words", "").put("date", "").put("start_time", "").put("time_words", "")
                    .put("end_time", "").put("duration_minutes", 0).put("location", ""))
                // A place that was never said is dropped; an unknown kind becomes "other".
                .put(JSONObject().put("number", 3).put("kind", "appointment").put("sure", false).put("title", "Dentist")
                    .put("when_words", "next Tuesday").put("date", "2026-09-29").put("start_time", "")
                    .put("time_words", "").put("end_time", "").put("duration_minutes", 0).put("location", "City Dental Clinic"))
        )

        val response = JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", answer.toString())))))
        )

        val suggestions = EventDetector.parse(response, notes, "Meeting with Rahul tomorrow at 5 at Starbucks. Buy milk.", 7L)

        assertEquals(3, suggestions.size)
        assertEquals("r#1", suggestions[0].noteId)
        assertTrue(suggestions[0].isEvent)
        assertEquals("Starbucks", suggestions[0].location)
        assertEquals("task", suggestions[1].kind)
        assertEquals("other", suggestions[2].kind)
        assertEquals("", suggestions[2].location)
    }
}
