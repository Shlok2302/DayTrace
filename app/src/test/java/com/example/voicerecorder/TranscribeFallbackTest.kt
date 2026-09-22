package com.example.voicerecorder

import com.example.voicerecorder.summary.FailureReason
import com.example.voicerecorder.summary.GeminiSummarizer
import com.example.voicerecorder.summary.GeminiSummarizer.GeminiException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two-step flow ("3.5 Transcribe"): only real silence is reported as
 * "no speech" (which is never retried), every other failed transcription
 * stays retryable, the transcript kept is the transcription model's own,
 * and text-only requests never go to the transcription model.
 */
class TranscribeFallbackTest {

    private fun answer(json: String) = JSONObject(json)

    private fun failsRetryably(json: String) {
        try {
            GeminiSummarizer.readTranscription(answer(json))
            fail("Expected a failed transcription")
        } catch (e: GeminiException) {
            assertEquals(FailureReason.TRANSCRIPTION_FAILED, e.failure)
            assertTrue("A failed transcription must be tried again", e.retryable)
        }
    }

    @Test
    fun speechIsRead() {
        // The shape gemini-3.5-transcribe returned for a real recording.
        val text = GeminiSummarizer.readTranscription(
            answer(
                """{"candidates":[{"content":{"parts":[{"audioTranscription":{"text":"Remember to send the APK to Shlok tonight by 8:00 p.m."}}],"role":"model"},"finishReason":"STOP"}]}"""
            )
        )
        assertEquals("Remember to send the APK to Shlok tonight by 8:00 p.m.", text)
    }

    @Test
    fun severalPiecesAreJoined() {
        val text = GeminiSummarizer.readTranscription(
            answer(
                """{"candidates":[{"content":{"parts":[
                    {"audioTranscription":{"text":"First part."}},
                    {"thought":true,"text":"thinking"},
                    {"audioTranscription":{"text":"Second part."}}
                ]},"finishReason":"STOP"}]}"""
            )
        )
        assertEquals("First part. Second part.", text)
    }

    @Test
    fun realSilenceIsNoSpeech() {
        // Silence: the answer finished normally and has no parts at all.
        assertEquals("", GeminiSummarizer.readTranscription(answer("""{"candidates":[{"content":{"role":"model"},"finishReason":"STOP"}]}""")))
        assertEquals("", GeminiSummarizer.readTranscription(answer("""{"candidates":[{"finishReason":"STOP"}]}""")))
        assertEquals("", GeminiSummarizer.readTranscription(answer("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""")))
        assertEquals("", GeminiSummarizer.readTranscription(answer("""{"candidates":[{"content":{"parts":[{"audioTranscription":{"text":"  "}}]},"finishReason":"STOP"}]}""")))
    }

    @Test
    fun noAnswerIsRetryableNotSilence() {
        failsRetryably("""{}""")
        failsRetryably("""{"candidates":[]}""")
        failsRetryably("""{"promptFeedback":{"blockReason":"OTHER"}}""")
    }

    @Test
    fun anAnswerThatDidNotFinishIsRetryableNotSilence() {
        failsRetryably("""{"candidates":[{"content":{"role":"model"},"finishReason":"SAFETY"}]}""")
        failsRetryably("""{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS"}]}""")
        failsRetryably("""{"candidates":[{"content":{"role":"model"},"finishReason":"OTHER"}]}""")
        failsRetryably("""{"candidates":[{"content":{"role":"model"}}]}""")
    }

    @Test
    fun anUnexpectedShapeIsRetryableNotSilence() {
        // e.g. Google starting to send plain "text" parts instead.
        failsRetryably("""{"candidates":[{"content":{"parts":[{"text":"Hello there."}]},"finishReason":"STOP"}]}""")
        failsRetryably("""{"candidates":[{"content":{"parts":["Hello there."]},"finishReason":"STOP"}]}""")
    }

    @Test
    fun theRawTranscriptIsTheOneKept() {
        // The extraction model's copy was cut short and it even said "no speech".
        val fromExtraction =
            JSONObject()
                .put("speech_detected", false)
                .put("transcript", "Remember to send the APK")
                .put("notes", org.json.JSONArray())

        val kept =
            GeminiSummarizer.useRawTranscript(fromExtraction, "Remember to send the APK to Shlok tonight by 8:00 p.m.")

        assertEquals("Remember to send the APK to Shlok tonight by 8:00 p.m.", kept.getString("transcript"))
        assertTrue(kept.getBoolean("speech_detected"))
        // The notes themselves are left as they were.
        assertEquals(0, kept.getJSONArray("notes").length())
    }

    @Test
    fun textRequestsNeverGoToTheTranscriptionModel() {
        val forTranscribe = GeminiSummarizer.textModel(GeminiSummarizer.TRANSCRIBE_MODEL)
        assertTrue(forTranscribe != GeminiSummarizer.TRANSCRIBE_MODEL)
        assertEquals("gemma-4-26b-a4b-it", forTranscribe)

        // Every other choice stays exactly as selected.
        assertEquals(GeminiSummarizer.DEFAULT_MODEL, GeminiSummarizer.textModel(GeminiSummarizer.DEFAULT_MODEL))
        assertEquals("gemini-3.5-flash", GeminiSummarizer.textModel("gemini-3.5-flash"))
    }
}
