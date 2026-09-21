package com.example.voicerecorder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.example.voicerecorder.reminders.AppNotifications
import com.example.voicerecorder.reminders.Reminders
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.NoteActions
import com.example.voicerecorder.summary.RecoveryWorker
import kotlin.concurrent.thread

class DayTraceApplication : Application() {

    override fun onCreate() {

        super.onCreate()

        // Before any activity is created, so the first screen is already
        // in the colours the user picked under Settings > Appearance.
        AppCompatDelegate.setDefaultNightMode(AppSettings(this).theme.mode)

        AppNotifications.createChannels(this)

        registerActivityLifecycleCallbacks(ForegroundTracker)

        /*
         * Queue any recording that was saved but never processed
         * (e.g. the app was killed right after saving the MP3).
         */
        RecoveryWorker.enqueue(this)

        // Empty the recycle bin of notes older than 30 days, and make sure
        // every reminder is set. Both read files, so not on the main thread.
        thread {
            try {
                val purged = NoteActions.purgeExpired(this)
                if (purged > 0) Log.i(TAG, "Recycle bin: $purged notes deleted after 30 days")
                Reminders.sync(this)
            } catch (e: Exception) {
                Log.e(TAG, "Recycle bin / reminder check failed", e)
            }
        }
    }

    /** Counts the app's visible screens, so notifications are only sent when the app is not open. */
    private object ForegroundTracker : ActivityLifecycleCallbacks {

        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            isInForeground = true
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            isInForeground = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {

        private const val TAG =
            "DayTraceApplication"

        /** True while one of the app's screens is on screen. */
        @Volatile
        var isInForeground: Boolean = false
            private set
    }
}
