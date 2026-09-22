package com.example.voicerecorder.summary

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Base64OutputStream
import com.example.voicerecorder.BuildConfig
import com.example.voicerecorder.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sends a saved recording to the Gemini API and returns its transcript
 * plus the short notes worth keeping, each with a title, tags and exactly
 * one category (see [GeminiSummarizer.CATEGORIES]).
 *
 * Small recordings are sent inline in a single request. Larger ones are
 * uploaded through the Gemini Files API first (inline requests are capped
 * at 20 MB), and that uploaded copy is deleted afterwards.
 *
 * The local MP3 is only read here. Deleting it after a successful result
 * is SummaryWorker's job.
 */
class GeminiSummarizer(
    private val context: Context
) {

    class GeminiException(
        message: String,
        val failure: FailureReason,
        val retryable: Boolean = false
    ) : Exception(message)

    private val settings =
        AppSettings(context)

    /** The model chosen in Settings > AI & Processing. */
    private val generateUrl: String
        get() = "$BASE_URL/v1beta/models/${settings.geminiModel}:generateContent"

    /**
     * The prompt, plus when the recording was made (so Gemini can turn
     * "tonight" or "by Friday" into a date) and any terms the user added
     * in Settings.
     */
    private fun prompt(
        recordedAt: LocalDateTime
    ): String {

        val prompt =
            PROMPT + "\n\n" + RECORDING_TIME.format(recordedAt.format(RECORDING_TIME_FORMAT))

        val extra =
            settings.knownTerms

        if (extra.isEmpty()) {
            return prompt
        }

        return prompt + "\n\nAlso use these exact spellings when a word sounds like one of them: " +
                extra.joinToString(", ") + "."
    }

    /**
     * [recordedAt] is when the recording started (epoch millis). Deadlines
     * such as "tonight at 8" are worked out from it.
     */
    suspend fun summarize(
        audioUri: Uri,
        recordedAt: Long
    ): RecordingNotes = withContext(Dispatchers.IO) {

        val recordedTime =
            Instant.ofEpochMilli(recordedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            throw GeminiException(
                "Gemini API key is missing. Add GEMINI_API_KEY to local.properties.",
                FailureReason.GEMINI_ERROR
            )
        }

        val size =
            audioSize(audioUri)

        // Two-step flow only: the transcript exactly as the transcription
        // model returned it, which is the one that is saved.
        var rawTranscript: String? = null

        val response =
            if (settings.geminiModel == TRANSCRIBE_MODEL) {
                val (transcript, notesResponse) = transcribeAndExtract(audioUri, size, recordedTime)
                rawTranscript = transcript
                notesResponse
            } else if (size <= INLINE_LIMIT_BYTES) {
                generateInline(audioUri, recordedTime)
            } else {
                generateFromUpload(audioUri, size, recordedTime)
            }

        parseNotes(response, recordedTime, rawTranscript)
    }

    private fun audioSize(
        audioUri: Uri
    ): Long {

        val size =
            try {
                context.contentResolver
                    .openFileDescriptor(audioUri, "r")
                    ?.use { it.statSize }
                    ?: -1L
            } catch (e: FileNotFoundException) {
                -1L
            }

        if (size <= 0) {
            throw GeminiException(
                "Recording not found or empty",
                FailureReason.RECORDING_NOT_FOUND
            )
        }

        return size
    }

    private fun openAudio(
        audioUri: Uri
    ): InputStream =
        context.contentResolver.openInputStream(audioUri)
            ?: throw GeminiException(
                "Could not open recording",
                FailureReason.RECORDING_NOT_FOUND
            )

    /**
     * One request with the audio embedded as base64.
     *
     * The base64 is streamed into the request body, so the recording
     * never has to sit in memory as one large string.
     */
    private fun generateInline(
        audioUri: Uri,
        recordedAt: LocalDateTime
    ): JSONObject {

        val audioPart =
            JSONObject().put(
                "inline_data",
                JSONObject()
                    .put("mime_type", MIME_TYPE)
                    .put("data", AUDIO_PLACEHOLDER)
            )

        val (head, tail) =
            requestBody(audioPart, recordedAt)
                .toString()
                .split(AUDIO_PLACEHOLDER)

        return json(generateUrl, "POST") {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setChunkedStreamingMode(0)

            outputStream.buffered().use { out ->
                out.write(head.toByteArray())

                Base64OutputStream(out, Base64.NO_WRAP or Base64.NO_CLOSE).use { base64 ->
                    openAudio(audioUri).use { it.copyTo(base64) }
                }

                out.write(tail.toByteArray())
            }
        }
    }

    /**
     * Upload through the Files API, then reference the file in the request.
     */
    private suspend fun generateFromUpload(
        audioUri: Uri,
        size: Long,
        recordedAt: LocalDateTime
    ): JSONObject {

        val file =
            uploadAudio(audioUri, size)

        val fileName =
            file.getString("name")

        try {
            if (file.optString("state") != "ACTIVE") {
                waitUntilActive(fileName)
            }

            val audioPart =
                JSONObject().put(
                    "file_data",
                    JSONObject()
                        .put("mime_type", MIME_TYPE)
                        .put("file_uri", file.getString("uri"))
                )

            val body =
                requestBody(audioPart, recordedAt).toString()

            return json(generateUrl, "POST") {
                writeJson(body)
            }

        } finally {
            // Best effort — Gemini also expires uploaded files after 48 hours.
            runCatching {
                call("$BASE_URL/v1beta/$fileName", "DELETE") {}
            }
        }
    }

    private fun uploadAudio(
        audioUri: Uri,
        size: Long
    ): JSONObject {

        val uploadUrl =
            call(
                "$BASE_URL/upload/v1beta/files",
                "POST",
                send = {
                    setRequestProperty("X-Goog-Upload-Protocol", "resumable")
                    setRequestProperty("X-Goog-Upload-Command", "start")
                    setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
                    setRequestProperty("X-Goog-Upload-Header-Content-Type", MIME_TYPE)

                    writeJson(
                        JSONObject()
                            .put("file", JSONObject().put("display_name", "recording"))
                            .toString()
                    )
                }
            ) {
                getHeaderField("x-goog-upload-url")
                    ?: throw GeminiException(
                        "Gemini did not return an upload URL",
                        FailureReason.GEMINI_ERROR,
                        retryable = true
                    )
            }

        return json(uploadUrl, "POST") {
            doOutput = true
            setFixedLengthStreamingMode(size)
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")

            outputStream.use { out ->
                openAudio(audioUri).use { it.copyTo(out) }
            }
        }.getJSONObject("file")
    }

    private suspend fun waitUntilActive(
        fileName: String
    ) {

        repeat(MAX_STATE_CHECKS) {

            when (json("$BASE_URL/v1beta/$fileName", "GET").optString("state")) {

                "ACTIVE" -> return

                "FAILED" -> throw GeminiException(
                    "Gemini could not process the recording",
                    FailureReason.GEMINI_ERROR
                )

                else -> delay(STATE_CHECK_INTERVAL_MS)
            }
        }

        throw GeminiException(
            "Gemini took too long to process the recording",
            FailureReason.GEMINI_ERROR,
            retryable = true
        )
    }

    // ── Transcribe-and-extract pipeline ─────────────────────────────

    /**
     * Two-step flow for the dedicated transcription model:
     * 1. [TRANSCRIBE_MODEL] turns the audio into text.
     * 2. [NOTES_FALLBACK_MODEL] (text-only, no audio) extracts
     *    structured notes from that transcript.
     *
     * This avoids the multimodal audio queue that is often overloaded
     * on the general-purpose Flash models.
     *
     * Returns the transcript exactly as step 1 gave it, and step 2's
     * answer. The transcript is taken before step 2 runs and is the one
     * that gets saved, so it never depends on the extraction model
     * copying it back correctly.
     */
    private suspend fun transcribeAndExtract(
        audioUri: Uri,
        size: Long,
        recordedAt: LocalDateTime
    ): Pair<String, JSONObject> {

        val transcribeResponse =
            try {
                if (size <= INLINE_LIMIT_BYTES) {
                    transcribeInline(audioUri)
                } else {
                    transcribeFromUpload(audioUri, size)
                }
            } catch (e: JSONException) {
                // An answer that could not be read is a failed request, not silence.
                throw GeminiException(
                    "Transcription failed: Gemini's answer could not be read",
                    FailureReason.TRANSCRIPTION_FAILED,
                    retryable = true
                )
            }

        // Throws (retryable) for anything but a finished, well-formed answer.
        val transcript =
            readTranscription(transcribeResponse)

        // Only a finished, well-formed answer with no words in it is silence.
        if (transcript.isEmpty()) {
            throw GeminiException(NO_SPEECH, FailureReason.NO_SPEECH)
        }

        return transcript to extractNotes(transcript, recordedAt)
    }

    /**
     * Sends audio inline to [TRANSCRIBE_MODEL]. Mirrors [generateInline]
     * but sends a minimal prompt and no response schema.
     */
    private fun transcribeInline(
        audioUri: Uri
    ): JSONObject {

        val audioPart =
            JSONObject().put(
                "inline_data",
                JSONObject()
                    .put("mime_type", MIME_TYPE)
                    .put("data", AUDIO_PLACEHOLDER)
            )

        val body =
            JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(audioPart)
                                .put(JSONObject().put("text", TRANSCRIBE_PROMPT))
                        )
                    )
                )

        val (head, tail) =
            body.toString().split(AUDIO_PLACEHOLDER)

        return json(modelUrl(TRANSCRIBE_MODEL), "POST") {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setChunkedStreamingMode(0)

            outputStream.buffered().use { out ->
                out.write(head.toByteArray())

                Base64OutputStream(out, Base64.NO_WRAP or Base64.NO_CLOSE).use { base64 ->
                    openAudio(audioUri).use { it.copyTo(base64) }
                }

                out.write(tail.toByteArray())
            }
        }
    }

    /**
     * Upload through the Files API, then reference the file in a
     * request to [TRANSCRIBE_MODEL]. Mirrors [generateFromUpload].
     */
    private suspend fun transcribeFromUpload(
        audioUri: Uri,
        size: Long
    ): JSONObject {

        val file =
            uploadAudio(audioUri, size)

        val fileName =
            file.getString("name")

        try {
            if (file.optString("state") != "ACTIVE") {
                waitUntilActive(fileName)
            }

            val audioPart =
                JSONObject().put(
                    "file_data",
                    JSONObject()
                        .put("mime_type", MIME_TYPE)
                        .put("file_uri", file.getString("uri"))
                )

            val body =
                JSONObject()
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray()
                                    .put(audioPart)
                                    .put(JSONObject().put("text", TRANSCRIBE_PROMPT))
                            )
                        )
                    )

            return json(modelUrl(TRANSCRIBE_MODEL), "POST") {
                writeJson(body.toString())
            }

        } finally {
            runCatching {
                call("$BASE_URL/v1beta/$fileName", "DELETE") {}
            }
        }
    }

    /**
     * Sends the already-transcribed text to [NOTES_FALLBACK_MODEL]
     * with the full prompt and structured schema, returning the same
     * JSON shape that [parseNotes] expects.
     */
    private fun extractNotes(
        transcript: String,
        recordedAt: LocalDateTime
    ): JSONObject {

        val textPart =
            JSONObject().put(
                "text",
                prompt(recordedAt) +
                        "\n\nThe audio has already been transcribed for you." +
                        " Use this transcript exactly as given in the" +
                        " \"transcript\" field:\n\n" + transcript
            )

        val body =
            JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(textPart))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("responseSchema", JSONObject(RESPONSE_SCHEMA))
                        .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                )

        return json(modelUrl(NOTES_FALLBACK_MODEL), "POST") {
            writeJson(body.toString())
        }
    }

    private fun requestBody(
        audioPart: JSONObject,
        recordedAt: LocalDateTime
    ): JSONObject {

        // Audio first, then the instructions (Google's recommended order).
        val parts =
            JSONArray()
                .put(audioPart)
                .put(JSONObject().put("text", prompt(recordedAt)))

        return JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("parts", parts))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject(RESPONSE_SCHEMA))
                    // The model's maximum, so a long recording's transcript
                    // and notes are never cut off.
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            )
    }

    /**
     * [rawTranscript]: the two-step flow's transcript, exactly as the
     * transcription model returned it. When given, it is the transcript
     * that is kept (see [useRawTranscript]).
     */
    private fun parseNotes(
        response: JSONObject,
        recordedAt: LocalDateTime,
        rawTranscript: String? = null
    ): RecordingNotes {

        val candidate =
            response.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw GeminiException(
                    "Gemini returned no result " +
                            response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty(),
                    FailureReason.PROCESSING_FAILED
                )

        // A cut-off answer would silently lose notes or transcript.
        if (candidate.optString("finishReason") == "MAX_TOKENS") {
            throw GeminiException(
                "Gemini's answer was cut off (recording too long)",
                FailureReason.PROCESSING_FAILED
            )
        }

        val parts =
            candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: JSONArray()

        val text =
            (0 until parts.length())
                .map { parts.getJSONObject(it) }
                .filterNot { it.optBoolean("thought") }
                .joinToString("") { it.optString("text") }

        if (text.isBlank()) {
            throw GeminiException(
                "Gemini returned an empty result (${candidate.optString("finishReason")})",
                FailureReason.PROCESSING_FAILED
            )
        }

        val result =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw GeminiException(
                    "Gemini returned an unexpected response",
                    FailureReason.PROCESSING_FAILED
                )
            }

        if (rawTranscript != null) {
            useRawTranscript(result, rawTranscript)
        }

        /*
         * No speech or no usable transcript means transcription did NOT
         * succeed. Failing here keeps the audio (the worker only deletes
         * it after a successful result).
         *
         * "No speech detected" is a failure (audio kept). "Nothing worth
         * keeping" is a success with an empty notes list (see
         * RecordingNotes.outcome).
         */
        if (!result.optBoolean("speech_detected")) {
            throw GeminiException(NO_SPEECH, FailureReason.NO_SPEECH)
        }

        val transcript =
            result.optString("transcript").trim()

        if (transcript.isEmpty()) {
            throw GeminiException(
                "Transcription failed: Gemini returned no text",
                FailureReason.TRANSCRIPTION_FAILED
            )
        }

        val notes =
            result.optJSONArray("notes")
                ?: throw GeminiException(
                    "Gemini returned an unexpected response",
                    FailureReason.PROCESSING_FAILED
                )

        // One invalid note fails the whole recording, so nothing half-valid gets through.
        // An empty list is valid: the recording had nothing worth keeping.
        return RecordingNotes(
            transcript = transcript,
            notes = (0 until notes.length()).map { index ->

                val item =
                    notes.optJSONObject(index)
                        ?: throw GeminiException(
                            "Gemini returned an unexpected response",
                            FailureReason.PROCESSING_FAILED
                        )

                val noteText =
                    item.optString("note").trim()

                val category =
                    item.optString("category").trim()

                val title =
                    item.optString("title").trim()
                        .ifEmpty { Note.titleFrom(noteText) }

                val tagList =
                    item.optJSONArray("tags")

                val tags =
                    (0 until (tagList?.length() ?: 0))
                        .mapNotNull { tagList?.optString(it)?.trim()?.removePrefix("#")?.lowercase() }
                        .filter { it.isNotEmpty() }
                        .take(MAX_TAGS)

                if (noteText.isEmpty()) {
                    throw GeminiException(
                        "Gemini returned an empty note",
                        FailureReason.PROCESSING_FAILED
                    )
                }

                if (category !in CATEGORIES) {
                    throw GeminiException(
                        "Gemini returned an invalid category: \"$category\"",
                        FailureReason.PROCESSING_FAILED
                    )
                }

                val (dueDate, dueTime) =
                    due(item, category, recordedAt)

                Note(category, noteText, title, tags, dueDate, dueTime)
            }
        )
    }

    /**
     * The deadline of a Remember note as ("YYYY-MM-DD", "HH:MM"), or
     * ("", "") when it has none. A deadline Gemini got wrong is dropped
     * instead of failing the whole recording: the note itself is fine.
     */
    private fun due(
        item: JSONObject,
        category: String,
        recordedAt: LocalDateTime
    ): Pair<String, String> {

        // No words saying when means no deadline was said.
        if (category != REMEMBER || item.optString("due_words").isBlank()) {
            return "" to ""
        }

        val time =
            runCatching { LocalTime.parse(item.optString("due_time").trim(), DUE_TIME_FORMAT) }
                .getOrNull()

        val date =
            runCatching { LocalDate.parse(item.optString("due_date").trim()) }.getOrNull()
                // A time with no day: today, or tomorrow if it had already passed.
                ?: time?.let {
                    if (it.isAfter(recordedAt.toLocalTime())) {
                        recordedAt.toLocalDate()
                    } else {
                        recordedAt.toLocalDate().plusDays(1)
                    }
                }
                ?: return "" to ""

        // Gemini gives the named day; "before Friday" means it is due on Thursday.
        val dueDate =
            if (time == null && BEFORE.containsMatchIn(item.optString("due_words"))) {
                date.minusDays(1)
            } else {
                date
            }

        // A deadline before the day of the recording cannot be right.
        if (dueDate.isBefore(recordedAt.toLocalDate())) {
            return "" to ""
        }

        return dueDate.toString() to (time?.format(DUE_TIME_FORMAT) ?: "")
    }

    private fun json(
        url: String,
        method: String,
        send: HttpURLConnection.() -> Unit = {}
    ): JSONObject =
        call(url, method, send) {
            inputStream.bufferedReader().use { JSONObject(it.readText()) }
        }

    private fun <T> call(
        url: String,
        method: String,
        send: HttpURLConnection.() -> Unit = {},
        read: HttpURLConnection.() -> T
    ): T {

        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            }

        try {
            connection.send()

            val code =
                connection.responseCode

            if (code !in 200..299) {
                throw httpError(code, connection.errorStream)
            }

            return connection.read()

        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.writeJson(
        body: String
    ) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(body.toByteArray()) }
    }

    private fun httpError(
        code: Int,
        errorStream: InputStream?
    ): GeminiException {

        val body =
            errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()

        val message =
            runCatching {
                JSONObject(body).getJSONObject("error").getString("message")
            }.getOrNull()

        return GeminiException(
            "Gemini error $code: ${message ?: "no details"}",
            FailureReason.GEMINI_ERROR,
            // Rate limits and server overload ("high demand") are temporary.
            retryable = code == 408 || code == 429 || code >= 500
        )
    }

    companion object {

        /*
         * Chosen after testing several models on speech and on silence:
         * accurate, ~2-3 s for a short note, and it reliably reports
         * silence instead of inventing a summary (the bigger flash
         * models hallucinated content for silent audio).
         */
        const val DEFAULT_MODEL =
            "gemini-3.5-flash-lite"

        const val TRANSCRIBE_MODEL =
            "gemini-3.5-transcribe"

        private const val NOTES_FALLBACK_MODEL =
            "gemma-4-26b-a4b-it"

        private const val TRANSCRIBE_PROMPT =
            "Transcribe this audio."

        private fun modelUrl(model: String): String =
            "$BASE_URL/v1beta/models/$model:generateContent"

        /**
         * The model for text-only requests with a JSON answer: the notes
         * of the two-step flow, and EventDetector's "is this an event or a
         * to-do?". The transcription model only transcribes audio (Google
         * answers "JSON mode is not enabled for this model"), so while it
         * is selected those requests go to [NOTES_FALLBACK_MODEL].
         */
        fun textModel(
            selected: String
        ): String =
            if (selected == TRANSCRIBE_MODEL) NOTES_FALLBACK_MODEL else selected

        /**
         * The text of a [TRANSCRIBE_MODEL] answer, from its
         * "audioTranscription.text" parts.
         *
         * Returns "" ONLY for silence: an answer that finished normally
         * ("STOP") and is well formed, with no words in it (Google then
         * sends no parts at all). Anything else is a failed transcription
         * and is thrown as retryable (TRANSCRIPTION_FAILED): no answer, a
         * blocked or cut-off one, or parts in an unexpected shape. A
         * recording marked as silent is never tried again, so only real
         * silence may be reported as such.
         */
        fun readTranscription(
            response: JSONObject
        ): String {

            val candidate =
                response.optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw failedTranscription(
                        "no answer" + response.optJSONObject("promptFeedback")
                            ?.optString("blockReason")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { " (blocked: $it)" }
                            .orEmpty()
                    )

            val finishReason =
                candidate.optString("finishReason")

            if (finishReason != "STOP") {
                throw failedTranscription("the answer ended with ${finishReason.ifEmpty { "no reason" }}")
            }

            // Finished normally with nothing in it: nothing was said.
            val parts =
                candidate.optJSONObject("content")?.optJSONArray("parts")
                    ?: return ""

            val texts =
                mutableListOf<String>()

            for (index in 0 until parts.length()) {

                val part =
                    parts.optJSONObject(index)
                        ?: throw failedTranscription("an unexpected answer")

                if (part.optBoolean("thought")) {
                    continue
                }

                val transcription =
                    part.optJSONObject("audioTranscription")
                        ?: throw failedTranscription("an unexpected answer")

                texts += transcription.optString("text")
            }

            return texts.joinToString(" ").trim()
        }

        /**
         * The two-step flow keeps the transcript exactly as the
         * transcription model returned it, not the extraction model's copy
         * of it. Speech was already found in step 1 (silence never reaches
         * step 2), so the extraction model cannot turn it into "no speech".
         */
        fun useRawTranscript(
            result: JSONObject,
            transcript: String
        ): JSONObject =
            result
                .put("speech_detected", true)
                .put("transcript", transcript)

        private fun failedTranscription(
            reason: String
        ): GeminiException =
            GeminiException(
                "Transcription failed: $reason",
                FailureReason.TRANSCRIPTION_FAILED,
                retryable = true
            )

        private const val BASE_URL =
            "https://generativelanguage.googleapis.com"

        private const val MIME_TYPE =
            "audio/mp3"

        /*
         * Inline requests are capped at 20 MB and base64 adds ~33%.
         * 10 MB is roughly 10 minutes of the recorder's 128 kbps MP3.
         */
        private const val INLINE_LIMIT_BYTES =
            10L * 1024 * 1024

        private const val AUDIO_PLACEHOLDER =
            "__AUDIO_BASE64__"

        private const val CONNECT_TIMEOUT_MS =
            30_000

        private const val READ_TIMEOUT_MS =
            180_000

        private const val MAX_STATE_CHECKS =
            60

        private const val STATE_CHECK_INTERVAL_MS =
            2_000L

        const val NO_SPEECH =
            "No speech detected."

        private const val MAX_OUTPUT_TOKENS =
            65_536

        private const val MAX_TAGS =
            3

        const val REMEMBER =
            "Remember"

        /*
         * Added after the prompt so Gemini can turn "tonight at 8" into a
         * date and time, e.g. "Monday, 21 September 2026 at 14:05".
         */
        private const val RECORDING_TIME =
            "This recording was made on %s (local time). Work out due_date and due_time from it."

        private val RECORDING_TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'at' HH:mm", Locale.ENGLISH)

        private val DUE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm")

        private val BEFORE =
            Regex("""\bbefore\b""", RegexOption.IGNORE_CASE)

        /*
         * Every note gets exactly one of these.
         * The same list is sent to Gemini as the allowed values.
         */
        val CATEGORIES =
            listOf(
                "Thoughts",
                "Idea",
                "Remember",
                "Random Gossip"
            )

        /*
         * Terms that speech recognition often mishears in student/developer
         * recordings (e.g. "DBMS" heard as "TBS 7"). Gemini is told to use
         * these spellings when the audio and the context fit. Extend freely.
         */
        val KNOWN_TERMS =
            listOf(
                "DBMS", "SQL", "MySQL", "DSA", "OOP", "OS", "CN", "COA", "TOC", "AI", "ML",
                "Android", "Android Studio", "Kotlin", "Java", "Python", "C++", "JavaScript",
                "React", "Flutter", "Firebase", "Gemini", "Gemini API", "API", "API key", "APK",
                "Git", "GitHub", "Figma", "UI", "UX", "JSON", "Wi-Fi", "LeetCode", "DayTrace",
                "hackathon", "viva", "lab", "semester", "internals", "assignment", "faculty",
                "HOD", "placement", "internship"
            )

        private val PROMPT =
            """
            You turn a personal voice recording into short notes. Your job is NOT to summarize the whole recording. Understand it, find every meaningful piece of information, separate independent tasks and ideas, drop unnecessary conversation, and write short notes that keep the full meaning.

            STEP 0 - TRANSCRIBE
            Write the complete, faithful transcript of the speech into "transcript", in the language it was spoken. The app deletes the audio once your transcript and notes are saved, so the transcript must not leave anything out.
            If there is no understandable speech (silence, noise, music only), set speech_detected to false, leave transcript empty and return no notes. Never invent speech.
            The speaker is usually a student or developer. These terms come up often and are easily misheard: ${KNOWN_TERMS.joinToString(", ")}. When a word sounds like one of these terms and the context fits, write the correct term in both the transcript and the notes. For example "TBS 7 assignment" or "DBMF assignment" becomes "DBMS assignment", "the Jimmy API" becomes "the Gemini API", "git hub" becomes "GitHub". Do not change words that already make sense in context.

            STEP 1 - UNDERSTAND
            Understand the whole recording and its context. It may mix several tasks, ideas, reminders, facts, personal thoughts, stories, jokes, opinions, small talk and unrelated conversation. One sentence is not one note.

            STEP 2 - EXTRACT MEANINGFUL POINTS (write them into "points")
            Find every separate piece of information worth keeping.
            Split pieces that are independent tasks, reminders, ideas or facts. "We need to finish the Android project before Friday and I still haven't completed the Gemini integration." gives two points: "Finish the Android project before Friday." and "Complete the Gemini integration."
            Do not split just because a sentence contains "and". "Finish the Android project and test it before Friday." stays one task, because both actions serve the same goal. Actions for the same goal, such as preparing the same event or finishing the same piece of work, stay one note. Split only clearly separate goals.
            When you do split, repeat the details the parts share (deadline, event, person) in each note, so nothing is lost.

            STEP 3 - WHAT TO KEEP AND WHAT TO DROP
            Keep every:
            - task, reminder, deadline, appointment or meeting, and anything to send, pay, buy, finish, attend or follow up on
            - idea, suggestion, proposal, feature idea or plan, even when nothing is decided yet ("maybe we could...", "we'll decide later")
            - useful observation, decision, opinion or fact
            - piece of news about people that is worth knowing later (someone got an internship, failed a test, is moving)
            Drop: greetings and goodbyes, acknowledgements ("yeah", "okay", "exactly"), filler and repetition, jokes, laughter and reactions ("it was hilarious", "that was so funny"), passing remarks with no lasting information ("the bus was crazy today", "the samosa was terrible"), and talk about the conversation itself ("what were we talking about?", "that's all for now").
            When unsure whether an idea, observation or plan is useful, KEEP it. When unsure whether small talk is useful, drop it.

            STEP 4 - REWRITE: SHORTEN THE WORDING, NEVER THE MEANING
            Write each point as one short, clear sentence in simple everyday language.
            Keep every detail that gives the point its meaning: dates, days, times, deadlines, people, places, project, course or subject names, and the purpose or reason when it explains why something matters or what it is for.
            Remove only conversational wording: "I think", "I feel", "we should probably", "I was saying", "you know", "actually", "kind of", "basically", "maybe", "I still haven't", "we really need to", "remind me to", "don't forget".
            - "I have a presentation on Monday, so I should prepare the slides this weekend." becomes "Prepare the slides this weekend for Monday's presentation." Never just "Prepare the slides this weekend."
            - "We really need to finish the Android project before Friday because the faculty is going to check the demo." becomes "Finish the Android project before Friday for the faculty demo."
            - "I still haven't completed the Gemini integration." becomes "Complete the Gemini integration."
            - "I need to finish the project tomorrow and also send the APK to Shlok tonight." becomes "Finish the project tomorrow." and "Send the APK to Shlok tonight."
            - "Call the plumber before Friday, because the landlord is visiting on Saturday." becomes "Call the plumber before Friday; the landlord visits on Saturday."
            Add nothing that was not said. Never refer to "the speaker", "the user" or "the recording".

            Also give each note, for display in the app:
            - title: 2 to 5 words naming what the note is about, in sentence case and with no full stop ("DBMS assignment", "Login screen animation", "Farewell party hall"). It must come from the note itself, never from anything that was not said.
            - tags: 2 or 3 short lowercase labels for finding the note later, one or two words each, with no "#" ("college", "assignment", "app idea"). Use plain everyday words.

            STEP 5 - CATEGORIZE (exactly one category per note)
            Decide in this order, and give the same kind of note the same category every time:
            1. Remember: something to DO or to REMEMBER. A task, reminder, deadline, appointment or meeting, an instruction to follow, or a fact to keep (a date, a number, where something is). This includes work that has been decided or committed to ("finish the database connection tonight", "push the code before midnight"). Words like "need to", "have to", "must", "don't forget", "remind me" point to Remember.
            2. Idea: something that could be built, created, added or tried, and that nobody has committed to yet: a proposal, concept, feature, project or solution ("we could add dark mode", "maybe let users edit their profile picture", "make an app that tracks sold-out canteen items"). Words like "maybe we could", "we should add", "what if we", "I was thinking we could" point to Idea.
            3. Thoughts: an opinion, reflection, feeling or observation, with nothing to build or do ("the library Wi-Fi is faster than the hostel's", "online classes are more tiring than offline ones", "working at night feels more productive").
            4. Random Gossip: casual information about people or events that is worth keeping but is neither a task, an idea nor a personal reflection ("Aman got an internship at Zomato starting in January").
            Small talk that is not worth keeping gets no note at all.

            STEP 6 - DEADLINES (Remember notes only)
            When a Remember note says WHEN it has to be done (a deadline, an appointment or a time), fill due_words, due_date and due_time. Leave all three empty for every other note.
            - due_words: the exact words from the recording that say when, such as "tonight by 8 PM", "before Friday", "tomorrow morning" or "on the 5th". Empty when no day or time is said.
            - due_date: the day as YYYY-MM-DD, worked out from due_words and the date of the recording (given at the end of these instructions). "today" and "tonight" are the day of the recording, "tomorrow" the day after it, and a weekday ("on Friday", "by Friday", "before Friday") the next such day. Always give the day that is NAMED, also after "before": for "before Friday" give the date of Friday itself.
            - due_time: the time as 24-hour HH:MM, ONLY when due_words contain a clock time or a part of the day. Tell morning from evening by the words: "tonight at 8" and "by 8 PM" are 20:00, "8 in the morning" is 08:00. A part of the day with no clock time: "morning" 09:00, "afternoon" 14:00, "evening" or "this evening" 19:00, "tonight" 21:00. A time with no day is on the day of the recording, or the next day if that time had already passed.
            If due_words name only a day ("by Friday", "tomorrow", "before Thursday"), due_time must be EMPTY. Never make up a time or a deadline that was not said.

            STEP 7 - NO DUPLICATES
            Never create two notes with the same information; merge them when they are truly the same item. Keep independent actions as separate notes.

            STEP 8 - CHECK
            Before answering, compare every note with the transcript. Each note must still contain every date, day, time, deadline, person, place, project name and purpose that belongs to it. If one deadline or date covers several notes, each of those notes must include it ("book the hall and send the invites by Thursday" gives "Book the hall by Thursday." and "Send the invites by Thursday."). Add anything missing.

            STEP 9 - OUTPUT
            Return every note worth keeping, in the order it was said. If there is speech but nothing worth keeping, return no notes (the transcript is still saved). Do not explain your reasoning, do not describe the process, and do not summarize the recording as one paragraph.
            """.trimIndent()

        /*
         * "transcript" is the proof that transcription succeeded; the app
         * saves it before deleting the audio.
         *
         * "points" is Gemini's internal extraction step (find each piece of
         * information before rewriting it). It makes splitting more
         * consistent; the app does not use it.
         *
         * "enum" restricts Gemini's answer to exactly the allowed categories.
         *
         * "due_words" / "due_date" / "due_time" come after "category" on
         * purpose: only Remember notes get a deadline, so the category is
         * decided first. "due_words" (the exact words that say when) makes
         * Gemini ground the deadline in what was said; a deadline without
         * them is dropped, so it can never be invented.
         */
        private val RESPONSE_SCHEMA =
            """
            {
              "type": "OBJECT",
              "properties": {
                "speech_detected": { "type": "BOOLEAN" },
                "transcript": { "type": "STRING" },
                "points": {
                  "type": "ARRAY",
                  "items": { "type": "STRING" }
                },
                "notes": {
                  "type": "ARRAY",
                  "items": {
                    "type": "OBJECT",
                    "properties": {
                      "note": { "type": "STRING" },
                      "title": { "type": "STRING" },
                      "tags": {
                        "type": "ARRAY",
                        "items": { "type": "STRING" }
                      },
                      "category": { "type": "STRING", "enum": ${JSONArray(CATEGORIES)} },
                      "due_words": { "type": "STRING" },
                      "due_date": { "type": "STRING" },
                      "due_time": { "type": "STRING" }
                    },
                    "required": ["note", "title", "tags", "category", "due_words", "due_date", "due_time"],
                    "propertyOrdering": ["note", "title", "tags", "category", "due_words", "due_date", "due_time"]
                  }
                }
              },
              "required": ["speech_detected", "transcript", "points", "notes"],
              "propertyOrdering": ["speech_detected", "transcript", "points", "notes"]
            }
            """
    }
}

