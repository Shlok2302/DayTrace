package com.example.voicerecorder.summary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.voicerecorder.service.RecordingService

/**
 * Hook between the existing recorder and the Gemini summary.
 *
 * Listens for the "recording complete" broadcast that RecordingService
 * already sends after the MP3 is saved, and queues a SummaryWorker for
 * that file. The recording code itself is not changed.
 */
class RecordingCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (intent.action != RecordingService.ACTION_RECORDING_COMPLETE) {
            return
        }

        val audioUri =
            intent.getStringExtra(RecordingService.EXTRA_FILE_PATH)

        if (audioUri == null) {

            Log.w(
                "RecordingCompleteReceiver",
                "Recording complete without a file path"
            )

            return
        }

        SummaryWorker.enqueue(context, audioUri)
    }
}
