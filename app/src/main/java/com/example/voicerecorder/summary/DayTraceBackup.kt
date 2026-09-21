package com.example.voicerecorder.summary

import android.content.Context
import com.example.voicerecorder.BuildConfig
import com.example.voicerecorder.reminders.Reminders
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exports the saved notes (read only: nothing is changed or deleted) and
 * restores them from a .daytrace backup.
 *
 * A .daytrace file is UTF-8 JSON:
 *   { "format": "daytrace-backup", "version": 1, "exported_at": ...,
 *     "app_version": ..., "notes_count": N, "recordings": [ ... ] }
 * where each recording is exactly what NoteStore keeps in its file, with
 * the note ids and the original recording time filled in.
 */
object DayTraceBackup {

    class BackupException(
        message: String
    ) : Exception(message)

    /** What a checked backup holds, before anything is written. */
    class Contents(
        val recordings: List<JSONObject>,
        val exportedAt: Long
    ) {

        val noteCount: Int
            get() = recordings.sumOf { it.getJSONArray("notes").length() }

        val noteIds: List<String>
            get() = recordings.flatMap { recording ->
                val notes = recording.getJSONArray("notes")
                (0 until notes.length()).map { notes.getJSONObject(it).getString("id") }
            }
    }

    data class RestoreResult(
        val added: Int,
        val alreadyHere: Int,
        val removed: Int
    )

    private const val FORMAT =
        "daytrace-backup"

    private const val VERSION =
        1

    /** Bigger than any real backup; stops a wrong file from filling memory. */
    private const val MAX_BYTES =
        50L * 1024 * 1024

    // Readable text ---------------------------------------------------------

    /** Heading, and the category it lists, in this order. */
    private val SECTIONS =
        listOf(
            "REMEMBER" to "Remember",
            "IDEAS" to "Idea",
            "THOUGHTS" to "Thoughts",
            "RANDOM GOSSIP" to "Random Gossip"
        )

