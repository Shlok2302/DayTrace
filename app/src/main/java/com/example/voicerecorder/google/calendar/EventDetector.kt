package com.example.voicerecorder.google.calendar

import android.content.Context
import com.example.voicerecorder.BuildConfig
import com.example.voicerecorder.google.EventSuggestion
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.GeminiSummarizer
import com.example.voicerecorder.summary.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Asks Gemini which Remember notes of one recording are calendar events
 * (a meeting, an appointment, a call at a set time) rather than to-dos
 * ("buy milk", "finish the DBMS assignment"), and which words say when
 * and where.
 *
 * A separate, text-only request made after the notes are saved: the
 * summarization (GeminiSummarizer) is not changed. Only runs when Google
 * Calendar is connected. The answer is only a suggestion; nothing is
 * added to Google Calendar without the user's OK.
 */
class EventDetector(
    context: Context
) {

    class DetectionException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    private val settings =
        AppSettings(context)

    /**
     * One suggestion per note in [notes] (all from one recording, made at
     * [recordedAt]). [transcript] is only context for the words.
     */
    suspend fun detect(
        notes: List<Note>,
        transcript: String,
        recordedAt: LocalDateTime
    ): List<EventSuggestion> = withContext(Dispatchers.IO) {

        if (notes.isEmpty()) {
            return@withContext emptyList()
        }

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            throw DetectionException("Gemini API key is missing")
        }

        val answer =
            try {
                request(prompt(notes, transcript, recordedAt))
            } catch (e: IOException) {
                throw DetectionException("Could not reach Gemini", e)
            }

        parse(answer, notes, transcript, System.currentTimeMillis())
    }

    private fun request(
        prompt: String
    ): JSONObject {

        val body =
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("responseSchema", JSONObject(RESPONSE_SCHEMA))
                )

        val connection =
            // A text model, even while "3.5 Transcribe" is selected: that one cannot answer in JSON.
            (URL("$BASE_URL/v1beta/models/${GeminiSummarizer.textModel(settings.geminiModel)}:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code =
                connection.responseCode

            if (code !in 200..299) {
                throw DetectionException("Gemini error $code")
            }

            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }

        } finally {
            connection.disconnect()
        }
    }

    companion object {

        private const val BASE_URL =
            "https://generativelanguage.googleapis.com"

        /** The transcript is only context; a very long one is cut. */
        private const val MAX_TRANSCRIPT_CHARS =
            12_000

        private val RECORDING_TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)

        fun prompt(
            notes: List<Note>,
            transcript: String,
            recordedAt: LocalDateTime
        ): String =
            PROMPT +
                    "\n\nThe recording was made on ${recordedAt.format(RECORDING_TIME_FORMAT)} (local time)." +
                    "\n\nNOTES\n" +
                    notes.mapIndexed { index, note -> "[${index + 1}] ${note.text}" }.joinToString("\n") +
                    "\n\nTRANSCRIPT OF THE RECORDING (context only)\n\"\"\"\n" +
                    transcript.take(MAX_TRANSCRIPT_CHARS) +
                    "\n\"\"\""

        /**
         * Gemini's answer as one suggestion per note. What was not said
         * stays empty; a place that does not appear in the note or the
         * transcript is dropped, so a place is never invented.
         */
        fun parse(
            response: JSONObject,
            notes: List<Note>,
            transcript: String,
            checkedAt: Long
        ): List<EventSuggestion> {

            val text =
                response.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.let { parts ->
                        (0 until parts.length())
                            .map { parts.getJSONObject(it) }
                            .filterNot { it.optBoolean("thought") }
                            .joinToString("") { it.optString("text") }
                    }
                    .orEmpty()

            val items =
                runCatching { JSONObject(text).getJSONArray("notes") }.getOrNull()
                    ?: throw DetectionException("Gemini returned an unexpected answer")

            val byNumber =
                (0 until items.length())
                    .mapNotNull { items.optJSONObject(it) }
                    .associateBy { it.optInt("number") }

            return notes.mapIndexedNotNull { index, note ->

                val item =
                    byNumber[index + 1] ?: return@mapIndexedNotNull null

                val kind =
                    item.optString("kind").trim().lowercase()
                        .takeIf { it in KINDS } ?: EventSuggestion.KIND_OTHER

                val location =
                    item.optString("location").trim()
                        .takeIf { it.isNotEmpty() && said(it, note.text, transcript) }
                        .orEmpty()

                EventSuggestion(
                    noteId = note.id,
                    kind = kind,
                    sure = item.optBoolean("sure"),
                    title = item.optString("title").trim(),
                    whenWords = item.optString("when_words").trim(),
                    date = item.optString("date").trim(),
                    startTime = item.optString("start_time").trim(),
                    timeWords = item.optString("time_words").trim(),
                    endTime = item.optString("end_time").trim(),
                    durationMinutes = item.optInt("duration_minutes"),
                    location = location,
                    checkedAt = checkedAt
                )
            }
        }

        /** True when [place] (ignoring case and punctuation) appears in what was said. */
        private fun said(
            place: String,
            noteText: String,
            transcript: String
        ): Boolean {

            fun simple(text: String) =
                text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

            val wanted =
                simple(place).removePrefix("the ").trim()

            return wanted.isNotEmpty() && (simple(noteText).contains(wanted) || simple(transcript).contains(wanted))
        }

        private val KINDS =
            setOf(EventSuggestion.KIND_EVENT, EventSuggestion.KIND_TASK, EventSuggestion.KIND_OTHER)

        private val PROMPT =
            """
            You look at short notes taken from a person's voice recording and decide which ones are CALENDAR EVENTS.

            An EVENT happens at a particular time, and the person attends it, takes part in it or has to be there: a meeting, an appointment, a call at a set time, a class or lecture, an exam, an interview, a party, dinner or celebration, a match, a trip or flight, a visit, a doctor's or dentist's appointment.
            A TASK is something to do or finish, with or without a deadline: "buy milk", "finish the DBMS assignment", "send the APK to Shlok by 8 PM", "pay the fees before Friday". A deadline ("by", "before", "until") makes it a TASK, not an event.
            Anything else (a fact, an idea, a thought) is OTHER.

            For every note, in the same order, give:
            - number: the note's number.
            - kind: "event", "task" or "other".
            - sure: true when it is clearly an event, or clearly not one. false when you are unsure.
            - title: for an event, 2 to 6 words naming it, without the date or time ("Meeting with Rahul", "Dentist appointment", "Call mom"). Empty for tasks and other notes.
            - when_words: the exact words that say WHEN it happens ("tomorrow at 5 PM", "next Tuesday", "on her birthday at 7 PM"). Empty when no day or time is said.
            - date: the day as YYYY-MM-DD, worked out from when_words and the date of the recording given below. "today" and "tonight" are the day of the recording, "tomorrow" the day after it, and a weekday the next such day. Leave it empty when the words do not tell which day it is, for example "on her birthday" when the date of the birthday was not said. Never guess a date.
            - start_time: the time as 24-hour HH:MM, only when a clock time is said ("5 PM" is 17:00, "7 in the evening" is 19:00). Empty when only a part of the day is said ("in the evening") or no time is said. Never guess a time.
            - time_words: the exact words that give the time or the part of the day ("at 5 PM", "in the evening"). Empty when none.
            - end_time: 24-hour HH:MM, only when an end time is said ("from 5 to 6 PM" ends at 18:00). Otherwise empty.
            - duration_minutes: only when a length is said ("for two hours" is 120). Otherwise 0.
            - location: the place exactly as it was said ("Starbucks in Viman Nagar", "the college auditorium"), only when a place is said for this event. Otherwise empty. Never guess a place.

            Use the transcript only to understand the notes better. Decide about the notes, not about other things in the transcript.
            """.trimIndent()

        private val RESPONSE_SCHEMA =
            """
            {
              "type": "OBJECT",
              "properties": {
                "notes": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "number": { "type": "INTEGER" },
                      "kind": { "type": "STRING", "enum": ["event", "task", "other"] },
                      "sure": { "type": "BOOLEAN" },
                      "title": { "type": "STRING" },
                      "when_words": { "type": "STRING" },
                      "date": { "type": "STRING" },
                      "start_time": { "type": "STRING" },
                      "time_words": { "type": "STRING" },
                      "end_time": { "type": "STRING" },
                      "duration_minutes": { "type": "INTEGER" },
                      "location": { "type": "STRING" }
                    },
                    "required": ["number", "kind", "sure", "title", "when_words", "date", "start_time", "time_words", "end_time", "duration_minutes", "location"],
                    "propertyOrdering": ["number", "kind", "sure", "title", "when_words", "date", "start_time", "time_words", "end_time", "duration_minutes", "location"]
                  }
                }
              },
              "required": ["notes"]
            }
            """
    }
}
