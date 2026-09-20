package com.example.voicerecorder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.voicerecorder.settings.AppSettings
import com.example.voicerecorder.summary.RecoveryWorker

class DayTraceApplication : Application() {

    override fun onCreate() {

        super.onCreate()

        // Before any activity is created, so the first screen is already
        // in the colours the user picked under Settings > Appearance.
        AppCompatDelegate.setDefaultNightMode(AppSettings(this).theme.mode)

        /*
         * Queue any recording that was saved but never processed
         * (e.g. the app was killed right after saving the MP3).
         */
        RecoveryWorker.enqueue(this)
    }
}
