package com.example.voicerecorder.google.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.voicerecorder.google.GoogleException
import com.example.voicerecorder.google.GoogleIntegrationManager
import com.example.voicerecorder.google.GoogleService
import com.example.voicerecorder.summary.GeminiSummarizer
import com.example.voicerecorder.summary.NoteStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds out which new Remember notes are calendar events (EventDetector),
 * so the note cards can suggest "Add to Google Calendar". Only while
 * Google Calendar is connected. Reads the notes; never changes them.
 */
class EventDetectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val manager =
            GoogleIntegrationManager(applicationContext)

        /*
         * "Is this note an event or a to-do?" is one question with one
         * answer, and both integrations use it, so it is asked when
         * either of them is connected.
         */
        if (!manager.isConnected(GoogleService.CALENDAR) && !manager.isConnected(GoogleService.TASKS)) {
            return Result.success()
        }

        return try {
            val checked = detectNewNotes(applicationContext)
            if (checked > 0) GoogleIntegrationManager.notifyChanged(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only a suggestion: tried again later, never forever.
            Log.w(TAG, "Event detection failed: ${e.javaClass.simpleName}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {

        private const val TAG = "EventDetection"

        private const val WORK_NAME = "google-calendar-detection"

        private const val MAX_RETRIES = 3

        /** Notes recorded longer ago than this are not checked (their events have passed). */
        private const val RECENT_DAYS = 14L

        /** At most this many recordings per run, to be gentle with the Gemini quota. */
        private const val MAX_RECORDINGS = 6

        fun enqueue(
            context: Context
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<EventDetectionWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            )
        }

        /**
         * Checks the Remember notes that were not checked yet and could
         * still be upcoming. Returns how many notes were checked.
         */
        suspend fun detectNewNotes(
            context: Context
        ): Int {

            val store =
                GoogleIntegrationManager(context).store

            val known =
                store.suggestions().keys + store.calendarLinks().keys + store.pending().map { it.noteId }

            val today =
                LocalDate.now()

            val since =
                System.currentTimeMillis() - RECENT_DAYS * NoteStore.DAY_MILLIS

            val detector =
                EventDetector(context)

            var checked = 0

            NoteStore(context).loadAll()
                .asSequence()
                .mapNotNull { recording ->
                    val notes = recording.activeNotes.filter { note ->
                        note.category == GeminiSummarizer.REMEMBER &&
                                note.id !in known &&
                                upcoming(note.dueDate, recording.recordedAt, since, today)
                    }
                    if (notes.isEmpty()) null else recording to notes
                }
                .take(MAX_RECORDINGS)
                .toList()
                .forEach { (recording, notes) ->
                    val recordedAt = Instant.ofEpochMilli(recording.recordedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    val suggestions = detector.detect(notes, recording.transcript, recordedAt)
                    store.saveSuggestions(suggestions)
                    checked += suggestions.size
                }

            return checked
        }

        private fun upcoming(
            dueDate: String,
            recordedAt: Long,
            since: Long,
            today: LocalDate
        ): Boolean {
            val due = runCatching { LocalDate.parse(dueDate) }.getOrNull()
            return if (due != null) !due.isBefore(today) else recordedAt >= since
        }
    }
}

/**
 * Sends the events that are waiting (confirmed while offline or while
 * Google was busy) once the phone is online. Each is sent with the event
 * id it was given when the user confirmed it, so a retry can never make
 * a second copy.
 */
class GoogleSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val manager =
            GoogleIntegrationManager(applicationContext)

        val waiting =
            manager.store.pending()
                .filter { it.state == com.example.voicerecorder.google.PendingCalendarAdd.STATE_WAITING }

        if (waiting.isEmpty() || !manager.isConnected(GoogleService.CALENDAR)) {
            return Result.success()
        }

        val adder =
            manager.calendarAdder()

        var retry = false

        for (pending in waiting) {
            try {
                when (adder.send(pending)) {
                    is CalendarAdder.Outcome.Queued -> retry = true
                    else -> Log.i(TAG, "A waiting event was added to Google Calendar")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GoogleException) {
                // Kept with its problem (reconnect / failed); the user decides.
                Log.w(TAG, "A waiting event could not be added: ${e.kind}")
            }
        }

        GoogleIntegrationManager.notifyChanged(applicationContext)

        return if (retry) Result.retry() else Result.success()
    }

    companion object {

        private const val TAG = "GoogleSync"

        private const val WORK_NAME = "google-calendar-sync"

        fun enqueue(
            context: Context
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GoogleSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            )
        }

        fun cancel(
            context: Context
        ) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/**
 * New notes were saved (SummaryWorker's "complete" broadcast, which stays
 * inside the app): check them for events and to-dos, if Google Calendar
 * or Google Tasks is connected.
 */
class NewNotesReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val manager =
            GoogleIntegrationManager(context)

        if (manager.isConnected(GoogleService.CALENDAR) || manager.isConnected(GoogleService.TASKS)) {
            EventDetectionWorker.enqueue(context)
        }
    }
}
