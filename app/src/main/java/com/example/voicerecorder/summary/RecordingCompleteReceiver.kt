package com.example.voicerecorder.summary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.voicerecorder.settings.AppSettings
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

        val settings =
            AppSettings(context)

        if (!settings.autoProcess) {
            Log.i(TAG, "Auto-processing is off, keeping $audioUri unprocessed")
            return
        }

        val minimumMs =
            settings.minimumSeconds * 1000L

        if (minimumMs > 0 && durationMs(context, audioUri) in 1 until minimumMs) {
            Log.i(TAG, "Shorter than the minimum duration, skipping $audioUri")
            return
        }

        SummaryWorker.enqueue(context, audioUri)
    }

    /**
     * The recording's length, or -1 when it cannot be read (then it is
     * always processed).
     */
    private fun durationMs(
        context: Context,
        audioUri: String
    ): Long =
        runCatching {
            context.contentResolver
                .query(
                    Uri.parse(audioUri),
                    arrayOf(MediaStore.MediaColumns.DURATION),
                    null,
                    null,
                    null
                )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                }
                ?: -1L
        }.getOrDefault(-1L)

    private companion object {

        const val TAG =
            "RecordingCompleteReceiver"
    }
}
