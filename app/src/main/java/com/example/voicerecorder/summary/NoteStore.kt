package com.example.voicerecorder.summary

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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

            // An imported file: when it was really recorded, and where it came from.
            val importInfo =
                readJson(importFile(name))

            // When the audio was RECORDED (never when it was processed).
            val (recordedAt, recordedAtSource) =
                when {
                    importInfo != null ->
                        millis(importInfo.optString("recorded_at")) to importInfo.optString("recorded_at_source")
                    timeFromName(name) != null ->
                        timeFromName(name)!! to SOURCE_RECORDING
                    else ->
                        (addedTime(audioUri) ?: 0L) to SOURCE_ADDED
                }

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

            if (recordedAt > 0) {
                json.put("recorded_at", Instant.ofEpochMilli(recordedAt).toString())
                json.put("recorded_at_source", recordedAtSource)
            }

            if (importInfo != null) {
                json.put(
                    "import",
                    JSONObject()
                        .put("original_file", importInfo.optString("original_file"))
                        .put("original_size", importInfo.optLong("original_size"))
                        .put("imported_at", importInfo.optString("imported_at"))
                )
            }

            val file =
                synchronized(LOCK) { write(name, json) }

            // Only once the result is safely written.
            importFile(name).delete()

            return file

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

    // Imported audio --------------------------------------------------------

    /**
     * Remembers, for an imported audio file about to be processed, when it
     * was really recorded and what the original file was. [save] adds this
     * to the result, so the notes carry the original recording time.
     */
    fun saveImportInfo(
        recording: String,
        originalFile: String,
        originalSize: Long,
        recordedAt: Long,
        recordedAtSource: String
    ) {

        val file =
            importFile(recording)

        file.parentFile?.mkdirs()

        file.writeText(
            JSONObject()
                .put("original_file", originalFile)
                .put("original_size", originalSize)
                .put("recorded_at", Instant.ofEpochMilli(recordedAt).toString())
                .put("recorded_at_source", recordedAtSource)
                .put("imported_at", Instant.now().toString())
                .toString(2)
        )
    }

    /** Forgets an import that failed before anything was saved. */
    fun clearImportInfo(
        recording: String
    ) {
        importFile(recording).delete()
    }

    /** True if a recording (or a pending import) already uses this name. */
    fun nameInUse(
        recording: String
    ): Boolean =
        File(directory, "$recording.json").exists() || importFile(recording).exists()

    private fun importFile(
        recording: String
    ): File =
        File(context.filesDir, "$IMPORTS_DIRECTORY/$recording.json")

    // Backup and restore ----------------------------------------------------

    /**
     * Every saved recording as written in its file, with the note ids and
     * the recording time filled in, for a .daytrace backup. Read only.
     */
    fun exportRecordings(): List<JSONObject> =
        directory
            .listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                val json = readJson(file) ?: return@mapNotNull null
                val saved = toSavedRecording(json) ?: return@mapNotNull null
                val notes = json.optJSONArray("notes") ?: JSONArray()
                for (index in 0 until notes.length()) {
                    val note = notes.getJSONObject(index)
                    if (note.optString("id").isEmpty()) {
                        note.put("id", legacyId(saved.name, index))
                    }
                }
                if (json.optString("recorded_at").isEmpty()) {
                    json.put("recorded_at", Instant.ofEpochMilli(saved.recordedAt).toString())
                }
                json
            }
            .orEmpty()

    /** The ids of every note on this device, including the recycle bin. */
    fun noteIds(): Set<String> =
        loadAll().flatMap { recording -> recording.notes.map { it.id } }.toSet()

    /**
     * Adds a recording from a backup. A recording that is not on this
     * device is written as it is; one that is gets only the notes it does
     * not have yet (matched by note id), so importing the same backup
     * again adds nothing. Returns the number of notes added.
     */
    fun restoreRecording(
        backup: JSONObject
    ): Int {

        val name =
            backup.getString("recording")

        synchronized(LOCK) {

            directory.mkdirs()

            val local =
                readJson(File(directory, "$name.json"))

            if (local == null) {
                write(name, backup)
                return backup.getJSONArray("notes").length()
            }

            val localNotes =
                local.optJSONArray("notes") ?: JSONArray().also { local.put("notes", it) }

            val localIds =
                (0 until localNotes.length()).map { index ->
                    localNotes.getJSONObject(index).optString("id").ifEmpty { legacyId(name, index) }
                }.toSet()

            // Keep the local ids fixed before adding anything.
            for (index in 0 until localNotes.length()) {
                val note = localNotes.getJSONObject(index)
                if (note.optString("id").isEmpty()) note.put("id", legacyId(name, index))
            }

            val backupNotes =
                backup.getJSONArray("notes")

            var added = 0

            for (index in 0 until backupNotes.length()) {
                val note = backupNotes.getJSONObject(index)
                if (note.getString("id") !in localIds) {
                    localNotes.put(note)
                    added++
                }
            }

            // Nothing new: the file on this device is left exactly as it is.
            if (added == 0) {
                return 0
            }

            if (local.optString("recorded_at").isEmpty() && backup.has("recorded_at")) {
                local.put("recorded_at", backup.getString("recorded_at"))
                local.put("recorded_at_source", backup.optString("recorded_at_source"))
            }

            write(name, local)

            return added
        }
    }

    /**
     * Deletes every saved recording on this device, for "Replace my notes"
     * when restoring a backup. Returns the ids of the notes that were removed.
     */
    fun deleteAllRecordings(): List<String> =
        synchronized(LOCK) {
            val ids = noteIds().toList()
            directory.listFiles { file -> file.extension == "json" }?.forEach { it.delete() }
            ids
        }

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

            val import =
                json.optJSONObject("import")

            SavedRecording(
                name = name,
                audioUri = json.optString("audio_uri"),
                // The stored recording time; older results only have it in the name.
                recordedAt = millis(json.optString("recorded_at")).takeIf { it > 0 }
                    ?: timeFromName(name)
                    ?: processedAt,
                processedAt = processedAt,
                outcome = json.optString("outcome", RecordingNotes.OUTCOME_NOTES),
                transcript = json.optString("transcript"),
                notes = toNotes(json),
                recordedAtSource = json.optString("recorded_at_source")
                    .ifEmpty { if (timeFromName(name) != null) SOURCE_RECORDING else "" },
                importedFrom = import?.optString("original_file")?.ifEmpty { null },
                importedSize = import?.optLong("original_size") ?: 0L
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

    /** When MediaStore added the audio file, or null. */
    private fun addedTime(
        audioUri: Uri
    ): Long? =
        runCatching {
            context.contentResolver
                .query(audioUri, arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) * 1000L else null }
        }.getOrNull()?.takeIf { it > 0 }

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

        private const val IMPORTS_DIRECTORY =
            "imports"

        /*
         * Where a recording's time came from ("recorded_at_source").
         * All of them mean "when the audio was recorded", never "when it
         * was processed".
         */
        const val SOURCE_RECORDING = "recording"         // DayTrace's own recording
        const val SOURCE_AUDIO_DETAILS = "audio_details" // date stored inside an imported file
        const val SOURCE_FILE_NAME = "file_name"         // date and time in the file's name
        const val SOURCE_FILE_DATE = "file_date"         // file's last change, confirmed by the user
        const val SOURCE_CHOSEN = "chosen"               // picked by the user when importing
        const val SOURCE_ADDED = "added"                 // when the audio file was created

        /** A name that is safe as a file name ("Voice_Recording_123"). */
        fun isSafeName(
            name: String
        ): Boolean =
            name.isNotEmpty() && !name.startsWith(".") && name.matches(Regex("[A-Za-z0-9._-]+"))

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
    val notes: List<Note>,
    /** Where [recordedAt] came from (NoteStore.SOURCE_*); empty for old results. */
    val recordedAtSource: String = "",
    /** The original file's name, for an imported audio file. */
    val importedFrom: String? = null,
    val importedSize: Long = 0L
) {

    /** The notes that are not in the recycle bin. */
    val activeNotes: List<Note>
        get() = notes.filterNot { it.isDeleted }
}
