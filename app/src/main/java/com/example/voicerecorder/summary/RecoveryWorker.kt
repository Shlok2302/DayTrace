package com.example.voicerecorder.summary

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Startup safety net for recordings that were saved but never processed,
 * e.g. the app was killed between RecordingService saving the MP3 and
 * RecordingCompleteReceiver queuing SummaryWorker.
 *
 * - Only this app's recordings made after this check first ran are
 *   considered, so recordings from before the notes feature are never
 *   touched.
 * - Processed recordings no longer have an MP3, so they are never found.
 * - SummaryWorker jobs are unique per recording (KEEP), so a recording that
 *   is already queued or running is not queued twice. If its result was
 *   already saved, SummaryWorker reuses it: no second Gemini call, no
 *   duplicate notes.
 * - Recordings that failed for good (no speech, or too many failures) are
 *   skipped; their audio stays.
 */
class RecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        // The "Music/App Records" folder (RELATIVE_PATH) only exists on Android 10+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.success()
        }

        val store =
            NoteStore(applicationContext)

        var queued = 0
        var skipped = 0

        savedRecordings(recoverySince()).forEach { audioUri ->

            Log.i(TAG, "MP3 found: $audioUri")

            when {
                SummaryWorker.isQueued(applicationContext, audioUri.toString()) -> {
                    Log.i(TAG, "Already queued, not queuing again: $audioUri")
                }

                // If the check itself fails, queue anyway: SummaryWorker handles it safely.
                runCatching { store.canRetry(audioUri) }.getOrDefault(true) -> {
                    SummaryWorker.enqueue(applicationContext, audioUri.toString())
                    Log.i(TAG, "Recovery queued: $audioUri")
                    queued++
                }

                else -> {
                    Log.i(TAG, "Skipped, failed before (audio kept): $audioUri")
                    skipped++
                }
            }
        }

        Log.i(TAG, "Recovery done: $queued queued, $skipped skipped after failing")

        return Result.success()
    }

    /**
     * Seconds since epoch when this check first ran. Recordings older than
     * that were made before the notes feature and are left alone.
     */
    private fun recoverySince(): Long {

        val prefs =
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!prefs.contains(KEY_SINCE)) {
            prefs.edit()
                .putLong(KEY_SINCE, System.currentTimeMillis() / 1000)
                .commit()
        }

        return prefs.getLong(KEY_SINCE, Long.MAX_VALUE)
    }

    /**
     * This app's MP3s in Music/App Records added since [since], oldest first.
     * Recordings still being written (IS_PENDING) are not returned.
     */
    private fun savedRecordings(
        since: Long
    ): List<Uri> {

        val collection =
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"

        val args =
            arrayOf(
                "$RECORDINGS_FOLDER%",
                since.toString(),
                applicationContext.packageName
            )

        return applicationContext.contentResolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            )
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                    }
                }
            }
            .orEmpty()
    }

    companion object {

        private const val TAG =
            "RecoveryWorker"

        private const val PREFS =
            "recording_recovery"

        private const val KEY_SINCE =
            "since_seconds"

        // Must match the folder RecordingService.saveMp3ToMusicFolder() saves into.
        private val RECORDINGS_FOLDER =
            "${Environment.DIRECTORY_MUSIC}/App Records"

        fun enqueue(
            context: Context
        ) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "recording-recovery",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RecoveryWorker>().build()
                )
        }
    }
}
