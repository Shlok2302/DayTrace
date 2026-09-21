package com.example.voicerecorder.summary

import com.example.voicerecorder.DayTraceApplication
import com.example.voicerecorder.reminders.AppNotifications
import com.example.voicerecorder.reminders.Reminders
import com.example.voicerecorder.settings.AppSettings
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Background job that turns one saved recording into notes with Gemini.
 *
 * Audio lifecycle: the MP3 is kept while Gemini works on it. Only after a
 * successful transcript + notes, and after those are saved (NoteStore),
 * is the MP3 deleted. Any failure keeps the MP3.
 *
 * Interruptions (app closed, process killed, phone restarted) are safe:
 * WorkManager runs the job again, and a result that was already saved is
 * reused instead of calling Gemini a second time. Deleting always comes
 * after saving, so the audio can never be deleted without a saved result.
 *
 * Runs through WorkManager, so it survives the user leaving the app,
 * waits for a network connection, and retries temporary failures
 * (e.g. Gemini "high demand" errors). It reports progress with
 * app-local broadcasts, like RecordingService does.
 */
class SummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val audioUri =
            inputData.getString(KEY_AUDIO_URI)
                ?: return Result.failure()

        val uri =
            Uri.parse(audioUri)

        val store =
            NoteStore(applicationContext)

        sendStatus(ACTION_SUMMARY_STARTED) {
            putExtra(EXTRA_AUDIO_URI, audioUri)
        }

        return try {

            // An earlier run may have saved the result before it was interrupted.
            val saved =
                store.find(uri)

            val result =
                if (saved != null) {
                    Log.i(TAG, "Reusing the result saved earlier for $audioUri (${saved.notes.size} notes)")
                    saved
                } else {
                    // One Gemini call returns both the transcript and the notes.
                    Log.i(TAG, "Transcription: sending $audioUri to Gemini")

                    val result =
                        GeminiSummarizer(applicationContext)
                            .summarize(uri, store.recordedAt(uri))

                    Log.i(TAG, "Transcription received: ${result.transcript.length} characters")

                    Log.i(TAG, "Notes generated: ${result.notes.size} (${result.outcome})")

                    result.notes.forEach { note ->
                        val due = if (note.hasDue) " (due ${"${note.dueDate} ${note.dueTime}".trim()})" else ""
                        Log.i(TAG, "  ${note.category}: ${note.text}$due")
                    }

                    // Progress for the record screen: Gemini is done, saving now.
                    sendStatus(ACTION_SUMMARY_SAVING) {
                        putExtra(EXTRA_AUDIO_URI, audioUri)
                    }

                    // Save BEFORE deleting anything. Throws StorageException on failure.
                    val file =
                        store.save(uri, result)

                    Log.i(TAG, "Result saved: ${file.absolutePath}")

                    result
                }

            // A failure recorded by an earlier attempt no longer applies.
            runCatching { store.clearFailure(uri) }

            // Transcription succeeded and the result is saved, so the audio can go.
            val audioDeleted =
                deleteAudio(uri)

            // Reminders for Remember notes with a deadline. Never fails the job.
            runCatching { Reminders.sync(applicationContext) }
                .onFailure { Log.e(TAG, "Could not set the reminders", it) }

            notify { AppNotifications.showNotesReady(applicationContext, result.notes) }

            // Two parallel lists: categories[i] belongs to notes[i].
            sendStatus(ACTION_SUMMARY_COMPLETE) {
                putExtra(EXTRA_AUDIO_URI, audioUri)
                putExtra(EXTRA_OUTCOME, result.outcome)
                putStringArrayListExtra(EXTRA_CATEGORIES, ArrayList(result.notes.map { it.category }))
                putStringArrayListExtra(EXTRA_NOTES, ArrayList(result.notes.map { it.text }))
                putExtra(EXTRA_AUDIO_DELETED, audioDeleted)
            }

            Result.success()

        } catch (e: CancellationException) {
            throw e

        } catch (e: Exception) {

            val retryable =
                when (e) {
                    is GeminiSummarizer.GeminiException -> e.retryable
                    is NoteStore.StorageException -> true
                    else -> e is IOException
                }

            // Settings > AI & Processing can turn retries off.
            val maxRetries =
                if (AppSettings(applicationContext).retryFailed) MAX_RETRIES else 0

            if (retryable && runAttemptCount < maxRetries) {

                Log.w(TAG, "Summary attempt ${runAttemptCount + 1} failed, retrying", e)

                return Result.retry()
            }

            Log.e(TAG, "Summary failed (${failureReason(e)}) for $audioUri, recording kept", e)

            // Lets RecoveryWorker skip recordings that keep failing.
            runCatching { store.recordFailure(uri, failureReason(e)) }

            sendStatus(ACTION_SUMMARY_FAILED) {
                putExtra(EXTRA_AUDIO_URI, audioUri)
                putExtra(EXTRA_FAILURE, failureReason(e).name)
                putExtra(EXTRA_ERROR, errorMessage(e))
            }

            notify { AppNotifications.showProcessingFailed(applicationContext, errorMessage(e)) }

            Result.failure()
        }
    }

    /**
     * A processing update, only when the user wants them (Settings >
     * Notifications) and the app is not open: on the record screen the
     * result is already shown.
     */
    private fun notify(
        show: () -> Unit
    ) {
        if (AppSettings(applicationContext).processingUpdates && !DayTraceApplication.isInForeground) {
            runCatching(show).onFailure { Log.e(TAG, "Could not show the notification", it) }
        }
    }

    /**
     * Deletes the MP3. Only called once the result is saved.
     * Returns true if the audio is gone (also when an earlier,
     * interrupted run already deleted it).
     */
    private fun deleteAudio(
        audioUri: Uri
    ): Boolean {

        val deleted =
            try {
                applicationContext.contentResolver.delete(audioUri, null, null) > 0 ||
                        !audioExists(audioUri)
            } catch (e: Exception) {
                Log.e(TAG, "Could not delete $audioUri", e)
                false
            }

        if (deleted) {
            Log.i(TAG, "MP3 deleted: $audioUri")
        }

        return deleted
    }

    private fun audioExists(
        audioUri: Uri
    ): Boolean =
        applicationContext.contentResolver
            .query(audioUri, null, null, null, null)
            ?.use { it.moveToFirst() }
            ?: false

    private fun failureReason(
        e: Exception
    ): FailureReason =
        when (e) {
            is GeminiSummarizer.GeminiException -> e.failure
            is NoteStore.StorageException -> FailureReason.STORAGE
            is IOException -> FailureReason.NETWORK
            else -> FailureReason.PROCESSING_FAILED
        }

    private fun errorMessage(
        e: Exception
    ): String =
        when (e) {
            is GeminiSummarizer.GeminiException -> e.message.orEmpty()
            is NoteStore.StorageException -> "Could not save the notes on this device."
            is IOException -> "Could not reach Gemini. Check your internet connection."
            else -> "Unexpected error (${e.javaClass.simpleName})"
        }.take(200)

    private fun sendStatus(
        action: String,
        extras: Intent.() -> Unit = {}
    ) {
        val intent =
            Intent(action)
                .setPackage(applicationContext.packageName)
                .apply(extras)

        applicationContext.sendBroadcast(intent)
    }

    companion object {

        private const val TAG =
            "SummaryWorker"

        const val ACTION_SUMMARY_STARTED =
            "com.example.voicerecorder.SUMMARY_STARTED"

        /** Gemini answered; the transcript and notes are being saved. */
        const val ACTION_SUMMARY_SAVING =
            "com.example.voicerecorder.SUMMARY_SAVING"

        const val ACTION_SUMMARY_COMPLETE =
            "com.example.voicerecorder.SUMMARY_COMPLETE"

        /** Which recording an update is about. */
        const val EXTRA_AUDIO_URI =
            "audio_uri"

        const val ACTION_SUMMARY_FAILED =
            "com.example.voicerecorder.SUMMARY_FAILED"

        /*
         * Success: RecordingNotes.OUTCOME_NOTES or
         * RecordingNotes.OUTCOME_NOTHING_WORTH_KEEPING.
         */
        const val EXTRA_OUTCOME =
            "outcome"

        /*
         * Failure: a FailureReason name, e.g. "NO_SPEECH". Audio is kept.
         */
        const val EXTRA_FAILURE =
            "failure"

        const val EXTRA_CATEGORIES =
            "categories"

        const val EXTRA_NOTES =
            "notes"

        const val EXTRA_AUDIO_DELETED =
            "audio_deleted"

        const val EXTRA_ERROR =
            "error"

        private const val KEY_AUDIO_URI =
            "audio_uri"

        /*
         * Retries use WorkManager's exponential backoff:
         * roughly 10 s, 20 s, 40 s.
         */
        private const val MAX_RETRIES =
            3

        private fun uniqueWorkName(
            audioUri: String
        ): String =
            "summary:$audioUri"

        /**
         * True if this recording's job is waiting or running right now.
         */
        fun isQueued(
            context: Context,
            audioUri: String
        ): Boolean =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName(audioUri))
                .get()
                .any { !it.state.isFinished }

        fun enqueue(
            context: Context,
            audioUri: String
        ) {

            // Settings > AI & Processing decides whether mobile data is allowed.
            val network =
                if (AppSettings(context).processOnMobileData) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }

            val request =
                OneTimeWorkRequestBuilder<SummaryWorker>()
                    .setInputData(workDataOf(KEY_AUDIO_URI to audioUri))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(network)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()

            // One job per recording, even if the broadcast arrives twice.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueWorkName(audioUri), ExistingWorkPolicy.KEEP, request)
        }
    }
}