/**
 * Why processing a recording failed. In every case the audio is kept.
 */
enum class FailureReason {

    /** Gemini heard no understandable speech (silence, noise, music). */
    NO_SPEECH,

    /** Speech was detected but no usable transcript came back. */
    TRANSCRIPTION_FAILED,

    /** Transcript came back, but the notes were malformed or invalid. */
    PROCESSING_FAILED,

    /** The MP3 is missing or empty. */
    RECORDING_NOT_FOUND,

    /** Gemini API error (bad key, quota, server error after retries). */
    GEMINI_ERROR,

    /** Gemini could not be reached. */
    NETWORK,

    /** Transcript + notes could not be saved on the device. */
    STORAGE
}

/**
 * A successfully processed recording: the full transcript plus the notes
 * worth keeping (may be empty when nothing in it was worth a note).
 */
data class RecordingNotes(
    val transcript: String,
    val notes: List<Note>
) {

    /**
     * "Nothing worth keeping" is a success: the transcript exists, there
     * were just no notes in it. Not to be confused with "No speech
     * detected", which is a failure (FailureReason.NO_SPEECH).
     */
    val outcome: String
        get() = if (notes.isEmpty()) OUTCOME_NOTHING_WORTH_KEEPING else OUTCOME_NOTES

    companion object {

        const val OUTCOME_NOTES =
            "NOTES"

        const val OUTCOME_NOTHING_WORTH_KEEPING =
            "NOTHING_WORTH_KEEPING"
    }
}

