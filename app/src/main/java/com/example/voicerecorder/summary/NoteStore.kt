package com.example.voicerecorder.summary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Saves each processed recording (transcript + ALL notes) as a JSON file
 * in the app's private storage: files/notes/<recording name>.json
 *
 * The worker only deletes the audio after this save succeeds, so the
 * text is never lost. Temporary until the app has a real database,
 * which can import these files.
 *
 * Also keeps a small record of failed recordings (files/failed/), so
 * RecoveryWorker knows which ones not to retry.
 */
class NoteStore(
    private val context: Context
) {

    class StorageException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    private val directory =
        File(context.filesDir, DIRECTORY)

    /**
     * Returns the saved file. Throws [StorageException] if it could not be
     * written; the audio must then be kept.
     */
    fun save(
        audioUri: Uri,
        result: RecordingNotes
    ): File {

        try {
            directory.mkdirs()

            val name =
                fileName(displayName(audioUri) ?: "recording_${audioUri.lastPathSegment}")

            val json =
                JSONObject()
                    .put("recording", name)
                    .put("audio_uri", audioUri.toString())
                    .put("processed_at", Instant.now().toString())
                    .put("outcome", result.outcome)
                    .put("transcript", result.transcript)
                    .put(
                        "notes",
                        JSONArray(
                            result.notes.map {
                                JSONObject()
                                    .put("category", it.category)
                                    .put("note", it.text)
                            }
                        )
                    )

            val file =
                File(directory, "$name.json")

            // Write to a temporary file first, so a crash never leaves a half-written file.
            val temporary =
                File(directory, "$name.json.tmp")

            temporary.writeText(json.toString(2))

            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IOException("Could not rename ${temporary.name}")
            }

            return file

        } catch (e: Exception) {
            throw StorageException("Could not save the notes on this device", e)
        }
    }

    /**
     * The result saved by an earlier, interrupted run for this recording,
     * or null if it was never saved.
     *
     * - Audio still exists: look up its file by the recording's unique
     *   name (the run stopped between saving and deleting).
     * - Audio already gone: look for a file saved for this exact audio URI
     *   (the run stopped between deleting and finishing).
     */
    fun find(
        audioUri: Uri
    ): RecordingNotes? {

        val name =
            displayName(audioUri)

        val file =
            if (name != null) {
                File(directory, "${fileName(name)}.json").takeIf { it.exists() }
            } else {
                directory
                    .listFiles { file -> file.extension == "json" }
                    ?.firstOrNull { readJson(it)?.optString("audio_uri") == audioUri.toString() }
            }

        return file?.let(::readJson)?.let(::toRecordingNotes)
    }

    /**
     * Remembers that processing this recording failed (its audio is kept),
     * in files/failed/<recording name>.json, counting the failures.
     * RecoveryWorker uses this so it never retries a recording forever.
     */
    fun recordFailure(
        audioUri: Uri,
        failure: FailureReason
    ) {

        val file =
            failureFile(audioUri) ?: return

        val failures =
            (readJson(file)?.optInt("failures") ?: 0) + 1

        file.parentFile?.mkdirs()

        file.writeText(
            JSONObject()
                .put("audio_uri", audioUri.toString())
                .put("reason", failure.name)
                .put("failures", failures)
                .put("failed_at", Instant.now().toString())
                .toString(2)
        )
    }

    /**
     * False when this recording failed for good: there was no speech in
     * it, or it already failed [MAX_FAILURES] times.
     */
    fun canRetry(
        audioUri: Uri
    ): Boolean {

        val failure =
            failureFile(audioUri)?.let(::readJson) ?: return true

        return failure.optString("reason") != FailureReason.NO_SPEECH.name &&
                failure.optInt("failures") < MAX_FAILURES
    }

    fun clearFailure(
        audioUri: Uri
    ) {
        failureFile(audioUri)?.delete()
    }

    private fun failureFile(
        audioUri: Uri
    ): File? =
        displayName(audioUri)?.let { File(context.filesDir, "$FAILED_DIRECTORY/${fileName(it)}.json") }

    private fun readJson(
        file: File
    ): JSONObject? =
        runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun toRecordingNotes(
        json: JSONObject
    ): RecordingNotes? =
        runCatching {
            val notes =
                json.getJSONArray("notes")

            RecordingNotes(
                transcript = json.getString("transcript"),
                notes = (0 until notes.length()).map { index ->
                    val note = notes.getJSONObject(index)
                    Note(note.getString("category"), note.getString("note"))
                }
            )
        }.getOrNull()

    /**
     * The recording's name, e.g. "Voice_Recording_123.mp3", or null if the
     * audio no longer exists. Query errors are thrown, not treated as
     * "missing", so a temporary problem can never look like a finished run.
     */
    private fun displayName(
        audioUri: Uri
    ): String? =
        context.contentResolver
            .query(audioUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    /**
     * "Voice_Recording_123.mp3" -> "Voice_Recording_123"
     */
    private fun fileName(
        displayName: String
    ): String =
        displayName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {

        private const val DIRECTORY =
            "notes"

        private const val FAILED_DIRECTORY =
            "failed"

        /*
         * A recording is given up after failing this many times in total
         * (each time after SummaryWorker's own retries). Its audio is kept.
         */
        private const val MAX_FAILURES =
            3
    }
}
