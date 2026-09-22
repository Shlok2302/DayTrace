package com.example.voicerecorder.google.tasks

import android.content.Context
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
import com.example.voicerecorder.google.PendingTaskAdd
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends the tasks that are waiting (confirmed while offline or while
 * Google was busy) once the phone is online. Each is sent with the mark
 * it was given when the user confirmed it, and the list is searched for
 * that mark first, so a retry can never make a second task.
 */
class TaskSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val manager =
            GoogleIntegrationManager(applicationContext)

        val waiting =
            manager.store.pendingTasks().filter { it.state == PendingTaskAdd.STATE_WAITING }

        if (waiting.isEmpty() || !manager.isConnected(GoogleService.TASKS)) {
            return Result.success()
        }

        val adder =
            manager.taskAdder()

        var retry = false

        for (pending in waiting) {
            try {
                when (adder.send(pending)) {
                    is TaskAdder.Outcome.Queued -> retry = true
                    else -> Log.i(TAG, "A waiting task was added to Google Tasks")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GoogleException) {
                // Kept with its problem (reconnect / failed); the user decides.
                Log.w(TAG, "A waiting task could not be added: ${e.kind}")
            }
        }

        GoogleIntegrationManager.notifyChanged(applicationContext)

        return if (retry) Result.retry() else Result.success()
    }

    companion object {

        private const val TAG = "GoogleTaskSync"

        private const val WORK_NAME = "google-tasks-sync"

        fun enqueue(
            context: Context
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TaskSyncWorker>()
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
