package com.example.voicerecorder.google

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

/**
 * Shows Google's own account and consent screen and waits for the answer.
 * Registered once by the activity (see [Host]), so any screen can ask
 * without registering anything itself.
 */
class GoogleConsent(
    activity: ComponentActivity
) {

    /** The activity that owns the one [GoogleConsent]. */
    interface Host {
        val googleConsent: GoogleConsent
    }

    /** What Google's screen returned. [data] also explains a failure (read by GoogleAuthManager). */
    class Answer(
        val allowed: Boolean,
        val data: Intent?
    )

    private var waiting: CompletableDeferred<Answer>? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val answer = waiting
            waiting = null
            answer?.complete(Answer(result.resultCode == Activity.RESULT_OK, result.data))
        }

    /** Shows Google's screen and returns its answer. */
    suspend fun ask(
        pendingIntent: PendingIntent
    ): Answer {

        // A request that never came back (e.g. the screen was recreated) is given up.
        waiting?.complete(Answer(false, null))

        val answer =
            CompletableDeferred<Answer>()

        waiting = answer

        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())

        return answer.await()
    }
}
