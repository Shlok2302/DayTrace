package com.example.voicerecorder

import android.app.Application
import com.example.voicerecorder.summary.RecoveryWorker

class DayTraceApplication : Application() {

    override fun onCreate() {

        super.onCreate()

        /*
         * Queue any recording that was saved but never processed
         * (e.g. the app was killed right after saving the MP3).
         */
        RecoveryWorker.enqueue(this)
    }
}