    private val TEXT_DATE =
        DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)

    /**
     * Every note (not the recycle bin), grouped by category, newest first,
     * each with the date and time it was recorded and its full text.
     */
    fun exportText(
        context: Context
    ): String {

        val recordings =
            NoteStore(context).loadAll()

        // (recorded at, position in its recording, note), so notes from the
        // same recording keep the order they were said in.
        val notes =
            recordings.flatMap { recording ->
                recording.activeNotes.mapIndexed { index, note -> Triple(recording.recordedAt, index, note) }
            }

        return SECTIONS.joinToString("\n\n\n") { (heading, category) ->

            val entries =
                notes
                    .filter { it.third.category == category }
                    .sortedWith(compareByDescending<Triple<Long, Int, Note>> { it.first }.thenBy { it.second })

            val body =
                if (entries.isEmpty()) {
                    "No notes."
                } else {
                    entries.joinToString("\n\n") { (time, _, note) ->
                        Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(TEXT_DATE) +
                                "\n" + note.text
                    }
                }

            "DAYTRACE — $heading\n\n$body"
        } + "\n"
    }

    // Backup ----------------------------------------------------------------

    fun writeBackup(
        context: Context,
        output: OutputStream
    ): Int {

        val recordings =
            NoteStore(context).exportRecordings()

        val notes =
            recordings.sumOf { it.optJSONArray("notes")?.length() ?: 0 }

        val backup =
            JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("exported_at", Instant.now().toString())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("notes_count", notes)
                .put("recordings", JSONArray(recordings))

        output.bufferedWriter(Charsets.UTF_8).use { it.write(backup.toString(2)) }

        return notes
    }

    /**
     * Reads and checks a backup. Throws [BackupException] with a message
     * for the user if it is not a DayTrace backup, is from a newer
     * version, or is damaged. Nothing is written.
     */
    fun read(
        input: InputStream
    ): Contents {

        val bytes =
            input.use { stream ->
                val buffer = stream.readNBytesCompat(MAX_BYTES + 1)
                if (buffer.size > MAX_BYTES) throw BackupException("This file is too large to be a DayTrace backup.")
                buffer
            }

        val json =
            try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: JSONException) {
                throw BackupException("This file isn't a DayTrace backup.")
            }

        if (json.optString("format") != FORMAT) {
            throw BackupException("This file isn't a DayTrace backup.")
        }

        val version =
            json.optInt("version", -1)

        if (version > VERSION) {
            throw BackupException("This backup was made by a newer version of DayTrace. Update the app to restore it.")
        }

        if (version < 1) {
            throw BackupException("This DayTrace backup is damaged (unknown version).")
        }

        val recordings =
            json.optJSONArray("recordings")
                ?: throw BackupException("This DayTrace backup is damaged (no recordings).")

        val checked =
            (0 until recordings.length()).map { index ->
                val recording =
                    recordings.optJSONObject(index)
                        ?: throw BackupException("This DayTrace backup is damaged (recording ${index + 1}).")
                check(recording, index + 1)
                recording
            }

        val contents =
            Contents(checked, millis(json.optString("exported_at")))

        if (json.has("notes_count") && json.optInt("notes_count", -1) != contents.noteCount) {
            throw BackupException("This DayTrace backup is incomplete (the number of notes does not match).")
        }

        if (contents.noteIds.size != contents.noteIds.toSet().size) {
            throw BackupException("This DayTrace backup is damaged (the same note appears twice).")
        }

        return contents
    }

    /** Every field a restored note or recording needs, in the right form. */
    private fun check(
        recording: JSONObject,
        position: Int
    ) {

        fun damaged(detail: String): Nothing =
            throw BackupException("This DayTrace backup is damaged (recording $position: $detail).")

        val name =
            recording.optString("recording")

        // The name becomes a file name, so it must be a plain one.
        if (!NoteStore.isSafeName(name)) damaged("bad name")

        if (millis(recording.optString("recorded_at")) <= 0) damaged("no recording time")

        val notes =
            recording.optJSONArray("notes") ?: damaged("no notes list")

        for (index in 0 until notes.length()) {

            val note =
                notes.optJSONObject(index) ?: damaged("note ${index + 1}")

            val id =
                note.optString("id")

            if (!id.startsWith("$name#") || id.length == name.length + 1) damaged("note ${index + 1} id")
            if (note.optString("category") !in GeminiSummarizer.CATEGORIES) damaged("note ${index + 1} category")
            if (note.optString("note").isBlank()) damaged("note ${index + 1} text")

            if (note.has("due_date") && runCatching { LocalDate.parse(note.getString("due_date")) }.isFailure) {
                damaged("note ${index + 1} deadline")
            }

            if (note.has("due_time") && note.getString("due_time").isNotEmpty() &&
                runCatching { LocalTime.parse(note.getString("due_time")) }.isFailure
            ) {
                damaged("note ${index + 1} deadline time")
            }

            for (key in listOf("deleted_at", "done_at")) {
                if (note.has(key) && millis(note.getString(key)) <= 0) damaged("note ${index + 1} $key")
            }

            val tags = note.opt("tags")
            if (tags != null && tags !is JSONArray) damaged("note ${index + 1} tags")
        }
    }

    /**
     * Restores a checked backup. Adding keeps every note on this device and
     * adds only the notes that are not here yet (by note id), so importing
     * the same backup twice adds nothing. [replace] first deletes the
     * notes on this device; the user has to confirm that.
     */
    fun restore(
        context: Context,
        contents: Contents,
        replace: Boolean
    ): RestoreResult {

        val store =
            NoteStore(context)

        val removed =
            if (replace) store.deleteAllRecordings() else emptyList()

        // Deleted notes never remind.
        removed.forEach { Reminders.cancel(context, it) }

        val restoredAt =
            Instant.now().toString()

        val added =
            contents.recordings.sumOf { recording ->
                val copy = JSONObject(recording.toString())
                // The audio link belongs to the phone the backup was made on, and
                // the audio was deleted there once processed; it means nothing here.
                copy.put("audio_uri", "")
                copy.put("restored_at", restoredAt)
                store.restoreRecording(copy)
            }

        // Restored deadlines that are still ahead get their reminders.
        Reminders.sync(context)

        return RestoreResult(
            added = added,
            alreadyHere = contents.noteCount - added,
            removed = removed.size
        )
    }

    /** How many of the backup's notes are already on this device. */
    fun alreadyHere(
        context: Context,
        contents: Contents
    ): Int {
        val local = NoteStore(context).noteIds()
        return contents.noteIds.count { it in local }
    }

    private fun millis(
        instant: String
    ): Long =
        if (instant.isEmpty()) 0L else runCatching { Instant.parse(instant).toEpochMilli() }.getOrDefault(0L)

    private fun InputStream.readNBytesCompat(
        limit: Long
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
            if (total >= limit) break
        }
        return out.toByteArray()
    }
}
