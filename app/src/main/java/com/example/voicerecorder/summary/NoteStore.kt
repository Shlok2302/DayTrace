package com.example.voicerecorder.summary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

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
 *
 * Deleting a note (or marking it as done) only moves it to the recycle
 * bin: it gets a "deleted_at" time and stays in its file until it is
 * restored, deleted for good, or [BIN_DAYS] days have passed.
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
                                    .put("id", newId(name))
                                    .put("category", it.category)
                                    .put("title", it.title)
                                    .put("note", it.text)
                                    .put("tags", JSONArray(it.tags))
                                    .putIfNotEmpty("due_date", it.dueDate)
                                    .putIfNotEmpty("due_time", it.dueTime)
                            }
                        )
                    )

            return synchronized(LOCK) { write(name, json) }

        } catch (e: Exception) {
            throw StorageException("Could not save the notes on this device", e)
        }
    }

    /**
     * Writes to a temporary file first, so a crash never leaves a
     * half-written file.
     */
    private fun write(
        name: String,
        json: JSONObject
    ): File {

        val file =
            File(directory, "$name.json")

        val temporary =
            File(directory, "$name.json.tmp")

        temporary.writeText(json.toString(2))

        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not rename ${temporary.name}")
        }

        return file
    }

    /**
     * When the recording started, from its name ("Voice_Recording_<millis>"),
     * or now if that cannot be read. Gemini works out deadlines such as
     * "tonight at 8" from it.
     */
    fun recordedAt(
        audioUri: Uri
    ): Long =
        runCatching { displayName(audioUri) }
            .getOrNull()
            ?.let(::timeFromName)
            ?: System.currentTimeMillis()

    // Recycle bin -----------------------------------------------------------

    /** The recording and note with this id, deleted or not. */
    fun findNote(
        noteId: String
    ): Pair<SavedRecording, Note>? {

        val recording =
            readJson(File(directory, "${recordingOf(noteId)}.json"))
                ?.let(::toSavedRecording)
                ?: return null

        val note =
            recording.notes.firstOrNull { it.id == noteId } ?: return null

        return recording to note
    }

    /**
     * Moves a note to the recycle bin. [done]: the user completed it
     * (from a reminder or the note screen) rather than deleting it.
     */
    fun moveToBin(
        noteId: String,
        done: Boolean
    ): Boolean =
        updateNote(noteId) { note ->
            // Already in the bin (e.g. "Done" tapped twice): keep its original time.
            if (note.has("deleted_at")) return@updateNote false
            val now = Instant.now().toString()
            note.put("deleted_at", now)
            if (done) note.put("done_at", now) else note.remove("done_at")
            true
        }

    /** Takes a note out of the recycle bin, exactly as it was. */
    fun restore(
        noteId: String
    ): Boolean =
        updateNote(noteId) { note ->
            val wasInBin = note.remove("deleted_at") != null
            note.remove("done_at")
            wasInBin
        }

    /** Removes a note from the recycle bin for good. */
    fun deleteForever(
        noteId: String
    ): Boolean =
        update(recordingOf(noteId)) { notes ->
            removeWhere(notes) { it.optString("id") == noteId } > 0
        }

    /**
     * Deletes for good every note that has been in the recycle bin for
     * [BIN_DAYS] days, or every note in it when [all] is true. Returns
     * the ids that were deleted.
     */
    fun purgeBin(
        all: Boolean = false
    ): List<String> {

        val cutoff =
            System.currentTimeMillis() - BIN_DAYS * DAY_MILLIS

        val purged =
            mutableListOf<String>()

        directory
            .listFiles { file -> file.extension == "json" }
            ?.forEach { file ->
                update(file.nameWithoutExtension) { notes ->
                    removeWhere(notes) { note ->
                        val deletedAt = millis(note.optString("deleted_at"))
                        val expired = deletedAt > 0 && (all || deletedAt <= cutoff)
                        if (expired) purged += note.optString("id")
                        expired
                    } > 0
                }
            }

        return purged
    }

    /** [change] returns true when it changed the note, so the file is written. */
    private fun updateNote(
        noteId: String,
        change: (JSONObject) -> Boolean
    ): Boolean =
        update(recordingOf(noteId)) { notes ->
            val note =
                (0 until notes.length())
                    .map { notes.getJSONObject(it) }
                    .firstOrNull { it.optString("id") == noteId }
                    ?: return@update false
            change(note)
        }

    /**
     * Reads a recording's file, lets [change] edit its notes and writes it
     * back if [change] returns true. Notes saved before ids existed get
     * their id ("<recording>#<position>") written first, so removing one
     * never changes the id of another.
     */
    private fun update(
        recording: String,
        change: (JSONArray) -> Boolean
    ): Boolean {

        synchronized(LOCK) {

            val json =
                readJson(File(directory, "$recording.json")) ?: return false

            val notes =
                json.optJSONArray("notes") ?: return false

            for (index in 0 until notes.length()) {
                val note = notes.getJSONObject(index)
                if (note.optString("id").isEmpty()) {
                    note.put("id", legacyId(recording, index))
                }
            }

            if (!change(notes)) {
                return false
            }

            write(recording, json)
            return true
        }
    }

    private fun removeWhere(
        notes: JSONArray,
        predicate: (JSONObject) -> Boolean
    ): Int {

        var removed = 0

        for (index in notes.length() - 1 downTo 0) {
            if (predicate(notes.getJSONObject(index))) {
                notes.remove(index)
                removed++
            }
        }

        return removed
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
            RecordingNotes(
                transcript = json.getString("transcript"),
                notes = toNotes(json).filterNot { it.isDeleted }
            )
        }.getOrNull()

    /**
     * Notes saved before titles and tags existed fall back to a title
     * made from the note itself and no tags; before ids existed, to an id
     * made from their position.
     */
    private fun toNotes(
        json: JSONObject
    ): List<Note> {

        val recording =
            json.optString("recording")

        val notes =
            json.optJSONArray("notes") ?: JSONArray()

        return (0 until notes.length()).map { index ->

            val note =
                notes.getJSONObject(index)

            val text =
                note.getString("note")

            val tags =
                note.optJSONArray("tags")

            Note(
                category = note.getString("category"),
                text = text,
                title = note.optString("title").trim().ifEmpty { Note.titleFrom(text) },
                tags = (0 until (tags?.length() ?: 0)).mapNotNull { tags?.optString(it) },
                dueDate = note.optString("due_date"),
                dueTime = note.optString("due_time"),
                id = note.optString("id").ifEmpty { legacyId(recording, index) },
                deletedAt = millis(note.optString("deleted_at")),
                doneAt = millis(note.optString("done_at"))
            )
        }
    }

    /**
     * Every processed recording, newest first. Used by the history and
     * stats screens; never touches the audio.
     */
    fun loadAll(): List<SavedRecording> =
        directory
            .listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> readJson(file)?.let { toSavedRecording(it) } }
            ?.sortedByDescending { it.recordedAt }
            .orEmpty()

    private fun toSavedRecording(
        json: JSONObject
    ): SavedRecording? =
        runCatching {

            val name =
                json.getString("recording")

            val processedAt =
                runCatching { Instant.parse(json.getString("processed_at")).toEpochMilli() }
                    .getOrDefault(0L)

            SavedRecording(
                name = name,
                audioUri = json.optString("audio_uri"),
                recordedAt = timeFromName(name) ?: processedAt,
                processedAt = processedAt,
                outcome = json.optString("outcome", RecordingNotes.OUTCOME_NOTES),
                transcript = json.optString("transcript"),
                notes = toNotes(json)
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

    /** "Voice_Recording_1789817319403" holds the time the recording started. */
    private fun timeFromName(
        name: String
    ): Long? =
        Regex("([0-9]{10,})").find(name)?.value?.toLongOrNull()

    private fun millis(
        instant: String
    ): Long =
        if (instant.isEmpty()) 0L else runCatching { Instant.parse(instant).toEpochMilli() }.getOrDefault(0L)

    private fun JSONObject.putIfNotEmpty(
        key: String,
        value: String
    ): JSONObject =
        if (value.isEmpty()) this else put(key, value)

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

        /** Notes stay in the recycle bin this long before they are deleted for good. */
        const val BIN_DAYS =
            30L

        const val DAY_MILLIS =
            24L * 60 * 60 * 1000

        /** One lock for every write, so a reminder and the UI never overwrite each other. */
        private val LOCK =
            Any()

        /** A note id is "<recording>#<suffix>", so the id alone finds its file. */
        fun recordingOf(
            noteId: String
        ): String =
            noteId.substringBeforeLast('#')

        private fun newId(
            recording: String
        ): String =
            "$recording#" + UUID.randomUUID().toString().take(8)

        private fun legacyId(
            recording: String,
            index: Int
        ): String =
            "$recording#$index"

        /*
         * A recording is given up after failing this many times in total
         * (each time after SummaryWorker's own retries). Its audio is kept.
         */
        private const val MAX_FAILURES =
            3
    }
}

/**
 * One processed recording as stored on the device, for the history,
 * stats and note screens.
 */
data class SavedRecording(
    val name: String,
    val audioUri: String,
    val recordedAt: Long,
    val processedAt: Long,
    val outcome: String,
    val transcript: String,
    val notes: List<Note>
) {

    /** The notes that are not in the recycle bin. */
    val activeNotes: List<Note>
        get() = notes.filterNot { it.isDeleted }
}
