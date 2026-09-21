package com.example.voicerecorder.summary

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Importing an audio file from the phone. The file is never changed:
 * ImportWorker converts a copy to MP3 (the same FFmpeg conversion a
 * recording gets), saves that copy where recordings go, and hands it to
 * SummaryWorker. From there it is processed, retried, recovered and
 * deleted exactly like a recording.
 *
 * The notes of an imported file belong to when it was RECORDED. That time
 * is read from the file itself (see [inspect]); when it cannot be found,
 * the user confirms or picks it, it is never silently "now".
 */
object AudioImport {

    private const val TAG =
        "AudioImport"

    /** Must match the folder RecordingService saves recordings into (RecoveryWorker scans it). */
    private val RECORDINGS_FOLDER =
        "${Environment.DIRECTORY_MUSIC}/App Records"

    /** A picked file, and what could be found out about when it was recorded. */
    data class Picked(
        val uri: Uri,
        val name: String,
        val size: Long,
        /** Reliable: stored inside the file, or a date and time in its name. */
        val recordedAt: Long?,
        val recordedAtSource: String?,
        /** Not reliable on its own (copying changes it): only offered to the user. */
        val lastModified: Long?
    )

    fun inspect(
        context: Context,
        uri: Uri
    ): Picked {

        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size = -1L

        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }

        val fromFile =
            dateInsideFile(context, uri)

        val fromName =
            if (fromFile == null) dateInName(name) else null

        return Picked(
            uri = uri,
            name = name,
            size = size,
            recordedAt = fromFile ?: fromName,
            recordedAtSource = when {
                fromFile != null -> NoteStore.SOURCE_AUDIO_DETAILS
                fromName != null -> NoteStore.SOURCE_FILE_NAME
                else -> null
            },
            lastModified = lastModified(context, uri)
        )
    }

    /** A recording already made from this same file (same name and size), if any. */
    fun alreadyImported(
        context: Context,
        picked: Picked
    ): SavedRecording? =
        NoteStore(context).loadAll().firstOrNull {
            it.importedFrom == picked.name && it.importedSize == picked.size
        }

    /**
     * Starts importing [picked], recorded at [recordedAt] (from [source]).
     * Returns the name the imported recording gets.
     */
    fun start(
        context: Context,
        picked: Picked,
        recordedAt: Long,
        source: String
    ): String {

        val store =
            NoteStore(context)

        // Named after when it was recorded (like "Voice_Recording_<millis>"), so
        // the pipeline and Gemini see the original time. Unique by the millisecond.
        var millis = recordedAt
        while (store.nameInUse(recordingName(millis)) || inAppRecords(context, "${recordingName(millis)}.mp3")) {
            millis++
        }

        val name =
            recordingName(millis)

        // Reserves the name, and carries the original details to NoteStore.save().
        store.saveImportInfo(name, picked.name, picked.size, millis, source)

        // Lets the worker read the file even if the app is closed meanwhile.
        runCatching {
            context.contentResolver.takePersistableUriPermission(picked.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ImportWorker.enqueue(context, picked.uri, name, picked.name)

        Log.i(TAG, "Import queued: ${picked.name} as $name, recorded at ${Instant.ofEpochMilli(millis)} ($source)")

        return name
    }

    fun recordingName(
        millis: Long
    ): String =
        "Imported_$millis"

    /**
     * Saves the converted MP3 where recordings go (Music/App Records),
     * the same way RecordingService does, so recovery finds it too.
     */
    fun saveToAppRecords(
        context: Context,
        mp3: File,
        displayName: String
    ): Uri? {

        val resolver =
            context.contentResolver

        val values =
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RECORDINGS_FOLDER)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

        val uri =
            resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        return try {
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Could not open MediaStore output stream" }
                mp3.inputStream().use { it.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Could not save the imported MP3", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun inAppRecords(
        context: Context,
        displayName: String
    ): Boolean =
        runCatching {
            context.contentResolver
                .query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(displayName),
                    null
                )
                ?.use { it.count > 0 }
                ?: false
        }.getOrDefault(false)

    // When was it recorded? -------------------------------------------------

    /** The recording date many recorders store inside the file (e.g. M4A, MP4). */
    private fun dateInsideFile(
        context: Context,
        uri: Uri
    ): Long? {

        val raw =
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                } finally {
                    retriever.release()
                }
            }.getOrNull()?.trim()

        if (raw.isNullOrEmpty()) {
            return null
        }

        val millis =
            listOf(
                // "20260920T144500.000Z", what Android reports for MP4/M4A files
                { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")).toInstant(ZoneOffset.UTC).toEpochMilli() },
                { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC).toEpochMilli() },
                { OffsetDateTime.parse(raw).toInstant().toEpochMilli() },
                { Instant.parse(raw).toEpochMilli() }
            ).firstNotNullOfOrNull { parse -> runCatching(parse).getOrNull() }

        return millis?.takeIf(::plausible)
    }

    /**
     * A date AND time in the file name, e.g. "Recording_20260920_201500.m4a"
     * or "2026-09-20 20.15.m4a" (local time). A date alone is not enough.
     */
    fun dateInName(
        name: String
    ): Long? {

        val match =
            Regex("""(?<!\d)(20\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])[ _T-]?([01]\d|2[0-3])[-_.:h]?([0-5]\d)(?:[-_.:m]?([0-5]\d))?(?!\d)""")
                .find(name)
                ?: return null

        val (year, month, day, hour, minute) =
            match.destructured

        val second =
            match.groupValues[6].ifEmpty { "0" }

        return runCatching {
            LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()?.takeIf(::plausible)
    }

    private fun lastModified(
        context: Context,
        uri: Uri
    ): Long? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        }.getOrNull()?.takeIf(::plausible)

    /** Encoders write placeholders such as 1904 or 1970; a real date is from this century and not ahead. */
    private fun plausible(
        millis: Long
    ): Boolean =
        millis >= Instant.parse("2000-01-01T00:00:00Z").toEpochMilli() &&
                millis <= System.currentTimeMillis() + 24L * 60 * 60 * 1000
}