/**
 * One short note from a recording.
 *
 * [title] and [tags] are for display; notes saved before they existed
 * fall back to [titleFrom] and an empty tag list.
 *
 * [dueDate] ("YYYY-MM-DD") and [dueTime] ("HH:MM", may be empty) are the
 * deadline of a Remember note, used for its reminders.
 *
 * The rest is set by NoteStore: [id] identifies the note, [deletedAt]
 * is when it went to the recycle bin (0 = not deleted) and [doneAt] is
 * when the user marked it as done (it goes to the bin then too).
 */
data class Note(
    val category: String,
    val text: String,
    val title: String = titleFrom(text),
    val tags: List<String> = emptyList(),
    val dueDate: String = "",
    val dueTime: String = "",
    val id: String = "",
    val deletedAt: Long = 0L,
    val doneAt: Long = 0L
) {

    val isDeleted: Boolean
        get() = deletedAt > 0L

    val hasDue: Boolean
        get() = dueDate.isNotEmpty()

    companion object {

        private const val MAX_TITLE_WORDS =
            5

        /**
         * A short title made from the note itself, e.g.
         * "Submit the DBMS assignment tomorrow." -> "Submit the DBMS assignment"
         */
        fun titleFrom(
            text: String
        ): String {

            val words =
                text.trim().split(Regex("\\s+"))

            return words
                .take(MAX_TITLE_WORDS)
                .joinToString(" ")
                .trimEnd('.', ',', ';', ':')
                .ifEmpty { "Note" }
        }
    }
}
