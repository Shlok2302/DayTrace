package com.example.voicerecorder.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Alarms do not survive a restart or an app update, and a new time zone
 * moves every deadline. This sets the reminders again in those cases,
 * and when the user allows exact alarms (so they become exact).
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val pending =
            goAsync()

        thread {
            try {
                Reminders.sync(context.applicationContext)
                Log.i(TAG, "Reminders set again after ${intent.action}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not set the reminders again", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {

        const val TAG =
            "RescheduleReceiver"
    }
}
